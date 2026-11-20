package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.handwriting.windows.WindowsInkRecognizer

/** The handwriting recognizers of Windows; on macOS and Linux none yet. */
internal actual fun handwritingEngines(): HandwritingEngines =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows")) HandwritingEngines(text = listOf(WindowsInkRecognizer())) else HandwritingEngines.None
