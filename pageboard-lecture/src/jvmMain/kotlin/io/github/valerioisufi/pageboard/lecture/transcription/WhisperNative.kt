package io.github.valerioisufi.pageboard.lecture.transcription

import io.github.valerioisufi.pageboard.platform.JvmNativeLibraries

/** JNI entry points of libwhisper_bridge (native/bridge/whisper_bridge_jni.cpp, whisper.cpp inside). */
internal object WhisperNative : WhisperEngine {
    /** Loads the bridge (whisper.cpp is linked statically into it). */
    val isAvailable: Boolean by lazy { JvmNativeLibraries.load(WhisperNative::class.java, "whisper_bridge") }

    @JvmStatic
    private external fun nInit(modelPath: String, useGpu: Boolean): Long

    @JvmStatic
    private external fun nTranscribe(handle: Long, samples: FloatArray, language: String, prompt: String, threads: Int): ByteArray?

    @JvmStatic
    private external fun nAbort(handle: Long)

    @JvmStatic
    private external fun nFree(handle: Long)

    override fun load(modelPath: String, useGpu: Boolean): Long = if (isAvailable) nInit(modelPath, useGpu) else 0L

    override fun transcribe(handle: Long, samples: FloatArray, language: String, prompt: String, threads: Int): ByteArray? =
        nTranscribe(handle, samples, language, prompt, threads)

    override fun abort(handle: Long) = nAbort(handle)

    override fun free(handle: Long) = nFree(handle)
}

internal actual fun whisperEngine(): WhisperEngine? = WhisperNative.takeIf { it.isAvailable }
