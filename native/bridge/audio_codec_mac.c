// JNI entry points of io.github.valerioisufi.drawview.lecture.audio.AudioCodecNative (desktop JVM,
// macOS): the audio files of other applications (m4a, mp3, wav, aiff, caf, flac...) decoded into
// the PCM of the lectures, and the recordings encoded as AAC in an m4a, with Audio Toolbox.
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
JNIEXPORT jlong JNICALL Java_io_github_valerioisufi_drawview_lecture_audio_AudioCodecNative_nDecode(
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

// Encodes [input] (e.g. the audio.wav of a lecture) into [output], AAC at [bitrate] bits per second
// in an m4a: 0 on success
JNIEXPORT jint JNICALL Java_io_github_valerioisufi_drawview_lecture_audio_AudioCodecNative_nEncodeM4a(
    JNIEnv* env, jclass cls, jstring input, jstring output, jint bitrate) {
    (void)cls;
    ExtAudioFileRef in = open_input(env, input);
    if (!in) return 1;

    AudioStreamBasicDescription aac;
    memset(&aac, 0, sizeof(aac));
    aac.mSampleRate = 16000;
    aac.mFormatID = kAudioFormatMPEG4AAC;
    aac.mChannelsPerFrame = 1;
    UInt32 size = sizeof(aac);
    AudioFormatGetProperty(kAudioFormatProperty_FormatInfo, 0, NULL, &size, &aac);

    CFURLRef url = url_of(env, output);
    ExtAudioFileRef out = NULL;
    OSStatus status = url ? ExtAudioFileCreateWithURL(url, kAudioFileM4AType, &aac, NULL, kAudioFileFlags_EraseFile, &out) : -1;
    if (url) CFRelease(url);
    if (status != noErr) {
        ExtAudioFileDispose(in);
        return 2;
    }
    AudioStreamBasicDescription client = lecture_format();
    if (ExtAudioFileSetProperty(out, kExtAudioFileProperty_ClientDataFormat, sizeof(client), &client) != noErr) {
        ExtAudioFileDispose(out);
        ExtAudioFileDispose(in);
        return 3;
    }
    // The bitrate of the encoder (speech: a low one is enough); the default when refused
    AudioConverterRef converter = NULL;
    size = sizeof(converter);
    if (ExtAudioFileGetProperty(out, kExtAudioFileProperty_AudioConverter, &size, &converter) == noErr && converter) {
        UInt32 rate = (UInt32)bitrate;
        if (AudioConverterSetProperty(converter, kAudioConverterEncodeBitRate, sizeof(rate), &rate) == noErr) {
            CFArrayRef config = NULL;
            ExtAudioFileSetProperty(out, kExtAudioFileProperty_ConverterConfig, sizeof(config), &config);
        }
    }

    SInt16 buffer[BLOCK_FRAMES];
    jint result = 0;
    for (;;) {
        long frames = read_block(in, buffer);
        if (frames < 0) {
            result = 4;
            break;
        }
        if (frames == 0) break;
        AudioBufferList list;
        list.mNumberBuffers = 1;
        list.mBuffers[0].mNumberChannels = 1;
        list.mBuffers[0].mDataByteSize = (UInt32)(frames * sizeof(SInt16));
        list.mBuffers[0].mData = buffer;
        if (ExtAudioFileWrite(out, (UInt32)frames, &list) != noErr) {
            result = 5;
            break;
        }
    }
    if (ExtAudioFileDispose(out) != noErr && result == 0) result = 6;
    ExtAudioFileDispose(in);
    return result;
}
