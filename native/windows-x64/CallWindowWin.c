#include <jni.h>
#include <windows.h>
#include <stdint.h>

static HWND as_hwnd(jlong handle) {
    return (HWND)(intptr_t)handle;
}

static void make_child(HWND child, HWND parent, int x, int y, int w, int h, int topmost) {
    if (child == NULL || !IsWindow(child)) return;
    LONG_PTR style = GetWindowLongPtr(child, GWL_STYLE);
    style &= ~(WS_POPUP | WS_CAPTION | WS_THICKFRAME | WS_SYSMENU |
               WS_MINIMIZEBOX | WS_MAXIMIZEBOX);
    style |= WS_CHILD | WS_VISIBLE;
    SetWindowLongPtr(child, GWL_STYLE, style);
    LONG_PTR exStyle = GetWindowLongPtr(child, GWL_EXSTYLE);
    exStyle &= ~WS_EX_APPWINDOW;
    exStyle |= WS_EX_TOOLWINDOW;
    SetWindowLongPtr(child, GWL_EXSTYLE, exStyle);
    SetParent(child, parent);
    SetWindowPos(child, topmost ? HWND_TOP : HWND_BOTTOM, x, y, w, h,
                 SWP_FRAMECHANGED | SWP_SHOWWINDOW | SWP_NOACTIVATE);
}

JNIEXPORT jlong JNICALL
Java_lab_sip_client_WindowLayout_findWindowNative(JNIEnv *env, jclass cls,
                                                  jstring title) {
    (void)cls;
    if (title == NULL) return 0;
    const jchar *chars = (*env)->GetStringChars(env, title, NULL);
    if (chars == NULL) return 0;
    HWND found = FindWindowW(NULL, (LPCWSTR)chars);
    (*env)->ReleaseStringChars(env, title, chars);
    return (jlong)(intptr_t)found;
}

JNIEXPORT void JNICALL
Java_lab_sip_client_WindowLayout_attachNative(JNIEnv *env, jclass cls,
                                              jlong parentHandle,
                                              jlong remoteHandle,
                                              jlong pipHandle,
                                              jint x, jint y, jint w, jint h) {
    (void)env;
    (void)cls;
    HWND parent = as_hwnd(parentHandle);
    HWND remote = as_hwnd(remoteHandle);
    HWND pip = as_hwnd(pipHandle);
    if (parent == NULL || !IsWindow(parent)) return;

    make_child(remote, parent, x, y, w, h, 0);
    if (pip != NULL && IsWindow(pip)) {
        int pipW = w / 4;
        if (pipW < 180) pipW = 180;
        if (pipW > 320) pipW = 320;
        int pipH = pipW * 9 / 16;
        int margin = 16;
        make_child(pip, parent, x + w - pipW - margin,
                   y + h - pipH - margin, pipW, pipH, 1);
    }
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
