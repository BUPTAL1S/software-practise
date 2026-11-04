#include <jni.h>
#include <windows.h>
#include <stdint.h>
#include <stdlib.h>
#include <math.h>
static HWND as_hwnd(jlong value) { return (HWND)(intptr_t)value; }
static BOOL owned(HWND window) {
    DWORD process = 0;
    if (!window || !IsWindow(window)) return FALSE;
    GetWindowThreadProcessId(window, &process);
    return process == GetCurrentProcessId();
}
static double dpi_scale(HWND window) {
    typedef UINT (WINAPI *GetDpi)(HWND);
    GetDpi getDpi = (GetDpi)GetProcAddress(GetModuleHandleW(L"user32.dll"), "GetDpiForWindow");
    UINT dpi = getDpi ? getDpi(window) : 96;
    return dpi ? (double)dpi / 96.0 : 1.0;
}
static BOOL make_child(HWND child, HWND parent, int x, int y, int w, int h) {
    if (!owned(child) || child == parent || w < 1 || h < 1) return FALSE;
    LONG_PTR style = GetWindowLongPtrW(child, GWL_STYLE);
    LONG_PTR wanted = (style & ~(WS_POPUP | WS_CAPTION | WS_THICKFRAME | WS_SYSMENU |
        WS_MINIMIZEBOX | WS_MAXIMIZEBOX)) | WS_CHILD | WS_CLIPSIBLINGS;
    if (style != wanted) SetWindowLongPtrW(child, GWL_STYLE, wanted);
    LONG_PTR ex = GetWindowLongPtrW(child, GWL_EXSTYLE);
    SetWindowLongPtrW(child, GWL_EXSTYLE, (ex & ~(WS_EX_APPWINDOW | WS_EX_LAYOUTRTL)) | WS_EX_TOOLWINDOW);
    if (GetParent(child) != parent) {
        SetLastError(0);
        HWND previous = SetParent(child, parent);
        if (!previous && GetLastError() != 0) return FALSE;
    }
    if (!SetWindowPos(child, HWND_TOP, x, y, w, h,
        SWP_FRAMECHANGED | SWP_SHOWWINDOW | SWP_NOACTIVATE | SWP_NOSENDCHANGING)) return FALSE;
    RECT actual;
    return GetParent(child) == parent && GetClientRect(child, &actual)
        && actual.right == w && actual.bottom == h;
}
JNIEXPORT jint JNICALL Java_lab_sip_client_WindowLayout_layoutApiVersionNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; return 2;
}
JNIEXPORT jlong JNICALL Java_lab_sip_client_WindowLayout_findWindowNative(JNIEnv *env, jclass cls, jstring title) {
    (void)cls;
    if (!title) return 0;
    jsize length = (*env)->GetStringLength(env, title);
    WCHAR *text = calloc((size_t)length + 1, sizeof(WCHAR));
    if (!text) return 0;
    (*env)->GetStringRegion(env, title, 0, length, (jchar*)text);
    if ((*env)->ExceptionCheck(env)) { free(text); return 0; }
    HWND found = NULL;
    while ((found = FindWindowExW(NULL, found, NULL, text)) != NULL) {
        if (owned(found)) break;
    }
    free(text);
    return owned(found) ? (jlong)(intptr_t)found : 0;
}
JNIEXPORT jboolean JNICALL Java_lab_sip_client_WindowLayout_attachScaledNative(JNIEnv *env, jclass cls,
    jlong parentHandle, jlong remoteHandle, jlong pipHandle, jint x, jint y, jint w, jint h,
    jdouble scaleX, jdouble scaleY) {
    (void)env; (void)cls;
    HWND parent = as_hwnd(parentHandle), remote = as_hwnd(remoteHandle), pip = as_hwnd(pipHandle);
    if (!owned(parent) || (!remote && !pip) || w <= 0 || h <= 0) return JNI_FALSE;
    double sx = scaleX > 0 ? scaleX : dpi_scale(parent), sy = scaleY > 0 ? scaleY : dpi_scale(parent);
    if (!isfinite(sx) || !isfinite(sy) || sx > 8 || sy > 8) return JNI_FALSE;
    int px = (int)lround(x * sx), py = (int)lround(y * sy);
    int pw = (int)lround(w * sx), ph = (int)lround(h * sy);
    RECT client;
    if (!GetClientRect(parent, &client)) return JNI_FALSE;
    if (px < 0) px = 0;
    if (py < 0) py = 0;
    if (pw > client.right - px) pw = client.right - px;
    if (ph > client.bottom - py) ph = client.bottom - py;
    if (pw < 2 || ph < 2) return JNI_FALSE;
    BOOL ok = !remote || make_child(remote, parent, px, py, pw, ph);
    if (pip) {
        int margin = (int)lround(12 * sx), pipW = pw / 4;
        int cap = (int)lround(280 * sx);
        if (pipW > cap) pipW = cap;
        if (pipW > pw - 2 * margin) pipW = pw - 2 * margin;
        if (pipW * 9 / 16 > ph - 2 * margin) pipW = (ph - 2 * margin) * 16 / 9;
        int pipH = pipW * 9 / 16;
        ok = make_child(pip, parent, px + pw - pipW - margin, py + ph - pipH - margin, pipW, pipH) && ok;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}
/* Preserve both historical JNI signatures; never reinterpret old argument lists. */
JNIEXPORT jboolean JNICALL Java_lab_sip_client_WindowLayout_attachWindowsNative(JNIEnv *env, jclass cls,
    jlong parent, jlong remote, jlong pip, jint x, jint y, jint w, jint h) {
    return Java_lab_sip_client_WindowLayout_attachScaledNative(env, cls, parent, remote, pip, x, y, w, h, 0, 0);
}
JNIEXPORT void JNICALL Java_lab_sip_client_WindowLayout_attachNative(JNIEnv *env, jclass cls,
    jlong parent, jlong remote, jlong pip, jint x, jint y, jint w, jint h) {
    Java_lab_sip_client_WindowLayout_attachScaledNative(env, cls, parent, remote, pip, x, y, w, h, 0, 0);
}
JNIEXPORT void JNICALL Java_lab_sip_client_WindowLayout_detachNative(JNIEnv *env, jclass cls,
    jlong remoteHandle, jlong pipHandle) {
    (void)env; (void)cls;
    HWND windows[2] = {as_hwnd(remoteHandle), as_hwnd(pipHandle)};
    for (int i = 0; i < 2; i++) {
        HWND child = windows[i];
        if (!owned(child)) continue;
        ShowWindow(child, SW_HIDE); SetParent(child, NULL);
        LONG_PTR style = GetWindowLongPtrW(child, GWL_STYLE);
        SetWindowLongPtrW(child, GWL_STYLE, (style & ~(WS_CHILD | WS_VISIBLE)) | WS_OVERLAPPEDWINDOW);
        // Reparenting can cause SDL/Win32 to restore visibility. Hide last.
        ShowWindow(child, SW_HIDE);
    }
}

JNIEXPORT jlongArray JNICALL Java_lab_sip_client_WindowLayout_describeNative(JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    HWND child = as_hwnd(handle);
    if (!owned(child)) return (*env)->NewLongArray(env, 0);
    HWND parent = GetParent(child);
    RECT rect; GetClientRect(child, &rect);
    POINT origin = {0, 0}; MapWindowPoints(child, parent, &origin, 1);
    jlong data[6] = {(jlong)(intptr_t)parent, origin.x, origin.y, rect.right, rect.bottom, IsWindowVisible(child)};
    jlongArray result = (*env)->NewLongArray(env, 6);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 6, data);
    return result;
}
