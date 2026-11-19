// JNI entry points of io.github.valerioisufi.pageboard.lecture.transcription.WhisperNative (desktop
// JVM): the speech recognition of whisper.cpp (linked statically), for the lecture assistant.
#include "whisper.h"
#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstdio>
#include <string>
#include <vector>

namespace {

struct Context {
    whisper_context* whisper;
    std::atomic<bool> abort{false};
};

void quiet_log(enum ggml_log_level level, const char* text, void*) {
    // Only the errors: whisper.cpp reports every step of the loading otherwise
    if (level == GGML_LOG_LEVEL_ERROR) std::fputs(text, stderr);
}

std::string string_of(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

void append_json_string(std::string& out, const std::string& text) {
    out += '"';
    for (unsigned char c : text) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char escaped[8];
                    std::snprintf(escaped, sizeof escaped, "\\u%04x", c);
                    out += escaped;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    out += '"';
}

bool starts_with_continuation_byte(const std::string& text) {
    return !text.empty() && (static_cast<unsigned char>(text[0]) & 0xC0) == 0x80;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_valerioisufi_pageboard_lecture_transcription_WhisperNative_nInit(
    JNIEnv* env, jclass, jstring model_path, jboolean use_gpu) {
    whisper_log_set(quiet_log, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = use_gpu == JNI_TRUE;
    std::string path = string_of(env, model_path);
    whisper_context* whisper = whisper_init_from_file_with_params(path.c_str(), params);
    if (!whisper) return 0;
    return reinterpret_cast<jlong>(new Context{whisper});
}

// The segments of [samples] (mono, 16 kHz, -1..1) as JSON, {"segments":[{"start":s,"end":s,"text":...}]}
// (UTF-8 bytes), or null when the transcription failed or was aborted.
JNIEXPORT jbyteArray JNICALL Java_io_github_valerioisufi_pageboard_lecture_transcription_WhisperNative_nTranscribe(
    JNIEnv* env, jclass, jlong handle, jfloatArray samples, jstring language, jstring prompt, jint threads) {
    auto* context = reinterpret_cast<Context*>(handle);
    if (!context) return nullptr;
    context->abort = false;

    std::vector<float> pcm(env->GetArrayLength(samples));
    env->GetFloatArrayRegion(samples, 0, static_cast<jsize>(pcm.size()), pcm.data());
    std::string lang = string_of(env, language);
    std::string initial_prompt = string_of(env, prompt);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang.empty() ? "auto" : lang.c_str();
    params.n_threads = threads > 0 ? threads : 4;
    // Every chunk on its own: its prompt is the course and the slides
    params.no_context = true;
    params.initial_prompt = initial_prompt.empty() ? nullptr : initial_prompt.c_str();
    params.abort_callback = [](void* data) { return static_cast<Context*>(data)->abort.load(); };
    params.abort_callback_user_data = context;

    if (whisper_full(context->whisper, params, pcm.data(), static_cast<int>(pcm.size())) != 0) return nullptr;
    if (context->abort) return nullptr;

    // A segment may begin inside a character of the previous one: joined, the text stays valid UTF-8
    struct Segment {
        int64_t t0, t1;
        std::string text;
    };
    std::vector<Segment> segments;
    int count = whisper_full_n_segments(context->whisper);
    for (int i = 0; i < count; i++) {
        std::string text = whisper_full_get_segment_text(context->whisper, i);
        int64_t t0 = whisper_full_get_segment_t0(context->whisper, i);
        int64_t t1 = whisper_full_get_segment_t1(context->whisper, i);
        if (!segments.empty() && starts_with_continuation_byte(text)) {
            segments.back().text += text;
            segments.back().t1 = t1;
        } else {
            segments.push_back({t0, t1, text});
        }
    }

    std::string json = "{\"segments\":[";
    for (size_t i = 0; i < segments.size(); i++) {
        if (i > 0) json += ',';
        // Times of whisper.cpp in hundredths of a second
        json += "{\"start\":" + std::to_string(segments[i].t0 / 100.0) + ",\"end\":" + std::to_string(segments[i].t1 / 100.0) + ",\"text\":";
        append_json_string(json, segments[i].text);
        json += '}';
    }
    json += "]}";

    jbyteArray result = env->NewByteArray(static_cast<jsize>(json.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(json.size()), reinterpret_cast<const jbyte*>(json.data()));
    return result;
}

// Stops the transcription in progress (from another thread): it returns null.
JNIEXPORT void JNICALL Java_io_github_valerioisufi_pageboard_lecture_transcription_WhisperNative_nAbort(
    JNIEnv*, jclass, jlong handle) {
    auto* context = reinterpret_cast<Context*>(handle);
    if (context) context->abort = true;
}

JNIEXPORT void JNICALL Java_io_github_valerioisufi_pageboard_lecture_transcription_WhisperNative_nFree(
    JNIEnv*, jclass, jlong handle) {
    auto* context = reinterpret_cast<Context*>(handle);
    if (!context) return;
    whisper_free(context->whisper);
    delete context;
}

}  // extern "C"
