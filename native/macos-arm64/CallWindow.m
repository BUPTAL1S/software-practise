#include <jni.h>
#include <stdint.h>
#import <Cocoa/Cocoa.h>

static void run_on_main(void (^block)(void)) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

static BOOL isViewInHierarchy(NSView *root, void *target) {
    if (!root) return NO;
    if ((__bridge void *)root == target) return YES;
    for (NSView *sub in [root subviews]) {
        if (isViewInHierarchy(sub, target)) return YES;
    }
    return NO;
}

static NSWindow *as_window(jlong handle) {
    if (handle == 0) return nil;
    void *ptr = (void *)(intptr_t)handle;
    for (NSWindow *window in [NSApp windows]) {
        if ((__bridge void *)window == ptr) {
            return window;
        }
        NSView *content = [window contentView];
        if (content && ((__bridge void *)content == ptr || isViewInHierarchy(content, ptr))) {
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

    __block jlong found = 0;
    run_on_main(^{
        for (NSWindow *window in [NSApp windows]) {
            if ([[window title] isEqualToString:wanted]) {
                found = (jlong)(intptr_t)(__bridge void *)window;
                break;
            }
        }
    });
    return found;
}

JNIEXPORT jint JNICALL Java_lab_sip_client_WindowLayout_layoutApiVersionNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; return 2;
}

JNIEXPORT jboolean JNICALL
Java_lab_sip_client_WindowLayout_attachScaledNative(JNIEnv *env, jclass cls,
                                              jlong parentHandle,
                                              jlong remoteHandle,
                                              jlong pipHandle,
                                              jint x, jint y, jint w, jint h, jdouble scaleX, jdouble scaleY) {
    (void)env;
    (void)cls;
    (void)scaleX;
    (void)scaleY;

    __block jboolean success = JNI_FALSE;
    run_on_main(^{
        NSWindow *parent = as_window(parentHandle);
        NSWindow *remote = as_window(remoteHandle);
        NSWindow *pip = as_window(pipHandle);
        if (parent == nil || (remote == nil && pip == nil) || w < 2 || h < 2) return;

        NSRect content = [parent contentRectForFrameRect:[parent frame]];
        CGFloat baseX = content.origin.x;
        CGFloat baseY = content.origin.y;
        CGFloat contentH = content.size.height;

        if (remote != nil) {
            [remote setStyleMask:NSWindowStyleMaskBorderless];
            [remote setHasShadow:NO];
            if ([remote parentWindow] != parent) {
                [parent addChildWindow:remote ordered:NSWindowAbove];
            }
            [remote setFrame:NSMakeRect(baseX + x, baseY + contentH - y - h, w, h) display:YES];
            NSView *remoteContentView = [remote contentView];
            if (remoteContentView != nil) {
                [remoteContentView setAutoresizesSubviews:YES];
                for (NSView *subview in [remoteContentView subviews]) {
                    [subview setAutoresizingMask:NSViewWidthSizable | NSViewHeightSizable];
                    [subview setFrame:[remoteContentView bounds]];
                }
                [remoteContentView setNeedsDisplay:YES];
            }
            [remote orderFront:nil];
        }

        if (pip != nil) {
            CGFloat margin = 12.0;
            CGFloat pipW = w / 4.0;
            if (pipW > 280.0) pipW = 280.0;
            pipW = MIN(pipW, MIN(w - 2.0 * margin, (h - 2.0 * margin) * 16.0 / 9.0));
            CGFloat pipH = pipW * 9.0 / 16.0;
            [pip setStyleMask:NSWindowStyleMaskBorderless];
            [pip setHasShadow:YES];
            if ([pip parentWindow] != parent) {
                [parent addChildWindow:pip ordered:NSWindowAbove];
            }
            [pip setFrame:NSMakeRect(baseX + x + w - pipW - margin,
                                     baseY + contentH - (y + h) + margin, pipW, pipH) display:YES];
            NSView *pipContentView = [pip contentView];
            if (pipContentView != nil) {
                [pipContentView setAutoresizesSubviews:YES];
                pipContentView.wantsLayer = YES;
                for (NSView *subview in [pipContentView subviews]) {
                    [subview setAutoresizingMask:NSViewWidthSizable | NSViewHeightSizable];
                    [subview setFrame:[pipContentView bounds]];
                    subview.wantsLayer = YES;
                }
                [pipContentView setNeedsDisplay:YES];
            }
            [pip orderFront:nil];
        }

        if (remote != nil && pip != nil) {
            [pip orderWindow:NSWindowAbove relativeTo:[remote windowNumber]];
        }
        success = JNI_TRUE;
    });
    return success;
}

JNIEXPORT void JNICALL Java_lab_sip_client_WindowLayout_attachNative(JNIEnv *env, jclass cls,
    jlong parent, jlong remote, jlong pip, jint x, jint y, jint w, jint h) {
    Java_lab_sip_client_WindowLayout_attachScaledNative(env, cls, parent, remote, pip, x, y, w, h, 0, 0);
}

JNIEXPORT void JNICALL
Java_lab_sip_client_WindowLayout_detachNative(JNIEnv *env, jclass cls,
                                              jlong remoteHandle,
                                              jlong pipHandle) {
    (void)env;
    (void)cls;
    run_on_main(^{
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
    });
}

typedef struct {
    jlong parent;
    jlong x;
    jlong y;
    jlong w;
    jlong h;
    jlong visible;
} WindowDesc;

JNIEXPORT jlongArray JNICALL
Java_lab_sip_client_WindowLayout_describeNative(JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    __block WindowDesc desc = {0, 0, 0, 0, 0, 0};
    __block BOOL found = NO;
    run_on_main(^{
        NSWindow *window = as_window(handle);
        if (window == nil) return;
        NSWindow *parent = [window parentWindow];
        NSRect frame = [window frame];
        CGFloat x = frame.origin.x;
        CGFloat y = frame.origin.y;
        if (parent != nil) {
            NSRect parentFrame = [parent contentRectForFrameRect:[parent frame]];
            x = frame.origin.x - parentFrame.origin.x;
            y = (parentFrame.origin.y + parentFrame.size.height) - (frame.origin.y + frame.size.height);
        }
        desc.parent = (jlong)(intptr_t)(__bridge void *)parent;
        desc.x = (jlong)x;
        desc.y = (jlong)y;
        desc.w = (jlong)frame.size.width;
        desc.h = (jlong)frame.size.height;
        desc.visible = [window isVisible] ? 1 : 0;
        found = YES;
    });
    if (!found) {
        return (*env)->NewLongArray(env, 0);
    }
    jlong data[6] = {desc.parent, desc.x, desc.y, desc.w, desc.h, desc.visible};
    jlongArray result = (*env)->NewLongArray(env, 6);
    if (result != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, 6, data);
    }
    return result;
}
