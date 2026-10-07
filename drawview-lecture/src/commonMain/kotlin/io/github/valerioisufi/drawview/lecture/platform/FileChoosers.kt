package io.github.valerioisufi.drawview.lecture.platform

import androidx.compose.runtime.Composable

/**
 * A file chosen by the student: on the phones a copy the application reads ([temporary], deleted
 * once read).
 */
class ChosenFile(val path: String, val name: String, val lastModified: Long?, val temporary: Boolean = false)

/**
 * Remembers the chooser of a file to open among [extensions] (the dialog of the desktop, the
 * document pickers of the phones): [onChosen] gets it, and is not called when cancelled.
 */
@Composable
internal expect fun rememberFileOpener(title: String, extensions: Set<String>, onChosen: (ChosenFile) -> Unit): () -> Unit
