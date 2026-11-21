package io.github.valerioisufi.pageboard.handwriting.pencilkit

// No PencilKit on Android: nothing is read

internal actual fun pencilKitVersion(): Int = 0

internal actual fun pencilKitLanguages(): String = ""

internal actual fun pencilKitAvailable(timeoutMs: Int): Boolean = false

internal actual fun pencilKitRecognize(language: String, samples: PencilKitSamples, timeoutMs: Int): Pair<String, String>? = null
