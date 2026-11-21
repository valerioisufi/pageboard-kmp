// The JNI functions of PencilKitBridge (pageboard-handwriting-pencilkit, macOS), over the plain C API
// of pencilkit_bridge.h; linked with pencilkit_bridge.swift into libpencilkit_bridge.dylib.
#include <jni.h>
#include <stdlib.h>

#include "pencilkit_bridge.h"

#define PENCILKIT_BRIDGE(name) Java_io_github_valerioisufi_pageboard_handwriting_pencilkit_PencilKitBridge_##name

JNIEXPORT jint JNICALL PENCILKIT_BRIDGE(version)(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return pk_bridge_version();
}

JNIEXPORT jstring JNICALL PENCILKIT_BRIDGE(languages)(JNIEnv *env, jclass type) {
    (void)type;
    char *languages = pk_bridge_languages();
    if (languages == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, languages);
    pk_bridge_free(languages);
    return result;
}

JNIEXPORT jboolean JNICALL PENCILKIT_BRIDGE(available)(JNIEnv *env, jclass type, jint timeoutMs) {
    (void)env;
    (void)type;
    return pk_bridge_available(timeoutMs) == 1 ? JNI_TRUE : JNI_FALSE;
}

// A string of the bridge as its UTF-8 bytes (NewStringUTF takes modified UTF-8, wrong beyond the BMP)
static jbyteArray utf8Bytes(JNIEnv *env, const char *string) {
    jsize length = 0;
    while (string[length] != 0) length++;
    jbyteArray bytes = (*env)->NewByteArray(env, length);
    if (bytes != NULL) (*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *)string);
    return bytes;
}

// The reading of the strokes, {text, search} as UTF-8 bytes, or null when nothing was read.
JNIEXPORT jobjectArray JNICALL PENCILKIT_BRIDGE(recognize)(JNIEnv *env, jclass type, jstring language, jfloatArray xy,
                                                          jdoubleArray times, jfloatArray pressures, jintArray sizes,
                                                          jint timeoutMs) {
    (void)type;
    jsize strokes = (*env)->GetArrayLength(env, sizes);
    jsize samples = (*env)->GetArrayLength(env, times);
    if (strokes == 0 || (*env)->GetArrayLength(env, xy) != samples * 2 || (*env)->GetArrayLength(env, pressures) != samples) {
        return NULL;
    }
    const char *tag = (*env)->GetStringUTFChars(env, language, NULL);
    if (tag == NULL) return NULL;
    jfloat *xyValues = (*env)->GetFloatArrayElements(env, xy, NULL);
    jdouble *timeValues = (*env)->GetDoubleArrayElements(env, times, NULL);
    jfloat *pressureValues = (*env)->GetFloatArrayElements(env, pressures, NULL);
    jint *sizeValues = (*env)->GetIntArrayElements(env, sizes, NULL);

    // The sizes must add up to the samples given
    jlong total = 0;
    for (jsize i = 0; i < strokes; i++) total += sizeValues[i] > 0 ? sizeValues[i] : 0;

    char *text = NULL;
    char *search = NULL;
    int32_t read = 0;
    if (total == samples) {
        read = pk_bridge_recognize(tag, xyValues, timeValues, pressureValues, (const int32_t *)sizeValues, strokes, timeoutMs,
                                   &text, &search);
    }

    (*env)->ReleaseIntArrayElements(env, sizes, sizeValues, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, pressures, pressureValues, JNI_ABORT);
    (*env)->ReleaseDoubleArrayElements(env, times, timeValues, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, xy, xyValues, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language, tag);

    jobjectArray result = NULL;
    if (read == 1 && text != NULL && search != NULL) {
        jclass bytes = (*env)->FindClass(env, "[B");
        result = bytes == NULL ? NULL : (*env)->NewObjectArray(env, 2, bytes, NULL);
        if (result != NULL) {
            (*env)->SetObjectArrayElement(env, result, 0, utf8Bytes(env, text));
            (*env)->SetObjectArrayElement(env, result, 1, utf8Bytes(env, search));
        }
    }
    pk_bridge_free(text);
    pk_bridge_free(search);
    return result;
}
