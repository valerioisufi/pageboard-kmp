package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard

/** An entry of the clipboard holding [text] only. */
internal expect fun plainClipEntry(text: String): ClipEntry

/**
 * The [Clipboard] given to the field of a text: what it pastes is the formatted text of the
 * clipboard in the notation, without the field knowing. All the rest is the clipboard of [delegate].
 *
 * @param formattedText the formatted text of the clipboard, `null` to paste its plain text.
 * @param onFormatted told of the text given to the field, which comes back as it is when pasted.
 */
internal class FormattedPasteClipboard(
    private val delegate: Clipboard,
    private val formattedText: () -> String?,
    private val onFormatted: (String) -> Unit
) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? {
        val formatted = formattedText() ?: return delegate.getClipEntry()
        onFormatted(formatted)
        return plainClipEntry(formatted)
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = delegate.setClipEntry(clipEntry)

    override val nativeClipboard: NativeClipboard get() = delegate.nativeClipboard
}
