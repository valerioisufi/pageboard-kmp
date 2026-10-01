#ifndef JNI_LOG_H_INCLUDED
#define JNI_LOG_H_INCLUDED

#include <jni.h>
#include <stdio.h>

// Info/debug logs are emitted on every glyph measurement and drawing call, which slows down
// formula rendering noticeably: they are compiled in only when TEX_JNI_VERBOSE is defined.
#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "tex"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#ifdef TEX_JNI_VERBOSE
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#endif
#else
#define LOGE(...) do { fprintf(stderr, "[tex ERROR] " __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#ifdef TEX_JNI_VERBOSE
#define LOGD(...) do { printf("[tex DEBUG] " __VA_ARGS__); printf("\n"); } while(0)
#define LOGI(...) do { printf("[tex INFO] " __VA_ARGS__); printf("\n"); } while(0)
#endif
#endif

#ifndef LOGD
#define LOGD(...) do { } while(0)
#endif
#ifndef LOGI
#define LOGI(...) do { } while(0)
#endif

#endif // JNI_LOG_H_INCLUDED
