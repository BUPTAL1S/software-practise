#include <jni.h>
#include <windows.h>
#include <stdint.h>

/* Test-only hook in the same process. It observes private WM_APP heartbeats;
 * no keyboard/mouse input is sent, intercepted, or changed. */
static const UINT HEARTBEAT = WM_APP + 0x537;
static HANDLE received;
static HHOOK hook;
static DWORD owner;
static volatile LONG sequence;
static SRWLOCK gate = SRWLOCK_INIT;

JNIEXPORT jboolean JNICALL Java_lab_sip_client_SdlEventProbe_windowExists(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    return IsWindow((HWND)(intptr_t)handle) ? JNI_TRUE : JNI_FALSE;
}

static LRESULT CALLBACK observe(int code, WPARAM removed, LPARAM value) {
    if (code >= 0 && removed == PM_REMOVE) {
        MSG *message = (MSG *)value;
        if (message->message == HEARTBEAT &&
            message->wParam == (WPARAM)InterlockedCompareExchange(&sequence, 0, 0)) {
            AcquireSRWLockShared(&gate);
            if (received) SetEvent(received);
            ReleaseSRWLockShared(&gate);
        }
    }
    return CallNextHookEx(NULL, code, removed, value);
}

JNIEXPORT jboolean JNICALL Java_lab_sip_client_SdlEventProbe_begin(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    HWND window = (HWND)(intptr_t)handle;
    DWORD pid = 0;
    if (hook || !IsWindow(window)) return JNI_FALSE;
    owner = GetWindowThreadProcessId(window, &pid);
    if (!owner || pid != GetCurrentProcessId()) return JNI_FALSE;
    HMODULE module = NULL;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
            GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT, (LPCWSTR)observe, &module)) return JNI_FALSE;
    received = CreateEventW(NULL, FALSE, FALSE, NULL);
    if (!received) return JNI_FALSE;
    hook = SetWindowsHookExW(WH_GETMESSAGE, observe, module, owner);
    if (!hook) { CloseHandle(received); received = NULL; return JNI_FALSE; }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_lab_sip_client_SdlEventProbe_heartbeat(JNIEnv *env, jclass cls, jint timeout) {
    (void)env; (void)cls;
    if (!hook || !received || timeout < 1 || timeout > 5000) return JNI_FALSE;
    ResetEvent(received);
    LONG value = InterlockedIncrement(&sequence);
    if (!PostThreadMessageW(owner, HEARTBEAT, (WPARAM)value, 0)) return JNI_FALSE;
    return WaitForSingleObject(received, (DWORD)timeout) == WAIT_OBJECT_0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_lab_sip_client_SdlEventProbe_end(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (hook) { UnhookWindowsHookEx(hook); hook = NULL; }
    AcquireSRWLockExclusive(&gate);
    if (received) { CloseHandle(received); received = NULL; }
    owner = 0;
    ReleaseSRWLockExclusive(&gate);
}
