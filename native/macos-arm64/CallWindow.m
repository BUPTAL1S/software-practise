#include <jni.h>
#include <stdint.h>
#import <Cocoa/Cocoa.h>

static NSWindow *as_window(jlong handle) {
    if (handle == 0) return nil;
    id object = (__bridge id)(void *)(intptr_t)handle;
    if ([object isKindOfClass:[NSWindow class]]) {
        return (NSWindow *)object;
    }
    if ([object isKindOfClass:[NSView class]]) {
        return [(NSView *)object window];
    }
    return nil;
}

JNIEXPORT void JNICALL
Java_lab_sip_client_WindowLayout_placePipNative(JNIEnv *env, jclass cls,
                                                jlong remoteWindow,
                                                jlong pipWindow) {
    (void)env;
    (void)cls;
    NSWindow *remote = as_window(remoteWindow);
    NSWindow *pip = as_window(pipWindow);
    if (remote == nil || pip == nil) return;

    NSRect remoteFrame = [remote frame];
    CGFloat width = remoteFrame.size.width / 4.0;
    if (width < 160.0) width = 160.0;
    if (width > 360.0) width = 360.0;
    CGFloat height = width * 9.0 / 16.0;
    if (height < 90.0) height = 90.0;
    CGFloat margin = 16.0;
    NSRect pipFrame = NSMakeRect(
        remoteFrame.origin.x + remoteFrame.size.width - width - margin,
        remoteFrame.origin.y + margin,
        width,
        height);

    [pip setStyleMask:NSWindowStyleMaskBorderless];
    [pip setLevel:NSFloatingWindowLevel];
    [pip setFrame:pipFrame display:YES];
    [pip orderFront:nil];
}

JNIEXPORT void JNICALL
Java_lab_sip_client_WindowLayout_restoreNative(JNIEnv *env, jclass cls,
                                               jlong pipWindow) {
    (void)env;
    (void)cls;
    NSWindow *pip = as_window(pipWindow);
    if (pip == nil) return;
    [pip setLevel:NSNormalWindowLevel];
}
