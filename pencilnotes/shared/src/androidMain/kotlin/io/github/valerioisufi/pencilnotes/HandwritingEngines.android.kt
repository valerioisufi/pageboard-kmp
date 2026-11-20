package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.handwriting.mlkit.MlKitLanguageModels
import io.github.valerioisufi.pageboard.handwriting.mlkit.MlKitTextRecognizer

/** ML Kit, whose models of the languages the user downloads from the settings of the search. */
internal actual fun handwritingEngines(): HandwritingEngines {
    val models = MlKitLanguageModels()
    return HandwritingEngines(text = listOf(MlKitTextRecognizer(models)), languageModels = models)
}
