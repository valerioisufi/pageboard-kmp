package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.handwriting.pencilkit.PencilKitTextRecognizer

/** The handwriting recognizer of PencilKit, which reads from iOS and iPadOS 27 (none before). */
internal actual fun handwritingEngines(): HandwritingEngines = HandwritingEngines(text = listOf(PencilKitTextRecognizer()))
