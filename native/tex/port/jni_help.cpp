#include "jni_help.h"
#include "jni_log.h"

extern "C" int jniRegisterNativeMethods(
    JNIEnv* env, const char* className,
    const JNINativeMethod* gMethods, int numMethods) {
    LOGI("Registering %s's %d native methods...", className, numMethods);

    jclass clazz = env->FindClass(className);
    if (!clazz) {
        LOGE("Native registration unable to find class '%s'", className);
        return -1;
    }

    int result = env->RegisterNatives(clazz, gMethods, numMethods);
    env->DeleteLocalRef(clazz);
    if (result < 0) {
        LOGE("RegisterNatives failed for '%s'", className);
        return -1;
    }

    return 0;
}
