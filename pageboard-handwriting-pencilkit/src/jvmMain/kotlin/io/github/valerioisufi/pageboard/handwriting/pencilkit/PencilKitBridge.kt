package io.github.valerioisufi.pageboard.handwriting.pencilkit

import io.github.valerioisufi.pageboard.platform.JvmNativeLibraries

/** The JNI library of the PencilKit bridge (`native/bridge/pencilkit_bridge_jni.c`), on macOS only. */
internal object PencilKitBridge {
    val isLoaded: Boolean by lazy {
        System.getProperty("os.name").orEmpty().startsWith("Mac") &&
            JvmNativeLibraries.load(PencilKitBridge::class.java, "pencilkit_bridge")
    }

    @JvmStatic
    external fun version(): Int

    @JvmStatic
    external fun languages(): String?

    @JvmStatic
    external fun available(timeoutMs: Int): Boolean

    /** The text and the indexable content as UTF-8, `null` when nothing was read. */
    @JvmStatic
    external fun recognize(language: String, xy: FloatArray, times: DoubleArray, pressures: FloatArray, sizes: IntArray, timeoutMs: Int): Array<ByteArray>?
}

internal actual fun pencilKitVersion(): Int = if (PencilKitBridge.isLoaded) PencilKitBridge.version() else 0

internal actual fun pencilKitLanguages(): String = if (PencilKitBridge.isLoaded) PencilKitBridge.languages().orEmpty() else ""

internal actual fun pencilKitAvailable(timeoutMs: Int): Boolean = PencilKitBridge.isLoaded && PencilKitBridge.available(timeoutMs)

internal actual fun pencilKitRecognize(language: String, samples: PencilKitSamples, timeoutMs: Int): Pair<String, String>? {
    if (!PencilKitBridge.isLoaded) return null
    val read = PencilKitBridge.recognize(language, samples.xy, samples.times, samples.pressures, samples.sizes, timeoutMs) ?: return null
    return read[0].decodeToString() to read[1].decodeToString()
}
