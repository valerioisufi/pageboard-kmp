package io.github.valerioisufi.drawview.lecture.ui.platform

import androidx.compose.runtime.Composable
import io.github.valerioisufi.drawview.lecture.platform.ChosenFile

/**
 * Remembers the chooser of a file to open among [extensions] (the dialog of the desktop, the
 * document pickers of the phones): [onChosen] gets it, and is not called when cancelled.
 */
@Composable
internal expect fun rememberFileOpener(title: String, extensions: Set<String>, onChosen: (ChosenFile) -> Unit): () -> Unit
