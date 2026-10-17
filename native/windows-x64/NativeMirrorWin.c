#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>
#include <string.h>

#define PROP_MIRROR_STATE L"SipClient_NativeMirrorState"
#define SDL_FLIP_NONE 0
#define SDL_FLIP_HORIZONTAL 1

typedef int (*PFN_SDL_RenderCopy)(void *renderer, void *texture, const void *srcrect, const void *dstrect);
typedef int (*PFN_SDL_RenderCopyEx)(void *renderer, void *texture, const void *srcrect, const void *dstrect, 
                                    const double angle, const void *center, const int flip);

static PFN_SDL_RenderCopy g_orig_SDL_RenderCopy = NULL;
static PFN_SDL_RenderCopyEx g_pfn_SDL_RenderCopyEx = NULL;
static volatile LONG g_hookInstalled = 0;
static volatile HWND g_mirroredHwnd = NULL;

static int My_SDL_RenderCopy(void *renderer, void *texture, const void *srcrect, const void *dstrect) {
    if (g_mirroredHwnd != NULL && g_pfn_SDL_RenderCopyEx != NULL) {
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
    if (!hSdl) return;
    
    g_pfn_SDL_RenderCopyEx = (PFN_SDL_RenderCopyEx)GetProcAddress(hSdl, "SDL_RenderCopyEx");
    PFN_SDL_RenderCopy targetFunc = (PFN_SDL_RenderCopy)GetProcAddress(hSdl, "SDL_RenderCopy");
    if (!g_pfn_SDL_RenderCopyEx || !targetFunc) return;
    
    HMODULE hPjsua = GetModuleHandleA("pjsua2.dll");
    if (!hPjsua) return;
    
    PIMAGE_DOS_HEADER dosHeader = (PIMAGE_DOS_HEADER)hPjsua;
    if (dosHeader->e_magic != IMAGE_DOS_SIGNATURE) return;
    PIMAGE_NT_HEADERS ntHeaders = (PIMAGE_NT_HEADERS)((BYTE*)hPjsua + dosHeader->e_lfanew);
    if (ntHeaders->Signature != IMAGE_NT_SIGNATURE) return;
    
    IMAGE_DATA_DIRECTORY importDir = ntHeaders->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (importDir.VirtualAddress == 0 || importDir.Size == 0) return;
    
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
                    }
                    return;
                }
            }
        }
    }
}

typedef struct {
    BOOL mirror;
} MirrorEnumContext;

static BOOL CALLBACK EnumChildMirrorProc(HWND child, LPARAM lParam) {
    MirrorEnumContext *ctx = (MirrorEnumContext *)lParam;
    if (!child) return TRUE;
    
    LONG_PTR ex = GetWindowLongPtrW(child, GWL_EXSTYLE);
    if (ctx->mirror) {
        ex |= WS_EX_LAYOUTRTL;
    } else {
        ex &= ~WS_EX_LAYOUTRTL;
    }
    SetWindowLongPtrW(child, GWL_EXSTYLE, ex);
    SetWindowPos(child, NULL, 0, 0, 0, 0, 
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED);
    InvalidateRect(child, NULL, TRUE);
    UpdateWindow(child);
    return TRUE;
}

static BOOL CALLBACK EnumChildCheckMirrorProc(HWND child, LPARAM lParam) {
    BOOL *foundMirrored = (BOOL *)lParam;
    if (!child) return TRUE;
    LONG_PTR ex = GetWindowLongPtrW(child, GWL_EXSTYLE);
    if ((ex & WS_EX_LAYOUTRTL) != 0) {
        *foundMirrored = TRUE;
        return FALSE;
    }
    return TRUE;
}

static void applyMirrorToHwnd(HWND hwnd, BOOL mirror) {
    if (!hwnd || !IsWindow(hwnd)) return;
    
    // 1. Install GPU hardware texture hook for SDL2 video pipeline if present
    installSdlHook();
    if (mirror) {
        g_mirroredHwnd = hwnd;
    } else if (g_mirroredHwnd == hwnd) {
        g_mirroredHwnd = NULL;
    }
    
    // 2. Apply WS_EX_LAYOUTRTL to the parent window itself
    LONG_PTR ex = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
    if (mirror) {
        ex |= WS_EX_LAYOUTRTL;
    } else {
        ex &= ~WS_EX_LAYOUTRTL;
    }
    SetWindowLongPtrW(hwnd, GWL_EXSTYLE, ex);
    SetWindowPos(hwnd, NULL, 0, 0, 0, 0, 
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED);
    
    // 3. Apply WS_EX_LAYOUTRTL to all child video windows (DirectShow / ActiveMovie / SDL view)
    MirrorEnumContext ctx;
    ctx.mirror = mirror;
    EnumChildWindows(hwnd, EnumChildMirrorProc, (LPARAM)&ctx);
    
    InvalidateRect(hwnd, NULL, TRUE);
    UpdateWindow(hwnd);
    
    // 4. Record mirror state property on window
    SetPropW(hwnd, PROP_MIRROR_STATE, (HANDLE)(INT_PTR)(mirror ? 1 : 0));
}

static void updateWindowTitle(JNIEnv *env, HWND hwnd, jstring jtitle) {
    if (!hwnd || !IsWindow(hwnd) || jtitle == NULL) return;
    jsize len = (*env)->GetStringLength(env, jtitle);
    if (len <= 0) return;
    const jchar *chars = (*env)->GetStringChars(env, jtitle, NULL);
    if (chars) {
        SetWindowTextW(hwnd, (const WCHAR *)chars);
        (*env)->ReleaseStringChars(env, jtitle, chars);
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
    
    if (g_mirroredHwnd == h) {
        return JNI_TRUE;
    }
    
    HANDLE prop = GetPropW(h, PROP_MIRROR_STATE);
    if ((INT_PTR)prop == 1) {
        return JNI_TRUE;
    }
    
    LONG_PTR ex = GetWindowLongPtrW(h, GWL_EXSTYLE);
    if ((ex & WS_EX_LAYOUTRTL) != 0) {
        return JNI_TRUE;
    }
    
    BOOL childMirrored = FALSE;
    EnumChildWindows(h, EnumChildCheckMirrorProc, (LPARAM)&childMirrored);
    if (childMirrored) {
        return JNI_TRUE;
    }
    
    return JNI_FALSE;
}
