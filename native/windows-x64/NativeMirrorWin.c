#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <SDL2/SDL.h>
#include <SDL2/SDL_syswm.h>

#define PROP_MIRROR_STATE L"SipClient_NativeMirrorState"
#define SDL_FLIP_NONE 0
#define SDL_FLIP_HORIZONTAL 1

typedef int (*PFN_SDL_RenderCopy)(void *renderer, void *texture, const void *srcrect, const void *dstrect);
typedef int (*PFN_SDL_RenderCopyEx)(void *renderer, void *texture, const void *srcrect, const void *dstrect, 
                                    const double angle, const void *center, const int flip);

static PFN_SDL_RenderCopy g_orig_SDL_RenderCopy = NULL;
static PFN_SDL_RenderCopyEx g_pfn_SDL_RenderCopyEx = NULL;
static volatile LONG g_hookInstalled = 0;
static PVOID volatile g_mirroredHwnd = NULL;
JNIEXPORT jint JNICALL Java_lab_sip_client_NativeMirror_mirrorApiVersionNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; return 2;
}
static SDL_Window *(*g_renderWindow)(SDL_Renderer*) = NULL;
static SDL_bool (*g_windowInfo)(SDL_Window*, SDL_SysWMinfo*) = NULL;

static BOOL rendererIsMirrored(void *renderer) {
    HWND selected = (HWND)InterlockedCompareExchangePointer(&g_mirroredHwnd, NULL, NULL);
    if (!selected || !g_renderWindow || !g_windowInfo) return FALSE;
    SDL_Window *window = g_renderWindow((SDL_Renderer*)renderer);
    SDL_SysWMinfo info;
    SDL_VERSION(&info.version);
    return window && g_windowInfo(window, &info) && info.subsystem == SDL_SYSWM_WINDOWS
        && info.info.win.window == selected && GetPropW(selected, PROP_MIRROR_STATE);
}

static int My_SDL_RenderCopy(void *renderer, void *texture, const void *srcrect, const void *dstrect) {
    if (g_pfn_SDL_RenderCopyEx != NULL && rendererIsMirrored(renderer)) {
        // True hardware GPU texture flip (Direct3D / OpenGL)
        return g_pfn_SDL_RenderCopyEx(renderer, texture, srcrect, dstrect, 0.0, NULL, SDL_FLIP_HORIZONTAL);
    }
    if (g_orig_SDL_RenderCopy) {
        return g_orig_SDL_RenderCopy(renderer, texture, srcrect, dstrect);
    }
    return 0;
}

static void installSdlHook(void) {
    if (InterlockedCompareExchange(&g_hookInstalled, 1, 0) != 0) {
        return;
    }
    HMODULE hSdl = GetModuleHandleA("SDL2.dll");
    if (!hSdl) {
        hSdl = LoadLibraryA("SDL2.dll");
    }
    if (!hSdl) goto failed;
    
    g_renderWindow = (SDL_Window *(*)(SDL_Renderer*))GetProcAddress(hSdl, "SDL_RenderGetWindow");
    g_windowInfo = (SDL_bool (*)(SDL_Window*, SDL_SysWMinfo*))GetProcAddress(hSdl, "SDL_GetWindowWMInfo");
    g_pfn_SDL_RenderCopyEx = (PFN_SDL_RenderCopyEx)GetProcAddress(hSdl, "SDL_RenderCopyEx");
    PFN_SDL_RenderCopy targetFunc = (PFN_SDL_RenderCopy)GetProcAddress(hSdl, "SDL_RenderCopy");
    if (!g_pfn_SDL_RenderCopyEx || !targetFunc || !g_renderWindow || !g_windowInfo) goto failed;
    
    HMODULE hPjsua = GetModuleHandleA("pjsua2.dll");
    if (!hPjsua) goto failed;
    
    PIMAGE_DOS_HEADER dosHeader = (PIMAGE_DOS_HEADER)hPjsua;
    if (dosHeader->e_magic != IMAGE_DOS_SIGNATURE) goto failed;
    PIMAGE_NT_HEADERS ntHeaders = (PIMAGE_NT_HEADERS)((BYTE*)hPjsua + dosHeader->e_lfanew);
    if (ntHeaders->Signature != IMAGE_NT_SIGNATURE) goto failed;
    
    IMAGE_DATA_DIRECTORY importDir = ntHeaders->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (importDir.VirtualAddress == 0 || importDir.Size == 0) goto failed;
    
    PIMAGE_IMPORT_DESCRIPTOR importDesc = (PIMAGE_IMPORT_DESCRIPTOR)((BYTE*)hPjsua + importDir.VirtualAddress);
    for (; importDesc->Name != 0; importDesc++) {
        const char *modName = (const char *)((BYTE*)hPjsua + importDesc->Name);
        if (_stricmp(modName, "SDL2.dll") == 0) {
            PIMAGE_THUNK_DATA thunk = (PIMAGE_THUNK_DATA)((BYTE*)hPjsua + importDesc->FirstThunk);
            for (; thunk->u1.Function != 0; thunk++) {
                PROC *pfn = (PROC *)&thunk->u1.Function;
                if (*pfn == (PROC)targetFunc) {
                    DWORD oldProtect;
                    if (VirtualProtect(pfn, sizeof(PROC), PAGE_EXECUTE_READWRITE, &oldProtect)) {
                        g_orig_SDL_RenderCopy = (PFN_SDL_RenderCopy)*pfn;
                        *pfn = (PROC)My_SDL_RenderCopy;
                        VirtualProtect(pfn, sizeof(PROC), oldProtect, &oldProtect);
                        return;
                    }
                    goto failed;
                }
            }
        }
    }
failed:
    // A preview may be requested before PJSUA loads SDL. Retry on the next request.
    InterlockedExchange(&g_hookInstalled, 0);
}

static void applyMirrorToHwnd(HWND hwnd, BOOL mirror) {
    if (!hwnd || !IsWindow(hwnd)) return;
    installSdlHook();
    if (mirror && g_orig_SDL_RenderCopy && g_renderWindow && g_windowInfo) {
        HWND old = (HWND)InterlockedExchangePointer(&g_mirroredHwnd, hwnd);
        if (old && old != hwnd) RemovePropW(old, PROP_MIRROR_STATE);
        SetPropW(hwnd, PROP_MIRROR_STATE, (HANDLE)1);
    } else {
        InterlockedCompareExchangePointer(&g_mirroredHwnd, NULL, hwnd);
        RemovePropW(hwnd, PROP_MIRROR_STATE);
    }
    // SDL performs exactly one texture flip. Never mirror Win32 child coordinates.
    LONG_PTR ex = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
    if (ex & WS_EX_LAYOUTRTL) SetWindowLongPtrW(hwnd, GWL_EXSTYLE, ex & ~WS_EX_LAYOUTRTL);
}

static void updateWindowTitle(JNIEnv *env, HWND hwnd, jstring jtitle) {
    if (!hwnd || !IsWindow(hwnd) || jtitle == NULL) return;
    jsize len = (*env)->GetStringLength(env, jtitle);
    if (len <= 0) return;
    WCHAR *chars = calloc((size_t)len + 1, sizeof(WCHAR));
    if (chars) {
        (*env)->GetStringRegion(env, jtitle, 0, len, (jchar*)chars);
        if ((*env)->ExceptionCheck(env)) { free(chars); return; }
        SetWindowTextW(hwnd, chars);
        free(chars);
    }
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    if (fdwReason == DLL_PROCESS_DETACH) {
        g_mirroredHwnd = NULL;
    }
    return TRUE;
}

JNIEXPORT void JNICALL Java_lab_sip_client_NativeMirror_applyMirror
  (JNIEnv *env, jclass cls, jlong hwnd, jstring jtitle)
{
    if (hwnd == 0) return;
    HWND h = (HWND)(intptr_t)hwnd;
    if (!IsWindow(h)) return;
    
    applyMirrorToHwnd(h, TRUE);
    updateWindowTitle(env, h, jtitle);
}

JNIEXPORT void JNICALL Java_lab_sip_client_NativeMirror_restoreNormalNative
  (JNIEnv *env, jclass cls, jlong hwnd, jstring jtitle)
{
    if (hwnd == 0) return;
    HWND h = (HWND)(intptr_t)hwnd;
    if (!IsWindow(h)) return;
    
    applyMirrorToHwnd(h, FALSE);
    updateWindowTitle(env, h, jtitle);
}

JNIEXPORT jboolean JNICALL Java_lab_sip_client_NativeMirror_isMirroredNative
  (JNIEnv *env, jclass cls, jlong hwnd)
{
    if (hwnd == 0) return JNI_FALSE;
    HWND h = (HWND)(intptr_t)hwnd;
    if (!IsWindow(h)) return JNI_FALSE;
    
    return InterlockedCompareExchangePointer(&g_mirroredHwnd, NULL, NULL) == h
        && GetPropW(h, PROP_MIRROR_STATE) ? JNI_TRUE : JNI_FALSE;
}
