package io.github.valerioisufi.drawview.canvas.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.model.text.RichTextParser
import kotlin.math.max
import kotlin.math.min

/**
 * The formatting of a text being written, as changes of its notation (see [RichTextParser]): the
 * delimiters around the selection, the heading of a line.
 */
public object RichTextEditing {

    /** The delimiters of an inline format. */
    public enum class Format(public val delimiter: String) {
        BOLD("**"),
        ITALIC("*"),
        STRIKETHROUGH("~~"),
        CODE("`"),
        FORMULA("$")
    }

    /** Adds or removes the delimiters of [format] around the selection (see [toggleDelimiters]). */
    public fun toggle(value: TextFieldValue, format: Format): TextFieldValue = toggleDelimiters(value, format.delimiter, format.delimiter)

    /**
     * Adds or removes the delimiters around the selection, or inserts them at the cursor (placing
     * the cursor between them).
     */
    internal fun toggleDelimiters(value: TextFieldValue, prefix: String, suffix: String): TextFieldValue {
        val text = value.text
        val start = min(value.selection.start, value.selection.end)
        val end = max(value.selection.start, value.selection.end)
        if (start == end) {
            val cursor = start + prefix.length
            return value.copy(text = text.substring(0, start) + prefix + suffix + text.substring(start), selection = TextRange(cursor))
        }
        val selected = text.substring(start, end)
        // Already wrapped: remove the delimiters
        val wrapped = start >= prefix.length && end + suffix.length <= text.length &&
            text.startsWith(prefix, start - prefix.length) && text.startsWith(suffix, end)
        return if (wrapped) {
            value.copy(
                text = text.substring(0, start - prefix.length) + selected + text.substring(end + suffix.length),
                selection = TextRange(start - prefix.length, end - prefix.length)
            )
        } else {
            value.copy(
                text = text.substring(0, start) + prefix + selected + suffix + text.substring(end),
                selection = TextRange(start + prefix.length, end + prefix.length)
            )
        }
    }

    /**
     * Makes the line of the caret (of the start of the selection) a heading, or a heading line
     * plain text again: the `#` and the spaces after them at the start of the line.
     */
    public fun toggleHeading(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val sel = value.selection
        val lineStart = text.lastIndexOf('\n', min(sel.start, sel.end) - 1) + 1
        var prefixEnd = lineStart
        while (prefixEnd < text.length && text[prefixEnd] == '#') prefixEnd++
        val isHeading = prefixEnd > lineStart && prefixEnd < text.length && text[prefixEnd] == ' '
        if (isHeading) {
            while (prefixEnd < text.length && text[prefixEnd] == ' ') prefixEnd++
        }
        val removed = if (isHeading) prefixEnd - lineStart else 0
        val inserted = if (isHeading) "" else HEADING_PREFIX
        val shift = inserted.length - removed
        fun moved(offset: Int) = if (offset < lineStart) offset else max(lineStart, offset + shift)
        return value.copy(
            text = text.substring(0, lineStart) + inserted + text.substring(lineStart + removed),
            selection = TextRange(moved(sel.start), moved(sel.end))
        )
    }

    private const val HEADING_PREFIX = "# "
}
