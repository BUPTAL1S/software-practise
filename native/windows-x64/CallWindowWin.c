#include <jni.h>
#include <windows.h>
#include <stdint.h>

static HWND as_hwnd(jlong handle) {
    return (HWND)(intptr_t)handle;
}

static int clamp_int(int value, int lo, int hi) {
    if (value < lo) return lo;
    if (value > hi) return hi;
    return value;
}

JNIEXPORT void JNICALL
Java_lab_sip_client_WindowLayout_placePipNative(JNIEnv *env, jclass cls,
                                                jlong remoteWindow,
                                                jlong pipWindow) {
    (void)env;
    (void)cls;
    HWND remote = as_hwnd(remoteWindow);
    HWND pip = as_hwnd(pipWindow);
    if (remote == NULL || pip == NULL) return;

    RECT r;
    if (!GetWindowRect(remote, &r)) return;
    int rw = r.right - r.left;
    int rh = r.bottom - r.top;
    if (rw <= 0 || rh <= 0) return;

    int w = clamp_int(rw / 4, 160, 360);
    int h = (int)((int64_t)w * 9 / 16);
    if (h < 90) h = 90;
    int margin = 16;
    int x = r.right - w - margin;
    int y = r.bottom - h - margin;

    LONG_PTR style = GetWindowLongPtr(pip, GWL_STYLE);
    style &= ~(WS_CAPTION | WS_THICKFRAME | WS_SYSMENU | WS_MINIMIZEBOX | WS_MAXIMIZEBOX);
    style |= WS_POPUP | WS_VISIBLE;
    SetWindowLongPtr(pip, GWL_STYLE, style);

    LONG_PTR exStyle = GetWindowLongPtr(pip, GWL_EXSTYLE);
    exStyle |= WS_EX_TOOLWINDOW;
    SetWindowLongPtr(pip, GWL_EXSTYLE, exStyle);

    SetWindowPos(pip, HWND_TOPMOST, x, y, w, h,
                 SWP_SHOWWINDOW | SWP_NOACTIVATE | SWP_FRAMECHANGED);
}

JNIEXPORT void JNICALL
Java_lab_sip_client_WindowLayout_restoreNative(JNIEnv *env, jclass cls,
                                               jlong pipWindow) {
    (void)env;
    (void)cls;
    HWND pip = as_hwnd(pipWindow);
    if (pip == NULL) return;
    LONG_PTR exStyle = GetWindowLongPtr(pip, GWL_EXSTYLE);
    exStyle &= ~WS_EX_TOOLWINDOW;
    SetWindowLongPtr(pip, GWL_EXSTYLE, exStyle);
    SetWindowPos(pip, HWND_NOTOPMOST, 0, 0, 0, 0,
                 SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
}
