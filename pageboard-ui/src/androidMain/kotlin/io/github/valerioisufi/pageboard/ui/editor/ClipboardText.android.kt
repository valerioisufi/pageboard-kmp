package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.platform.Clipboard

internal actual fun htmlIn(clipboard: Clipboard): String? {
    val clip = clipboard.nativeClipboard.primaryClip ?: return null
    return (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).htmlText }
}
