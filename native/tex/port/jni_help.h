#ifndef JNI_HELP_INCLUDED
#define JNI_HELP_INCLUDED

#include <jni.h>

#ifndef NELEM
#define NELEM(x) ((int)(sizeof(x) / sizeof((x)[0])))
#endif

#ifdef __cplusplus
extern "C" {
#endif

int jniRegisterNativeMethods(
    JNIEnv* env, const char* className,
    const JNINativeMethod* gMethods, int numMethods);

#ifdef __cplusplus
}
#endif

#endif  // JNI_HELP_INCLUDED
