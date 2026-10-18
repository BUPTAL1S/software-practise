#import <Cocoa/Cocoa.h>
#import <QuartzCore/QuartzCore.h>
#import <objc/runtime.h>
#include <jni.h>

static char kMirrorObserverKey;

@interface MirrorHelper : NSObject
@property (nonatomic, weak) NSView *view;
- (void)frameDidChange:(NSNotification *)note;
@end

@implementation MirrorHelper
- (void)frameDidChange:(NSNotification *)note {
    NSView *v = self.view;
    if (!v) return;
    CGFloat w = v.bounds.size.width;
    if (w <= 0.0) {
        NSWindow *win = [v window];
        if (win) {
            w = [win contentView].bounds.size.width;
            if (w <= 0.0) {
                w = [win frame].size.width;
            }
        }
    }
    if (w <= 0.0) {
        w = 480.0;
    }
    v.wantsLayer = YES;
    CALayer *layer = v.layer;
    if (layer) {
        layer.affineTransform = CGAffineTransformMake(-1.0, 0.0, 0.0, 1.0, w, 0.0);
    }
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}
@end

static BOOL isViewInHierarchy(NSView *root, void *target) {
    if (!root) return NO;
    if ((__bridge void *)root == target) return YES;
    for (NSView *sub in [root subviews]) {
        if (isViewInHierarchy(sub, target)) return YES;
    }
    return NO;
}

static id validatePointerSafely(void *ptr) {
    if (!ptr) return nil;
    NSArray<NSWindow *> *wins = [NSApp windows];
    for (NSWindow *w in wins) {
        if ((__bridge void *)w == ptr) {
            return w;
        }
        if (isViewInHierarchy([w contentView], ptr)) {
            return (__bridge id)ptr;
        }
    }
    return nil;
}

static NSView* findTargetView(id obj) {
    if (!obj) return nil;
    if ([obj isKindOfClass:[NSWindow class]]) {
        NSWindow *win = (NSWindow *)obj;
        NSView *content = [win contentView];
        if (!content) return nil;
        for (NSView *sub in [content subviews]) {
            if ([sub isKindOfClass:NSClassFromString(@"MTKView")]) {
                return sub;
            }
        }
        if ([[content subviews] count] > 0) {
            return [content subviews][0];
        }
        return content;
    } else if ([obj isKindOfClass:[NSView class]]) {
        NSView *view = (NSView *)obj;
        for (NSView *sub in [view subviews]) {
            if ([sub isKindOfClass:NSClassFromString(@"MTKView")]) {
                return sub;
            }
        }
        return view;
    }
    return nil;
}

static void doApplyMirror(void *ptr, const char *titleUtf8) {
    if (!ptr) return;
    id obj = validatePointerSafely(ptr);
    if (!obj) return;
    NSView *targetView = findTargetView(obj);
    if (targetView) {
        // If targetView is a child of contentView, ensure contentView itself is not mirrored
        if ([obj isKindOfClass:[NSWindow class]]) {
            NSView *content = [(NSWindow *)obj contentView];
            if (content && content != targetView) {
                objc_setAssociatedObject(content, &kMirrorObserverKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
                if (content.layer) {
                    content.layer.affineTransform = CGAffineTransformIdentity;
                }
            }
        }
        
        MirrorHelper *helper = objc_getAssociatedObject(targetView, &kMirrorObserverKey);
        if (!helper) {
            helper = [[MirrorHelper alloc] init];
            helper.view = targetView;
            [targetView setPostsFrameChangedNotifications:YES];
            [[NSNotificationCenter defaultCenter] addObserver:helper
                                                     selector:@selector(frameDidChange:)
                                                         name:NSViewFrameDidChangeNotification
                                                       object:targetView];
            objc_setAssociatedObject(targetView, &kMirrorObserverKey, helper, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        }
        [helper frameDidChange:nil];
        
        NSWindow *win = [targetView window];
        if (!win && [obj isKindOfClass:[NSWindow class]]) {
            win = (NSWindow *)obj;
        }
        if (win && titleUtf8 && strlen(titleUtf8) > 0) {
            [win setTitle:[NSString stringWithUTF8String:titleUtf8]];
        }
    }
}

static void doRestoreNormal(void *ptr, const char *titleUtf8) {
    if (!ptr) return;
    id obj = validatePointerSafely(ptr);
    if (!obj) return;
    NSView *targetView = findTargetView(obj);
    if (targetView) {
        objc_setAssociatedObject(targetView, &kMirrorObserverKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        CALayer *layer = targetView.layer;
        if (layer) {
            layer.affineTransform = CGAffineTransformIdentity;
        }
        NSWindow *win = [targetView window];
        if (!win && [obj isKindOfClass:[NSWindow class]]) {
            win = (NSWindow *)obj;
        }
        if (win && titleUtf8 && strlen(titleUtf8) > 0) {
            [win setTitle:[NSString stringWithUTF8String:titleUtf8]];
        }
    }
    if ([obj isKindOfClass:[NSWindow class]]) {
        NSView *content = [(NSWindow *)obj contentView];
        if (content && content != targetView) {
            objc_setAssociatedObject(content, &kMirrorObserverKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            if (content.layer) {
                content.layer.affineTransform = CGAffineTransformIdentity;
            }
        }
    }
}

JNIEXPORT void JNICALL Java_lab_sip_client_NativeMirror_applyMirror
  (JNIEnv *env, jclass cls, jlong hwnd, jstring jtitle)
{
    if (hwnd == 0) return;
    void *ptr = (void *)(intptr_t)hwnd;
    
    char *titleBuf = NULL;
    if (jtitle != NULL) {
        const char *chars = (*env)->GetStringUTFChars(env, jtitle, NULL);
        if (chars) {
            titleBuf = strdup(chars);
            (*env)->ReleaseStringUTFChars(env, jtitle, chars);
        }
    }
    
    dispatch_async(dispatch_get_main_queue(), ^{
        doApplyMirror(ptr, titleBuf);
        if (titleBuf) free(titleBuf);
    });

    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(100 * NSEC_PER_MSEC)), dispatch_get_main_queue(), ^{
        doApplyMirror(ptr, NULL);
    });

    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(300 * NSEC_PER_MSEC)), dispatch_get_main_queue(), ^{
        doApplyMirror(ptr, NULL);
    });
}

JNIEXPORT void JNICALL Java_lab_sip_client_NativeMirror_restoreNormalNative
  (JNIEnv *env, jclass cls, jlong hwnd, jstring jtitle)
{
    if (hwnd == 0) return;
    void *ptr = (void *)(intptr_t)hwnd;
    
    char *titleBuf = NULL;
    if (jtitle != NULL) {
        const char *chars = (*env)->GetStringUTFChars(env, jtitle, NULL);
        if (chars) {
            titleBuf = strdup(chars);
            (*env)->ReleaseStringUTFChars(env, jtitle, chars);
        }
    }
    
    dispatch_async(dispatch_get_main_queue(), ^{
        doRestoreNormal(ptr, titleBuf);
        if (titleBuf) free(titleBuf);
    });
}

JNIEXPORT jboolean JNICALL Java_lab_sip_client_NativeMirror_isMirroredNative
  (JNIEnv *env, jclass cls, jlong hwnd)
{
    if (hwnd == 0) return JNI_FALSE;
    void *ptr = (void *)(intptr_t)hwnd;
    __block jboolean mirrored = JNI_FALSE;
    void (^checkBlock)(void) = ^{
        id obj = validatePointerSafely(ptr);
        if (!obj) return;
        NSView *targetView = findTargetView(obj);
        if (targetView && targetView.layer) {
            CGAffineTransform t = targetView.layer.affineTransform;
            if (t.a < -0.5) {
                mirrored = JNI_TRUE;
            }
        }
        if (!mirrored && [obj isKindOfClass:[NSWindow class]]) {
            NSView *content = [(NSWindow *)obj contentView];
            if (content && content.layer) {
                CGAffineTransform t = content.layer.affineTransform;
                if (t.a < -0.5) {
                    mirrored = JNI_TRUE;
                }
            }
        }
    };
    if ([NSThread isMainThread]) {
        checkBlock();
    } else {
        dispatch_sync(dispatch_get_main_queue(), checkBlock);
    }
    return mirrored;
}
