#include "jni_def.h"
#include "jni_log.h"

JavaVM* gJVM;

/** io/github/valerioisufi/pageboard/latex/microtex/Rect */
jfieldID gFieldRectX, gFieldRectY, gFieldRectW, gFieldRectH;

/** io/github/valerioisufi/pageboard/latex/microtex/TextLayout */
jclass gClassTextLayout;
jmethodID gMethodGetBounds;

/** io/github/valerioisufi/pageboard/latex/microtex/Font */
jclass gClassFont;
jmethodID gMethodDeriveFont;
jmethodID gMethodCreateFontFromName;
jmethodID gMethodCreateFontFromFile;

/** io/github/valerioisufi/pageboard/latex/microtex/ActionRecorder */
jmethodID gMethodRecord;

JNIEnv* getJNIEnv() {
    JNIEnv* env = nullptr;
    if (gJVM == nullptr) return nullptr;
    jint res = gJVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
#ifdef __ANDROID__
        if (gJVM->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
#else
        if (gJVM->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
            return nullptr;
        }
#endif
    } else if (res != JNI_OK) {
        return nullptr;
    }
    return env;
}

static void load(JNIEnv* env) {
    // io/github/valerioisufi/pageboard/latex/microtex/Rect
    jclass rect = env->FindClass("io/github/valerioisufi/pageboard/latex/microtex/Rect");
    gFieldRectX = env->GetFieldID(rect, "x", "F");
    gFieldRectY = env->GetFieldID(rect, "y", "F");
    gFieldRectW = env->GetFieldID(rect, "w", "F");
    gFieldRectH = env->GetFieldID(rect, "h", "F");
    // io/github/valerioisufi/pageboard/latex/microtex/TextLayout
    jclass layout = env->FindClass("io/github/valerioisufi/pageboard/latex/microtex/TextLayout");
    gMethodGetBounds = env->GetStaticMethodID(
        layout,
        "getBounds",
        "(Ljava/lang/String;Lio/github/valerioisufi/pageboard/latex/microtex/Font;)Lio/github/valerioisufi/pageboard/latex/microtex/Rect;");
    gClassTextLayout = (jclass)env->NewGlobalRef(layout);
    // io/github/valerioisufi/pageboard/latex/microtex/Font
    jclass font = env->FindClass("io/github/valerioisufi/pageboard/latex/microtex/Font");
    gClassFont = (jclass)env->NewGlobalRef(font);
    gMethodDeriveFont = env->GetMethodID(
        gClassFont,
        "deriveFont",
        "(I)Lio/github/valerioisufi/pageboard/latex/microtex/Font;");
    gMethodCreateFontFromName = env->GetStaticMethodID(
        gClassFont,
        "create",
        "(Ljava/lang/String;IF)Lio/github/valerioisufi/pageboard/latex/microtex/Font;");
    gMethodCreateFontFromFile = env->GetStaticMethodID(
        gClassFont,
        "create",
        "(Ljava/lang/String;F)Lio/github/valerioisufi/pageboard/latex/microtex/Font;");
    // io/github/valerioisufi/pageboard/latex/microtex/ActionRecorder
    jclass recorder = env->FindClass("io/github/valerioisufi/pageboard/latex/microtex/ActionRecorder");
    gMethodRecord = env->GetMethodID(
        recorder,
        "record",
        "(ILjava/lang/Object;[F)V");
}

extern int registerLaTeX(JNIEnv* env);
extern int registerTeXRender(JNIEnv* env);

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJVM = vm;

    LOGI("Load jni, initialize java methods...");

    JNIEnv* env = 0;
    vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);

    registerLaTeX(env);
    registerTeXRender(env);

    load(env);

    return JNI_VERSION_1_6;
}
