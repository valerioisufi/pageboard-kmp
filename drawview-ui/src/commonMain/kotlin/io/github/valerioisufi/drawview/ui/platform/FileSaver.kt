package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable

/**
 * Where to write a file the user saves: [path], then [deliver] with the path written (on the
 * phones the file is written in the application, then given to the place the user chose).
 */
class FileTarget(val path: String, val deliver: suspend (written: String) -> Unit = {})

/**
 * Remembers the chooser of where to save a file of [mimeType] (the dialog of the desktop, the
 * document pickers of the phones), named as suggested at launch: [onTarget] gets where to write
 * it, and is not called when cancelled.
 */
@Composable
expect fun rememberFileSaver(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): (suggestedName: String) -> Unit
