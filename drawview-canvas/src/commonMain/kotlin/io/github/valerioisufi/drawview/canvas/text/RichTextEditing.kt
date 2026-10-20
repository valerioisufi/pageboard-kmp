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

    /**
     * Makes the lines of the selection bullet list items (`- `), or plain text again if they are
     * already all bullet list items.
     */
    public fun toggleBulletList(value: TextFieldValue): TextFieldValue = toggleListLines(value, ordered = false)

    /**
     * Makes the lines of the selection numbered list items (`1. `, `2. `...), or plain text again
     * if they are already all numbered list items.
     */
    public fun toggleNumberedList(value: TextFieldValue): TextFieldValue = toggleListLines(value, ordered = true)

    private fun toggleListLines(value: TextFieldValue, ordered: Boolean): TextFieldValue {
        val text = value.text
        val sel = value.selection
        val selMin = min(sel.start, sel.end)
        val selMax = max(sel.start, sel.end)

        val firstLineStart = text.lastIndexOf('\n', selMin - 1) + 1
        val lastLineEnd = if (selMax == selMin) {
            text.indexOf('\n', selMin).let { if (it < 0) text.length else it }
        } else {
            val endBound = if (selMax > 0 && text[selMax - 1] == '\n') selMax - 1 else selMax
            text.indexOf('\n', endBound).let { if (it < 0) text.length else it }
        }

        val block = text.substring(firstLineStart, lastLineEnd)
        val lines = block.split('\n')

        val parsed = lines.map { line ->
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            val rest = line.substring(indent.length)
            val numMatch = NUMBERED_MARKER.find(rest)
            val headMatch = HEADING_MARKER.find(rest)
            val (type, len) = when {
                rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ ") -> {
                    var mLen = 2
                    while (mLen < rest.length && rest[mLen].isWhitespace()) mLen++
                    MarkerType.BULLET to mLen
                }
                numMatch != null -> MarkerType.NUMBERED to numMatch.value.length
                headMatch != null -> MarkerType.HEADING to headMatch.value.length
                else -> MarkerType.NONE to 0
            }
            LineInfo(indent, type, len, rest.substring(len))
        }

        val allSame = if (ordered) {
            parsed.all { it.markerType == MarkerType.NUMBERED }
        } else {
            parsed.all { it.markerType == MarkerType.BULLET }
        }

        val newLines = parsed.mapIndexed { index, lineInfo ->
            if (allSame) {
                lineInfo.indent + lineInfo.content
            } else if (ordered) {
                lineInfo.indent + "${index + 1}. " + lineInfo.content
            } else {
                lineInfo.indent + BULLET_PREFIX + lineInfo.content
            }
        }

        val newBlock = newLines.joinToString("\n")

        fun mapOffset(offset: Int): Int {
            if (offset < firstLineStart) return offset
            if (offset > lastLineEnd) return offset + (newBlock.length - block.length)
            var oldPos = firstLineStart
            var newPos = firstLineStart
            for (i in lines.indices) {
                val oldLine = lines[i]
                val newLine = newLines[i]
                val info = parsed[i]
                val oldLineEnd = oldPos + oldLine.length
                if (offset <= oldLineEnd) {
                    val inLineOffset = offset - oldPos
                    val indentLen = info.indent.length
                    val oldMarkerEnd = indentLen + info.markerLength
                    val newMarkerEnd = if (allSame) {
                        indentLen
                    } else if (ordered) {
                        indentLen + "${i + 1}. ".length
                    } else {
                        indentLen + BULLET_PREFIX.length
                    }
                    val mappedInLine = when {
                        inLineOffset < indentLen -> inLineOffset
                        inLineOffset <= oldMarkerEnd -> newMarkerEnd
                        else -> newMarkerEnd + (inLineOffset - oldMarkerEnd)
                    }
                    return (newPos + mappedInLine).coerceIn(newPos, newPos + newLine.length)
                }
                oldPos = oldLineEnd + 1
                newPos = newPos + newLine.length + 1
            }
            return newPos
        }

        val newText = text.substring(0, firstLineStart) + newBlock + text.substring(lastLineEnd)
        return value.copy(
            text = newText,
            selection = TextRange(mapOffset(sel.start), mapOffset(sel.end))
        )
    }

    /**
     * Handles the Enter key on a list item:
     * - If the current list item is empty (contains only marker and optional whitespace), clears the
     *   marker and exits the list.
     * - If the current list item has content and the caret is after the marker, continues the list on
     *   the next line with the same indentation and the next bullet or incremented number.
     * - Returns `null` if the cursor is not in a list item or before the marker (in which case a
     *   standard newline should be inserted).
     */
    public fun handleEnter(value: TextFieldValue): TextFieldValue? {
        val start = min(value.selection.start, value.selection.end)
        val end = max(value.selection.start, value.selection.end)
        val text = if (start != end) {
            value.text.substring(0, start) + value.text.substring(end)
        } else {
            value.text
        }
        val cursor = start

        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val nextNewline = text.indexOf('\n', cursor)
        val lineEnd = if (nextNewline < 0) text.length else nextNewline
        val line = text.substring(lineStart, lineEnd)
        val cursorInLine = cursor - lineStart

        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val rest = line.substring(indent.length)
        val numMatch = NUMBERED_MARKER.find(rest)

        val (markerType, markerLen, numValue, bulletMarker) = when {
            rest.startsWith("- ") -> MarkerInfo(MarkerType.BULLET, 2, 0, "- ")
            rest.startsWith("* ") -> MarkerInfo(MarkerType.BULLET, 2, 0, "* ")
            rest.startsWith("+ ") -> MarkerInfo(MarkerType.BULLET, 2, 0, "+ ")
            numMatch != null -> {
                val num = numMatch.groupValues[1].toIntOrNull() ?: 1
                MarkerInfo(MarkerType.NUMBERED, numMatch.value.length, num, "")
            }
            else -> MarkerInfo(MarkerType.NONE, 0, 0, "")
        }

        if (markerType == MarkerType.NONE) return null

        val markerEnd = indent.length + markerLen
        val content = rest.substring(markerLen)

        // Empty list item: exit list by clearing the marker on this line
        if (content.isBlank()) {
            val newText = text.substring(0, lineStart) + text.substring(lineEnd)
            return TextFieldValue(newText, TextRange(lineStart))
        }

        // Caret before the marker: regular newline above the item
        if (cursorInLine < markerEnd) return null

        val beforeCursor = line.substring(0, cursorInLine)
        val afterCursor = line.substring(cursorInLine).trimStart()
        val nextPrefix = if (markerType == MarkerType.NUMBERED) {
            "$indent${numValue + 1}. "
        } else {
            "$indent$bulletMarker"
        }

        val newLineBlock = beforeCursor + "\n" + nextPrefix + afterCursor
        val newText = text.substring(0, lineStart) + newLineBlock + text.substring(lineEnd)
        val newCursor = lineStart + beforeCursor.length + 1 + nextPrefix.length
        return TextFieldValue(newText, TextRange(newCursor))
    }

    /**
     * Checks if [newValue] is a single newline insertion resulting from pressing Return/Enter in
     * an IME or software keyboard, and applies [handleEnter] if so. Returns `null` if not a single
     * newline insertion or if [handleEnter] does not apply.
     */
    public fun handleSmartNewline(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        if (oldValue.text == newValue.text) return null
        val oldSelMin = min(oldValue.selection.start, oldValue.selection.end)
        val oldSelMax = max(oldValue.selection.start, oldValue.selection.end)
        val expectedLength = oldValue.text.length - (oldSelMax - oldSelMin) + 1
        if (newValue.text.length != expectedLength) return null
        if (newValue.selection.start != oldSelMin + 1 || newValue.selection.end != oldSelMin + 1) return null
        if (newValue.text.substring(oldSelMin, oldSelMin + 1) != "\n") return null
        if (newValue.text.substring(0, oldSelMin) != oldValue.text.substring(0, oldSelMin)) return null
        if (newValue.text.substring(oldSelMin + 1) != oldValue.text.substring(oldSelMax)) return null
        return handleEnter(oldValue)
    }

    /**
     * Indents the lines covered by the selection by adding 2 spaces at the beginning of each line.
     */
    public fun indent(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val sel = value.selection
        val selMin = min(sel.start, sel.end)
        val selMax = max(sel.start, sel.end)

        val firstLineStart = text.lastIndexOf('\n', selMin - 1) + 1
        val lastLineEnd = if (selMax == selMin) {
            text.indexOf('\n', selMin).let { if (it < 0) text.length else it }
        } else {
            val endBound = if (selMax > 0 && text[selMax - 1] == '\n') selMax - 1 else selMax
            text.indexOf('\n', endBound).let { if (it < 0) text.length else it }
        }

        val block = text.substring(firstLineStart, lastLineEnd)
        val lines = block.split('\n')
        val newLines = lines.map { "  $it" }
        val newBlock = newLines.joinToString("\n")

        fun mapOffset(offset: Int): Int {
            if (offset < firstLineStart) return offset
            if (offset > lastLineEnd) return offset + (newBlock.length - block.length)
            var oldPos = firstLineStart
            var added = 0
            for (line in lines) {
                val oldLineEnd = oldPos + line.length
                if (offset <= oldLineEnd) {
                    return offset + added + 2
                }
                added += 2
                oldPos = oldLineEnd + 1
            }
            return offset + added
        }

        val newText = text.substring(0, firstLineStart) + newBlock + text.substring(lastLineEnd)
        return value.copy(
            text = newText,
            selection = TextRange(mapOffset(sel.start), mapOffset(sel.end))
        )
    }

    /**
     * Outdents the lines covered by the selection by removing up to 2 leading spaces from each line.
     */
    public fun outdent(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val sel = value.selection
        val selMin = min(sel.start, sel.end)
        val selMax = max(sel.start, sel.end)

        val firstLineStart = text.lastIndexOf('\n', selMin - 1) + 1
        val lastLineEnd = if (selMax == selMin) {
            text.indexOf('\n', selMin).let { if (it < 0) text.length else it }
        } else {
            val endBound = if (selMax > 0 && text[selMax - 1] == '\n') selMax - 1 else selMax
            text.indexOf('\n', endBound).let { if (it < 0) text.length else it }
        }

        val block = text.substring(firstLineStart, lastLineEnd)
        val lines = block.split('\n')
        val removedSpaces = lines.map { line ->
            when {
                line.startsWith("  ") -> 2
                line.startsWith(" ") -> 1
                line.startsWith("\t") -> 1
                else -> 0
            }
        }
        val newLines = lines.mapIndexed { i, line -> line.substring(removedSpaces[i]) }
        val newBlock = newLines.joinToString("\n")

        fun mapOffset(offset: Int): Int {
            if (offset < firstLineStart) return offset
            if (offset > lastLineEnd) return offset - (block.length - newBlock.length)
            var oldPos = firstLineStart
            var removedSoFar = 0
            for (i in lines.indices) {
                val line = lines[i]
                val rem = removedSpaces[i]
                val oldLineEnd = oldPos + line.length
                if (offset <= oldLineEnd) {
                    val inLine = offset - oldPos
                    val newInLine = (inLine - rem).coerceAtLeast(0)
                    return (oldPos - removedSoFar) + newInLine
                }
                removedSoFar += rem
                oldPos = oldLineEnd + 1
            }
            return (offset - removedSoFar).coerceAtLeast(firstLineStart)
        }

        val newText = text.substring(0, firstLineStart) + newBlock + text.substring(lastLineEnd)
        return value.copy(
            text = newText,
            selection = TextRange(mapOffset(sel.start), mapOffset(sel.end))
        )
    }

    private data class MarkerInfo(
        val type: MarkerType,
        val length: Int,
        val number: Int,
        val bulletMarker: String
    )

    private data class LineInfo(
        val indent: String,
        val markerType: MarkerType,
        val markerLength: Int,
        val content: String
    )

    private enum class MarkerType { NONE, BULLET, NUMBERED, HEADING }

    private val NUMBERED_MARKER = Regex("""^(\d+)\.[ \t]+""")
    private val HEADING_MARKER = Regex("""^#{1,6}[ \t]+""")
    private const val BULLET_PREFIX = "- "
    private const val HEADING_PREFIX = "# "
}
