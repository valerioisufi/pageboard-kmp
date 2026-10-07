#include "audio_bridge.h"

#include <stdlib.h>
#include <string.h>

#include <opus.h>
#include <voAAC.h>
#include <cmnMemory.h>

// ---------------------------------------------------------------- Opus

void *dv_opus_encoder_create(int32_t sample_rate, int32_t bitrate) {
    int error = 0;
    OpusEncoder *encoder = opus_encoder_create(sample_rate, 1, OPUS_APPLICATION_VOIP, &error);
    if (error != OPUS_OK || encoder == NULL) return NULL;
    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(10));
    return encoder;
}

int32_t dv_opus_encode(void *encoder, const int16_t *pcm, int32_t frame_size, uint8_t *out, int32_t max_out) {
    return opus_encode((OpusEncoder *) encoder, pcm, frame_size, out, max_out);
}

int32_t dv_opus_lookahead(void *encoder) {
    opus_int32 lookahead = 0;
    opus_encoder_ctl((OpusEncoder *) encoder, OPUS_GET_LOOKAHEAD(&lookahead));
    return lookahead;
}

void dv_opus_encoder_destroy(void *encoder) {
    if (encoder != NULL) opus_encoder_destroy((OpusEncoder *) encoder);
}

void *dv_opus_decoder_create(int32_t sample_rate) {
    int error = 0;
    OpusDecoder *decoder = opus_decoder_create(sample_rate, 1, &error);
    return error == OPUS_OK ? decoder : NULL;
}

int32_t dv_opus_decode(void *decoder, const uint8_t *packet, int32_t length, int16_t *pcm, int32_t max_samples) {
    return opus_decode((OpusDecoder *) decoder, length > 0 ? packet : NULL, length, pcm, max_samples, 0);
}

void dv_opus_decoder_destroy(void *decoder) {
    if (decoder != NULL) opus_decoder_destroy((OpusDecoder *) decoder);
}

// ---------------------------------------------------------------- AAC

typedef struct {
    VO_AUDIO_CODECAPI api;
    VO_HANDLE handle;
    VO_MEM_OPERATOR memory;
} AacEncoder;

void *dv_aac_encoder_create(int32_t sample_rate, int32_t bitrate) {
    AacEncoder *encoder = (AacEncoder *) calloc(1, sizeof(AacEncoder));
    if (encoder == NULL) return NULL;
    voGetAACEncAPI(&encoder->api);
    encoder->memory.Alloc = cmnMemAlloc;
    encoder->memory.Copy = cmnMemCopy;
    encoder->memory.Free = cmnMemFree;
    encoder->memory.Set = cmnMemSet;
    encoder->memory.Check = cmnMemCheck;
    VO_CODEC_INIT_USERDATA user = { 0 };
    user.memflag = VO_IMF_USERMEMOPERATOR;
    user.memData = &encoder->memory;
    if (encoder->api.Init(&encoder->handle, VO_AUDIO_CodingAAC, &user) != VO_ERR_NONE) {
        free(encoder);
        return NULL;
    }
    AACENC_PARAM params = { 0 };
    params.sampleRate = sample_rate;
    params.bitRate = bitrate;
    params.nChannels = 1;
    params.adtsUsed = 0;
    if (encoder->api.SetParam(encoder->handle, VO_PID_AAC_ENCPARAM, &params) != VO_ERR_NONE) {
        encoder->api.Uninit(encoder->handle);
        free(encoder);
        return NULL;
    }
    return encoder;
}

int32_t dv_aac_encode(void *opaque, const int16_t *pcm, uint8_t *out, int32_t max_out) {
    AacEncoder *encoder = (AacEncoder *) opaque;
    VO_CODECBUFFER input = { 0 }, output = { 0 };
    VO_AUDIO_OUTPUTINFO info = { 0 };
    input.Buffer = (VO_PBYTE) pcm;
    input.Length = 1024 * 2;
    if (encoder->api.SetInputData(encoder->handle, &input) != VO_ERR_NONE) return -1;
    output.Buffer = out;
    output.Length = max_out;
    if (encoder->api.GetOutputData(encoder->handle, &output, &info) != VO_ERR_NONE) return -1;
    return (int32_t) output.Length;
}

void dv_aac_encoder_destroy(void *opaque) {
    AacEncoder *encoder = (AacEncoder *) opaque;
    if (encoder == NULL) return;
    encoder->api.Uninit(encoder->handle);
    free(encoder);
}
