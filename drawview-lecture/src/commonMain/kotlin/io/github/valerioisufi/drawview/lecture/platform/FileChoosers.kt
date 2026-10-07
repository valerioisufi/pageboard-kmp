package io.github.valerioisufi.drawview.lecture.platform

import androidx.compose.runtime.Composable

/**
 * A file chosen by the student: on the phones a copy the application reads ([temporary], deleted
 * once read).
 */
class ChosenFile(val path: String, val name: String, val lastModified: Long?, val temporary: Boolean = false)

/**
 * Where to write a file the student saves: [path], then [deliver] with the path written (on the
 * phones the file is written in the application, then given to the place the student chose).
 */
class FileTarget(val path: String, val deliver: suspend (written: String) -> Unit = {})

/**
 * Remembers the chooser of a file to open among [extensions] (the dialog of the desktop, the
 * document pickers of the phones): [onChosen] gets it, and is not called when cancelled.
 */
@Composable
internal expect fun rememberFileOpener(title: String, extensions: Set<String>, onChosen: (ChosenFile) -> Unit): () -> Unit

/**
 * Remembers the chooser of where to save a file of [mimeType] (named as suggested at launch):
 * [onTarget] gets where to write it, and is not called when cancelled.
 */
@Composable
internal expect fun rememberFileSaver(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): (suggestedName: String) -> Unit
