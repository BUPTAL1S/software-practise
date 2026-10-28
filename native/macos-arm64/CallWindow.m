#include <jni.h>
#include <stdint.h>
#import <Cocoa/Cocoa.h>

static NSWindow *as_window(jlong handle) {
    if (handle == 0) return nil;
    void *ptr = (void *)(intptr_t)handle;
    for (NSWindow *window in [NSApp windows]) {
        if ((__bridge void *)window == ptr) {
            return window;
        }
        if ([window contentView] && (__bridge void *)[window contentView] == ptr) {
            return window;
        }
    }
    return nil;
}

JNIEXPORT jlong JNICALL
Java_lab_sip_client_WindowLayout_findWindowNative(JNIEnv *env, jclass cls,
                                                  jstring title) {
    (void)cls;
    if (title == NULL) return 0;
    jsize length = (*env)->GetStringLength(env, title);
    const jchar *chars = (*env)->GetStringChars(env, title, NULL);
    if (chars == NULL) return 0;
    NSString *wanted = [NSString stringWithCharacters:(const unichar *)chars length:length];
    (*env)->ReleaseStringChars(env, title, chars);

    for (NSWindow *window in [NSApp windows]) {
        if ([[window title] isEqualToString:wanted]) {
            return (jlong)(intptr_t)(__bridge void *)window;
        }
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_lab_sip_client_WindowLayout_attachNative(JNIEnv *env, jclass cls,
                                              jlong parentHandle,
                                              jlong remoteHandle,
                                              jlong pipHandle,
                                              jint x, jint y, jint w, jint h) {
    (void)env;
    (void)cls;
    NSWindow *parent = as_window(parentHandle);
    NSWindow *remote = as_window(remoteHandle);
    NSWindow *pip = as_window(pipHandle);
    if (parent == nil || remote == nil) return;

    NSRect content = [parent contentRectForFrameRect:[parent frame]];
    NSRect contentOnScreen = [parent convertRectToScreen:content];
    CGFloat baseX = contentOnScreen.origin.x;
    CGFloat baseY = contentOnScreen.origin.y;
    CGFloat contentH = contentOnScreen.size.height;

    [remote setStyleMask:NSWindowStyleMaskBorderless];
    if ([remote parentWindow] != parent) {
        [parent addChildWindow:remote ordered:NSWindowAbove];
    }
    [remote setFrame:NSMakeRect(baseX + x, baseY + contentH - y - h, w, h) display:YES];
    [remote orderFront:nil];

    if (pip != nil) {
        CGFloat pipW = w / 4.0;
        if (pipW < 180.0) pipW = 180.0;
        if (pipW > 320.0) pipW = 320.0;
        CGFloat pipH = pipW * 9.0 / 16.0;
        CGFloat margin = 16.0;
        [pip setStyleMask:NSWindowStyleMaskBorderless];
        if ([pip parentWindow] != parent) {
            [parent addChildWindow:pip ordered:NSWindowAbove];
        }
        [pip setFrame:NSMakeRect(baseX + x + w - pipW - margin,
                                 baseY + contentH - (y + h) + margin, pipW, pipH) display:YES];
        [pip orderFront:nil];
    }
}

JNIEXPORT void JNICALL
Java_lab_sip_client_WindowLayout_detachNative(JNIEnv *env, jclass cls,
                                              jlong remoteHandle,
                                              jlong pipHandle) {
    (void)env;
    (void)cls;
    NSWindow *remote = as_window(remoteHandle);
    NSWindow *pip = as_window(pipHandle);
    NSWindow *windows[2] = {remote, pip};
    for (int i = 0; i < 2; i++) {
        NSWindow *window = windows[i];
        if (window == nil) continue;
        [window orderOut:nil];
        if ([window parentWindow] != nil) {
            [[window parentWindow] removeChildWindow:window];
        }
    }
}
