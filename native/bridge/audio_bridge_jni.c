// JNI entry points of the audio codecs (io.github.valerioisufi.pageboard.lecture.audio.AudioNative).
#include <jni.h>

#include "audio_bridge.h"

#define JNI_METHOD(name) Java_io_github_valerioisufi_pageboard_lecture_audio_AudioNative_##name

JNIEXPORT jlong JNICALL JNI_METHOD(nOpusEncoderCreate)(JNIEnv *env, jclass clazz, jint rate, jint bitrate) {
    return (jlong) (intptr_t) dv_opus_encoder_create(rate, bitrate);
}

JNIEXPORT jint JNICALL JNI_METHOD(nOpusEncode)(JNIEnv *env, jclass clazz, jlong encoder, jshortArray pcm, jint frameSize, jbyteArray out) {
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *bytes = (*env)->GetByteArrayElements(env, out, NULL);
    jint written = dv_opus_encode((void *) (intptr_t) encoder, samples, frameSize, (uint8_t *) bytes, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, bytes, 0);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    return written;
}

JNIEXPORT jint JNICALL JNI_METHOD(nOpusLookahead)(JNIEnv *env, jclass clazz, jlong encoder) {
    return dv_opus_lookahead((void *) (intptr_t) encoder);
}

JNIEXPORT void JNICALL JNI_METHOD(nOpusEncoderDestroy)(JNIEnv *env, jclass clazz, jlong encoder) {
    dv_opus_encoder_destroy((void *) (intptr_t) encoder);
}

JNIEXPORT jlong JNICALL JNI_METHOD(nOpusDecoderCreate)(JNIEnv *env, jclass clazz, jint rate) {
    return (jlong) (intptr_t) dv_opus_decoder_create(rate);
}

JNIEXPORT jint JNICALL JNI_METHOD(nOpusDecode)(JNIEnv *env, jclass clazz, jlong decoder, jbyteArray packet, jint length, jshortArray pcm) {
    jbyte *bytes = packet != NULL ? (*env)->GetByteArrayElements(env, packet, NULL) : NULL;
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    jint decoded = dv_opus_decode((void *) (intptr_t) decoder, (const uint8_t *) bytes, length, samples, (*env)->GetArrayLength(env, pcm));
    (*env)->ReleaseShortArrayElements(env, pcm, samples, 0);
    if (bytes != NULL) (*env)->ReleaseByteArrayElements(env, packet, bytes, JNI_ABORT);
    return decoded;
}

JNIEXPORT void JNICALL JNI_METHOD(nOpusDecoderDestroy)(JNIEnv *env, jclass clazz, jlong decoder) {
    dv_opus_decoder_destroy((void *) (intptr_t) decoder);
}

JNIEXPORT jlong JNICALL JNI_METHOD(nAacEncoderCreate)(JNIEnv *env, jclass clazz, jint rate, jint bitrate) {
    return (jlong) (intptr_t) dv_aac_encoder_create(rate, bitrate);
}

JNIEXPORT jint JNICALL JNI_METHOD(nAacEncode)(JNIEnv *env, jclass clazz, jlong encoder, jshortArray pcm, jbyteArray out) {
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *bytes = (*env)->GetByteArrayElements(env, out, NULL);
    jint written = dv_aac_encode((void *) (intptr_t) encoder, samples, (uint8_t *) bytes, (*env)->GetArrayLength(env, out));
    (*env)->ReleaseByteArrayElements(env, out, bytes, 0);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    return written;
}

JNIEXPORT void JNICALL JNI_METHOD(nAacEncoderDestroy)(JNIEnv *env, jclass clazz, jlong encoder) {
    dv_aac_encoder_destroy((void *) (intptr_t) encoder);
}
