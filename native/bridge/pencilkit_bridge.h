// The handwriting recognizer of PencilKit (PKStrokeRecognizer, iOS, iPadOS and macOS 27) as a plain C
// API, implemented in Swift by pencilkit_bridge.swift: read by Kotlin/Native through cinterop on iOS
// and by pencilkit_bridge_jni.c on macOS. Before the systems 27, or when built with an SDK without
// the recognizer, it reads no language.
#ifndef PAGEBOARD_PENCILKIT_BRIDGE_H
#define PAGEBOARD_PENCILKIT_BRIDGE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// The version of the recognition of the system (PKStrokeRecognizer.recognitionVersion), 0 without a
// recognizer.
int32_t pk_bridge_version(void);

// The languages the recognizer reads, as BCP 47 tags split by commas (empty without a recognizer).
// Freed with pk_bridge_free.
char *pk_bridge_languages(void);

// Whether the recognizer reads now (1) or not (0), waiting [timeout_ms] at most: it reads a word of
// its own. The system reads only for an application in the foreground, launched as an application
// (not, on macOS, a process started from a terminal).
int32_t pk_bridge_available(int32_t timeout_ms);

// Reads the strokes of a block in [language] (a BCP 47 tag), waiting [timeout_ms] at most.
//
// [xy] holds x and y of every sample (points), [times] the time of every sample (milliseconds, one
// clock for the whole block), [pressures] its pressure (0 to 1), [sizes] the number of samples of
// each of the [stroke_count] strokes. On a reading it returns 1 and gives the most likely text in
// [out_text] and every candidate, for a search, in [out_search], both freed with pk_bridge_free;
// otherwise 0 (no recognizer, nothing read, the time out).
int32_t pk_bridge_recognize(const char *language, const float *xy, const double *times, const float *pressures,
                            const int32_t *sizes, int32_t stroke_count, int32_t timeout_ms, char **out_text,
                            char **out_search);

// Frees a string given by the bridge.
void pk_bridge_free(char *string);

#ifdef __cplusplus
}
#endif

#endif
