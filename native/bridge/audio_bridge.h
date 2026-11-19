// The codecs of the lecture recordings (plain C API, for the JNI bridge and Kotlin/Native):
// Opus (libopus) to record and play them, AAC-LC (vo-aacenc) to export them.
#ifndef PAGEBOARD_AUDIO_BRIDGE_H
#define PAGEBOARD_AUDIO_BRIDGE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Opus, mono 16-bit PCM at sample_rate (8, 12, 16, 24 or 48 kHz), speech.
void *dv_opus_encoder_create(int32_t sample_rate, int32_t bitrate);
// Encodes frame_size samples (a valid Opus frame: 20 ms) into out: its bytes, negative on error.
int32_t dv_opus_encode(void *encoder, const int16_t *pcm, int32_t frame_size, uint8_t *out, int32_t max_out);
// Samples of the encoder's look-ahead (at sample_rate): the decoder skips them.
int32_t dv_opus_lookahead(void *encoder);
void dv_opus_encoder_destroy(void *encoder);

void *dv_opus_decoder_create(int32_t sample_rate);
// Decodes packet (NULL or length 0: a lost packet) into pcm: the samples, negative on error.
int32_t dv_opus_decode(void *decoder, const uint8_t *packet, int32_t length, int16_t *pcm, int32_t max_samples);
void dv_opus_decoder_destroy(void *decoder);

// AAC-LC, mono 16-bit PCM, raw frames (no ADTS header) of 1024 samples.
void *dv_aac_encoder_create(int32_t sample_rate, int32_t bitrate);
// Encodes 1024 samples into out: its bytes, negative on error.
int32_t dv_aac_encode(void *encoder, const int16_t *pcm, uint8_t *out, int32_t max_out);
void dv_aac_encoder_destroy(void *encoder);

#ifdef __cplusplus
}
#endif

#endif
