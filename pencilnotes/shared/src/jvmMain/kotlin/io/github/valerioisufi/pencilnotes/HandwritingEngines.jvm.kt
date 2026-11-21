package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.handwriting.pencilkit.PencilKitTextRecognizer
import io.github.valerioisufi.pageboard.handwriting.windows.WindowsInkRecognizer

/** The handwriting recognizers of Windows; PencilKit on macOS (it reads from macOS 27); none on Linux. */
internal actual fun handwritingEngines(): HandwritingEngines {
    val system = System.getProperty("os.name").orEmpty()
    return when {
        system.startsWith("Windows") -> HandwritingEngines(text = listOf(WindowsInkRecognizer()))
        system.startsWith("Mac") -> HandwritingEngines(text = listOf(PencilKitTextRecognizer()))
        else -> HandwritingEngines.None
    }
}
