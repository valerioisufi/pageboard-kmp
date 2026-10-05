package io.github.valerioisufi.drawview.lecture.audio

import io.github.valerioisufi.drawview.core.platform.JvmNativeLibraries

/** JNI entry points of libaudio_codec_bridge (native/bridge/audio_codec_mac.c, macOS only). */
internal object AudioCodecNative : AudioCodec {
    val isAvailable: Boolean by lazy {
        System.getProperty("os.name").lowercase().contains("mac") &&
            JvmNativeLibraries.load(AudioCodecNative::class.java, "audio_codec_bridge")
    }

    @JvmStatic
    private external fun nDecode(input: String, output: String): Long

    @JvmStatic
    private external fun nEncodeM4a(input: String, output: String, bitrate: Int): Int

    override fun decode(input: String, output: String): Long = nDecode(input, output)

    override fun encodeM4a(input: String, output: String): Boolean = nEncodeM4a(input, output, SPEECH_BITRATE) == 0

    /** Speech at 16 kHz (AAC accepts 12 to 48 kbit/s): about 14 MB per hour. */
    private const val SPEECH_BITRATE = 32_000
}

internal actual fun audioCodec(): AudioCodec? = AudioCodecNative.takeIf { it.isAvailable }
