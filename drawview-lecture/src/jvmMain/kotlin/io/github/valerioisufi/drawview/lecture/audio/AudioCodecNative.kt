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

    override fun decode(input: String, output: String): Long = nDecode(input, output)
}

internal actual fun audioCodec(): AudioCodec? = AudioCodecNative.takeIf { it.isAvailable }
