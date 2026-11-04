#include <jni.h>
#include <windows.h>
#include <stdint.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

static HWND as_hwnd(jlong handle) {
    return (HWND)(intptr_t)handle;
}

static double dpi_scale(HWND window) {
    HDC dc = GetDC(window);
    if (dc == NULL) return 1.0;
    int dpi = GetDeviceCaps(dc, LOGPIXELSX);
    ReleaseDC(window, dc);
    if (dpi <= 0) return 1.0;
    return (double)dpi / 96.0;
}

static int scaled(int value, double scale) {
    return (int)lround((double)value * scale);
}

static int make_child(HWND child, HWND parent, int x, int y, int w, int h, int topmost) {
    if (child == NULL || !IsWindow(child)) return 0;
    LONG_PTR style = GetWindowLongPtr(child, GWL_STYLE);
    style &= ~(WS_POPUP | WS_CAPTION | WS_THICKFRAME | WS_SYSMENU |
               WS_MINIMIZEBOX | WS_MAXIMIZEBOX);
    style |= WS_CHILD | WS_VISIBLE;
    SetWindowLongPtr(child, GWL_STYLE, style);
    LONG_PTR exStyle = GetWindowLongPtr(child, GWL_EXSTYLE);
    exStyle &= ~WS_EX_APPWINDOW;
    exStyle |= WS_EX_TOOLWINDOW;
    SetWindowLongPtr(child, GWL_EXSTYLE, exStyle);
    SetLastError(0);
    if (SetParent(child, parent) == NULL && GetLastError() != 0) return 0;
    return SetWindowPos(child, topmost ? HWND_TOP : HWND_BOTTOM, x, y, w, h,
                        SWP_FRAMECHANGED | SWP_SHOWWINDOW | SWP_NOACTIVATE);
}

JNIEXPORT jlong JNICALL
Java_lab_sip_client_WindowLayout_findWindowNative(JNIEnv *env, jclass cls,
                                                  jstring title) {
    (void)cls;
    if (title == NULL) return 0;
    jsize length = (*env)->GetStringLength(env, title);
    /* JNI UTF-16 buffers are not guaranteed to have a trailing NUL. */
    WCHAR *chars = calloc((size_t)length + 1, sizeof(WCHAR));
    if (chars == NULL) return 0;
    (*env)->GetStringRegion(env, title, 0, length, (jchar *)chars);
    if ((*env)->ExceptionCheck(env)) { free(chars); return 0; }
    HWND found = NULL;
    DWORD processId = 0;
    while ((found = FindWindowExW(NULL, found, NULL, chars)) != NULL) {
        GetWindowThreadProcessId(found, &processId);
        if (processId == GetCurrentProcessId()) break;
    }
    free(chars);
    return (jlong)(intptr_t)found;
}

JNIEXPORT jboolean JNICALL
Java_lab_sip_client_WindowLayout_attachWindowsNative(JNIEnv *env, jclass cls,
                                              jlong parentHandle,
                                              jlong remoteHandle,
                                              jlong pipHandle,
                                              jint x, jint y, jint w, jint h) {
    (void)env;
    (void)cls;
    HWND parent = as_hwnd(parentHandle);
    HWND remote = as_hwnd(remoteHandle);
    HWND pip = as_hwnd(pipHandle);
    if (parent == NULL || !IsWindow(parent) || !IsWindow(remote)) return JNI_FALSE;
    if (parent == remote || parent == pip) return JNI_FALSE;

    double scale = dpi_scale(parent);
    int px = scaled(x, scale);
    int py = scaled(y, scale);
    int pw = scaled(w, scale);
    int ph = scaled(h, scale);

    if (!make_child(remote, parent, px, py, pw, ph, 0)) return JNI_FALSE;
    if (pip != NULL && IsWindow(pip)) {
        int pipW = pw / 4;
        if (pipW < 180) pipW = 180;
        if (pipW > 320) pipW = 320;
        int pipH = pipW * 9 / 16;
        int margin = 16;
        if (!make_child(pip, parent, px + pw - pipW - margin,
                        py + ph - pipH - margin, pipW, pipH, 1)) return JNI_FALSE;
    }
    return pip == NULL || IsWindow(pip) ? JNI_TRUE : JNI_FALSE;
}

/* Keep the previous entry point usable by clients built before this update. */
JNIEXPORT void JNICALL
Java_lab_sip_client_WindowLayout_attachNative(JNIEnv *env, jclass cls,
                                              jlong parent, jlong remote, jlong pip,
                                              jint x, jint y, jint w, jint h) {
    Java_lab_sip_client_WindowLayout_attachWindowsNative(env, cls, parent, remote, pip,
                                                        x, y, w, h);
}

JNIEXPORT void JNICALL
Java_lab_sip_client_WindowLayout_detachNative(JNIEnv *env, jclass cls,
                                              jlong remoteHandle,
                                              jlong pipHandle) {
    (void)env;
    (void)cls;
    HWND remote = as_hwnd(remoteHandle);
    HWND pip = as_hwnd(pipHandle);
    HWND windows[2] = {remote, pip};
    for (int i = 0; i < 2; i++) {
        HWND child = windows[i];
        if (child == NULL || !IsWindow(child)) continue;
        ShowWindow(child, SW_HIDE);
        SetParent(child, NULL);
        LONG_PTR style = GetWindowLongPtr(child, GWL_STYLE);
        style &= ~WS_CHILD;
        style |= WS_OVERLAPPEDWINDOW;
        SetWindowLongPtr(child, GWL_STYLE, style);
    }
}
