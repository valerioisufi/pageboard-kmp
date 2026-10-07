package io.github.valerioisufi.drawview.lecture.audio

import io.github.valerioisufi.drawview.core.platform.JvmNativeLibraries

/** JNI entry points of libaudio_bridge (native/bridge/audio_bridge_jni.c). */
internal object AudioNative {
    val isAvailable: Boolean by lazy { JvmNativeLibraries.load(AudioNative::class.java, "audio_bridge") }

    fun require() = check(isAvailable) { "The audio codecs are not available" }

    @JvmStatic external fun nOpusEncoderCreate(rate: Int, bitrate: Int): Long
    @JvmStatic external fun nOpusEncode(encoder: Long, pcm: ShortArray, frameSize: Int, out: ByteArray): Int
    @JvmStatic external fun nOpusLookahead(encoder: Long): Int
    @JvmStatic external fun nOpusEncoderDestroy(encoder: Long)
    @JvmStatic external fun nOpusDecoderCreate(rate: Int): Long
    @JvmStatic external fun nOpusDecode(decoder: Long, packet: ByteArray?, length: Int, pcm: ShortArray): Int
    @JvmStatic external fun nOpusDecoderDestroy(decoder: Long)
    @JvmStatic external fun nAacEncoderCreate(rate: Int, bitrate: Int): Long
    @JvmStatic external fun nAacEncode(encoder: Long, pcm: ShortArray, out: ByteArray): Int
    @JvmStatic external fun nAacEncoderDestroy(encoder: Long)
}

internal actual class OpusEncoder actual constructor(sampleRate: Int, bitrate: Int) {
    private var handle = AudioNative.require().let { AudioNative.nOpusEncoderCreate(sampleRate, bitrate) }.also { check(it != 0L) { "No Opus encoder" } }
    private val buffer = ByteArray(MAX_OPUS_PACKET)

    actual val lookahead: Int = AudioNative.nOpusLookahead(handle)

    actual fun encode(frame: ShortArray): ByteArray {
        val written = AudioNative.nOpusEncode(handle, frame, frame.size, buffer)
        check(written >= 0) { "Opus encoding failed ($written)" }
        return buffer.copyOf(written)
    }

    actual fun close() {
        if (handle != 0L) AudioNative.nOpusEncoderDestroy(handle)
        handle = 0
    }
}

internal actual class OpusDecoder actual constructor(sampleRate: Int) {
    private var handle = AudioNative.require().let { AudioNative.nOpusDecoderCreate(sampleRate) }.also { check(it != 0L) { "No Opus decoder" } }
    private val buffer = ShortArray(MAX_OPUS_SAMPLES)

    actual fun decode(packet: ByteArray?): ShortArray {
        val decoded = AudioNative.nOpusDecode(handle, packet, packet?.size ?: 0, buffer)
        return if (decoded > 0) buffer.copyOf(decoded) else ShortArray(0)
    }

    actual fun close() {
        if (handle != 0L) AudioNative.nOpusDecoderDestroy(handle)
        handle = 0
    }
}

internal actual class AacEncoder actual constructor(sampleRate: Int, bitrate: Int) {
    private var handle = AudioNative.require().let { AudioNative.nAacEncoderCreate(sampleRate, bitrate) }.also { check(it != 0L) { "No AAC encoder" } }
    private val buffer = ByteArray(8192)

    actual fun encode(frame: ShortArray): ByteArray {
        val written = AudioNative.nAacEncode(handle, frame, buffer)
        check(written >= 0) { "AAC encoding failed" }
        return buffer.copyOf(written)
    }

    actual fun close() {
        if (handle != 0L) AudioNative.nAacEncoderDestroy(handle)
        handle = 0
    }
}
