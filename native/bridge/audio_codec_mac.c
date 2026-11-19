// JNI entry points of io.github.valerioisufi.pageboard.lecture.audio.AudioCodecNative (desktop JVM,
// macOS): the audio files of other applications (m4a, mp3, wav, aiff, caf, flac...) decoded into
// the PCM of the lectures, with Audio Toolbox.
#include <AudioToolbox/AudioToolbox.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

// 16-bit mono PCM at 16 kHz, little endian: the audio of the lectures
static AudioStreamBasicDescription lecture_format(void) {
    AudioStreamBasicDescription format;
    memset(&format, 0, sizeof(format));
    format.mSampleRate = 16000;
    format.mFormatID = kAudioFormatLinearPCM;
    format.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked;
    format.mBitsPerChannel = 16;
    format.mChannelsPerFrame = 1;
    format.mBytesPerFrame = 2;
    format.mFramesPerPacket = 1;
    format.mBytesPerPacket = 2;
    return format;
}

static CFURLRef url_of(JNIEnv* env, jstring path) {
    const char* chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (!chars) return NULL;
    CFURLRef url = CFURLCreateFromFileSystemRepresentation(NULL, (const UInt8*)chars, (CFIndex)strlen(chars), false);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    return url;
}

// Opens [path] for reading, delivering the audio of the lectures (converted by Audio Toolbox)
static ExtAudioFileRef open_input(JNIEnv* env, jstring path) {
    CFURLRef url = url_of(env, path);
    if (!url) return NULL;
    ExtAudioFileRef file = NULL;
    OSStatus status = ExtAudioFileOpenURL(url, &file);
    CFRelease(url);
    if (status != noErr) return NULL;
    AudioStreamBasicDescription client = lecture_format();
    if (ExtAudioFileSetProperty(file, kExtAudioFileProperty_ClientDataFormat, sizeof(client), &client) != noErr) {
        ExtAudioFileDispose(file);
        return NULL;
    }
    return file;
}

enum { BLOCK_FRAMES = 16384 };

// Reads a block of [file] into [buffer]: the frames read (0 at the end), -1 on error
static long read_block(ExtAudioFileRef file, SInt16* buffer) {
    AudioBufferList list;
    list.mNumberBuffers = 1;
    list.mBuffers[0].mNumberChannels = 1;
    list.mBuffers[0].mDataByteSize = BLOCK_FRAMES * sizeof(SInt16);
    list.mBuffers[0].mData = buffer;
    UInt32 frames = BLOCK_FRAMES;
    if (ExtAudioFileRead(file, &frames, &list) != noErr) return -1;
    return (long)frames;
}

// Decodes [input] into [output] (raw 16-bit mono PCM at 16 kHz): the number of samples, -1 when
// the file cannot be read
JNIEXPORT jlong JNICALL Java_io_github_valerioisufi_pageboard_lecture_audio_AudioCodecNative_nDecode(
    JNIEnv* env, jclass cls, jstring input, jstring output) {
    (void)cls;
    ExtAudioFileRef file = open_input(env, input);
    if (!file) return -1;
    const char* output_path = (*env)->GetStringUTFChars(env, output, NULL);
    FILE* out = output_path ? fopen(output_path, "wb") : NULL;
    if (output_path) (*env)->ReleaseStringUTFChars(env, output, output_path);
    if (!out) {
        ExtAudioFileDispose(file);
        return -1;
    }
    SInt16 buffer[BLOCK_FRAMES];
    jlong total = 0;
    for (;;) {
        long frames = read_block(file, buffer);
        if (frames < 0) {
            total = -1;
            break;
        }
        if (frames == 0) break;
        if (fwrite(buffer, sizeof(SInt16), (size_t)frames, out) != (size_t)frames) {
            total = -1;
            break;
        }
        total += frames;
    }
    fclose(out);
    ExtAudioFileDispose(file);
    return total;
}
