// Trackpad gestures of macOS that AWT does not deliver to Java: the phases of the scroll (fingers
// put down or lifted, inertia of the system), which the mouse wheel events do not carry, and the
// pinch. An event monitor of the application reports them to
// io.github.valerioisufi.drawview.canvas.MacTrackpad (desktop JVM, macOS only).

#import <AppKit/AppKit.h>

#include <jni.h>

static JavaVM *jvm;
static jclass trackpadClass;
static jmethodID onScrollPhase;
static jmethodID onMagnify;
static id monitor;

static JNIEnv *attachedEnv(void) {
    JNIEnv *env = NULL;
    jint status = (*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*jvm)->AttachCurrentThreadAsDaemon(jvm, (void **)&env, NULL) != JNI_OK) return NULL;
    } else if (status != JNI_OK) {
        return NULL;
    }
    return env;
}

static void clearException(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static void reportScroll(NSEvent *event) {
    NSEventPhase fingers = NSEventPhaseMayBegin | NSEventPhaseBegan | NSEventPhaseEnded | NSEventPhaseCancelled;
    NSEventPhase inertia = NSEventPhaseBegan | NSEventPhaseEnded | NSEventPhaseCancelled;
    NSEventPhase phase = event.phase;
    NSEventPhase momentumPhase = event.momentumPhase;
    // Only the changes of phase, and the scroll of a mouse wheel (no phase at all)
    if ((phase & fingers) == 0 && (momentumPhase & inertia) == 0 &&
        (phase != NSEventPhaseNone || momentumPhase != NSEventPhaseNone)) {
        return;
    }
    JNIEnv *env = attachedEnv();
    if (env == NULL) return;
    (*env)->CallStaticVoidMethod(env, trackpadClass, onScrollPhase, (jint)phase, (jint)momentumPhase);
    clearException(env);
}

static void reportMagnify(NSEvent *event) {
    JNIEnv *env = attachedEnv();
    if (env == NULL) return;
    (*env)->CallStaticVoidMethod(env, trackpadClass, onMagnify, (jint)event.phase, (jdouble)event.magnification);
    clearException(env);
}

JNIEXPORT void JNICALL Java_io_github_valerioisufi_drawview_canvas_MacTrackpad_nativeInstall(
    JNIEnv *env, jclass clazz
) {
    if (trackpadClass != NULL) return;
    if ((*env)->GetJavaVM(env, &jvm) != JNI_OK) return;
    onScrollPhase = (*env)->GetStaticMethodID(env, clazz, "onScrollPhase", "(II)V");
    if (onScrollPhase == NULL) return;
    onMagnify = (*env)->GetStaticMethodID(env, clazz, "onMagnify", "(ID)V");
    if (onMagnify == NULL) return;
    trackpadClass = (*env)->NewGlobalRef(env, clazz);

    dispatch_async(dispatch_get_main_queue(), ^{
        NSEventMask mask = NSEventMaskScrollWheel | NSEventMaskMagnify;
        monitor = [NSEvent addLocalMonitorForEventsMatchingMask:mask handler:^NSEvent *(NSEvent *event) {
            if (event.type == NSEventTypeMagnify) {
                reportMagnify(event);
            } else {
                reportScroll(event);
            }
            return event;
        }];
    });
}
