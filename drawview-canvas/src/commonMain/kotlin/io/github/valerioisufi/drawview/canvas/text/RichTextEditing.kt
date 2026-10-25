package io.github.valerioisufi.drawview.canvas.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.model.text.RichTextParser
import kotlin.math.max
import kotlin.math.min

/**
 * The formatting of a text being written, as changes of its notation (see [RichTextParser]): the
 * delimiters around the selection, the heading of a line, the lists, the blocks of code. Inside a
 * block of code the text is code: the formats do not apply, Enter keeps the indentation of the
 * line and continues no list, Tab moves by the columns of the code.
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

    /**
     * Adds or removes the delimiters of [format] around the selection (see [toggleDelimiters]);
     * code over several lines is a block of code ([toggleCodeBlock]). Nothing in a block of code.
     */
    public fun toggle(value: TextFieldValue, format: Format): TextFieldValue {
        if (inCodeBlock(value)) return value
        val start = min(value.selection.start, value.selection.end)
        val end = max(value.selection.start, value.selection.end)
        if (format == Format.CODE && value.text.substring(start, end).contains('\n')) return toggleCodeBlock(value)
        return toggleDelimiters(value, format.delimiter, format.delimiter)
    }

    /** The block of code the selection of [value] is in, its fences included, or `null`. */
    public fun codeBlockAt(value: TextFieldValue): RichTextParser.Span? {
        val start = min(value.selection.start, value.selection.end)
        val end = max(value.selection.start, value.selection.end)
        return RichTextParser.parse(value.text).firstOrNull { it.kind == RichTextParser.Kind.CODE_BLOCK && start >= it.start && end <= it.end }
    }

    /** Whether the selection of [value] is in a block of code (its fences included). */
    public fun inCodeBlock(value: TextFieldValue): Boolean = codeBlockAt(value) != null

    /**
     * Removes the fences of the block of code of the selection, or puts the lines of the selection
     * between fences (longer than any run of backticks in them), the selection on the same
     * characters.
     */
    public fun toggleCodeBlock(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val sel = value.selection
        val block = codeBlockAt(value)
        if (block != null) {
            val before = block.contentStart - block.start
            val after = block.end - block.contentEnd
            fun moved(offset: Int) = when {
                offset <= block.start -> offset
                offset <= block.contentStart -> block.start
                offset <= block.contentEnd -> offset - before
                offset <= block.end -> block.contentEnd - before
                else -> offset - before - after
            }
            return value.copy(
                text = text.substring(0, block.start) + text.substring(block.contentStart, block.contentEnd) + text.substring(block.end),
                selection = TextRange(moved(sel.start), moved(sel.end))
            )
        }
        val (firstLineStart, lastLineEnd) = selectedLines(text, sel)
        val code = text.substring(firstLineStart, lastLineEnd)
        var longest = 0
        var run = 0
        for (c in code) {
            run = if (c == '`') run + 1 else 0
            longest = max(longest, run)
        }
        val fence = "`".repeat(max(MIN_FENCE, longest + 1))
        val opening = fence + "\n"
        val closing = "\n" + fence
        fun moved(offset: Int) = when {
            offset < firstLineStart -> offset
            offset <= lastLineEnd -> offset + opening.length
            else -> offset + opening.length + closing.length
        }
        return value.copy(
            text = text.substring(0, firstLineStart) + opening + code + closing + text.substring(lastLineEnd),
            selection = TextRange(moved(sel.start), moved(sel.end))
        )
    }

    /**
     * Adds `showLineNumbers` after the opening fence of the block of the selection, or takes off
     * the words asking for its line numbers ([RichTextParser.LINE_NUMBERS]), the selection on the
     * same characters. Nothing outside a block.
     */
    public fun toggleLineNumbers(value: TextFieldValue): TextFieldValue {
        val block = codeBlockAt(value) ?: return value
        val text = value.text
        // The information after the characters of the fence, to the end of its line
        var infoStart = block.start + block.level
        val fence = text[infoStart]
        while (infoStart < text.length && text[infoStart] == fence) infoStart++
        val infoEnd = text.indexOf('\n', infoStart).let { if (it < 0) text.length else it }
        val info = text.substring(infoStart, infoEnd)
        val newInfo = if (block.numbered) {
            val kept = info.split(' ', '\t')
                .filter { it.isNotEmpty() && it.trimStart('{', '.').trimEnd('}').lowercase() !in RichTextParser.LINE_NUMBERS }
                .joinToString(" ")
            // The brace closing the attributes, gone with the word it was written after
            val closed = if ('{' in kept && '}' !in kept) "$kept}" else kept
            if (closed.isEmpty()) "" else info.takeWhile { it == ' ' || it == '\t' } + closed
        } else {
            val written = info.trimEnd()
            if (written.isBlank()) LINE_NUMBERS_WORD else "$written $LINE_NUMBERS_WORD"
        }
        val shift = newInfo.length - info.length
        fun moved(offset: Int) = when {
            offset <= infoStart -> offset
            offset <= infoEnd -> min(offset, infoStart + newInfo.length)
            else -> offset + shift
        }
        return value.copy(
            text = text.substring(0, infoStart) + newInfo + text.substring(infoEnd),
            selection = TextRange(moved(value.selection.start), moved(value.selection.end))
        )
    }

    /** Where the lines of [selection] start and end in [text] (a selection ending at a line break leaves the next line out). */
    private fun selectedLines(text: String, selection: TextRange): Pair<Int, Int> {
        val selMin = min(selection.start, selection.end)
        val selMax = max(selection.start, selection.end)
        val firstLineStart = text.lastIndexOf('\n', selMin - 1) + 1
        val lastLineEnd = if (selMax == selMin) {
            text.indexOf('\n', selMin).let { if (it < 0) text.length else it }
        } else {
            val endBound = if (selMax > 0 && text[selMax - 1] == '\n') selMax - 1 else selMax
            text.indexOf('\n', endBound).let { if (it < 0) text.length else it }
        }
        return firstLineStart to lastLineEnd
    }

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
        if (inCodeBlock(value)) return value
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
        if (inCodeBlock(value)) return value
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
        codeEnter(value)?.let { return it }
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
     * Enter in a block of code (the selection replaced): in its code, a new line indented as the
     * line of the caret; at the end of the opening fence of a block not closed, the block closed
     * under the caret. `null` elsewhere, on the fences of a block closed too (a plain line break).
     */
    private fun codeEnter(value: TextFieldValue): TextFieldValue? {
        val start = min(value.selection.start, value.selection.end)
        val end = max(value.selection.start, value.selection.end)
        val text = value.text.substring(0, start) + value.text.substring(end)
        val block = RichTextParser.parse(text).firstOrNull { it.kind == RichTextParser.Kind.CODE_BLOCK && start >= it.start && start <= it.end } ?: return null
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        if (lineStart == block.start) {
            val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            // Not closed: its code runs to the end of the text, no closing fence after it
            val closed = block.contentEnd < block.end
            if (closed || text.substring(start, lineEnd).isNotBlank()) return null
            var fenceEnd = block.start + block.level
            val char = text[fenceEnd]
            while (fenceEnd < text.length && text[fenceEnd] == char) fenceEnd++
            val fence = text.substring(block.start, fenceEnd)
            return TextFieldValue(text.substring(0, start) + "\n\n" + fence + text.substring(start), TextRange(start + 1))
        }
        if (start < block.contentStart || start > block.contentEnd) return null
        val indent = text.substring(lineStart, start).takeWhile { it == ' ' || it == '\t' }
        return TextFieldValue(text.substring(0, start) + "\n" + indent + text.substring(start), TextRange(start + 1 + indent.length))
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
     * [newValue] with its caret out of the markers of the list items, which are shown as their
     * bullet or number and are no place for the caret: a caret moved into a marker from the text of
     * its item goes to the end of the line before (backwards), any other to the text of the item.
     * `null` when [newValue] needs no change (its text changed, a selection, no marker).
     */
    public fun keepCaretOutOfMarkers(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        if (oldValue.text != newValue.text || !newValue.selection.collapsed) return null
        val caret = newValue.selection.start
        val item = listItemMarkerAt(newValue.text, caret) ?: return null
        val backwards = oldValue.selection.collapsed && oldValue.selection.start == item.contentStart && caret < item.contentStart
        val target = if (backwards && item.start > 0) item.start - 1 else item.contentStart
        return newValue.copy(selection = TextRange(target))
    }

    /**
     * What a deletion of [oldValue] into [newValue] does when it deletes a character that is not
     * shown: Backspace at the start of the text of a list item deletes the last character of its
     * marker, and takes the whole marker away instead; Backspace at the start of the code of a block
     * deletes the line break of its opening fence (hidden while the code is written), and moves the
     * caret onto the fence instead, which shows it. `null` when [newValue] is not such a deletion.
     */
    public fun handleConcealedDeletion(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        val old = oldValue.text
        if (!oldValue.selection.collapsed || newValue.text.length != old.length - 1) return null
        val caret = oldValue.selection.start
        if (caret == 0 || newValue.text != old.removeRange(caret - 1, caret)) return null
        val spans = RichTextParser.parse(old)
        if (spans.any { it.kind == RichTextParser.Kind.CODE_BLOCK && it.contentStart == caret && it.start < caret }) return TextFieldValue(old, TextRange(caret - 1))
        val item = spans.firstOrNull { it.kind == RichTextParser.Kind.LIST_ITEM && it.contentStart == caret && it.start < caret } ?: return null
        return TextFieldValue(old.removeRange(item.start, item.contentStart), TextRange(item.start))
    }

    /** The list item of [text] whose marker (its indent, its bullet or number, the space after it) has [offset], or `null`. */
    private fun listItemMarkerAt(text: String, offset: Int): RichTextParser.Span? =
        RichTextParser.parse(text).firstOrNull { it.kind == RichTextParser.Kind.LIST_ITEM && offset >= it.start && offset < it.contentStart }

    /**
     * Indents the lines covered by the selection by adding 2 spaces at the beginning of each line;
     * in the code of a block, by [CODE_INDENT] spaces, or at the caret up to the next column of
     * [CODE_INDENT].
     */
    public fun indent(value: TextFieldValue): TextFieldValue {
        if (!inCodeBlock(value)) return indentLines(value, LIST_INDENT)
        val start = min(value.selection.start, value.selection.end)
        if (start != max(value.selection.start, value.selection.end)) return indentLines(value, CODE_INDENT)
        val text = value.text
        var column = 0
        for (i in text.lastIndexOf('\n', start - 1) + 1 until start) {
            column = if (text[i] == '\t') (column / CODE_INDENT + 1) * CODE_INDENT else column + 1
        }
        val spaces = " ".repeat(CODE_INDENT - column % CODE_INDENT)
        return value.copy(text = text.substring(0, start) + spaces + text.substring(start), selection = TextRange(start + spaces.length))
    }

    /** Adds [width] spaces at the beginning of each line covered by the selection. */
    private fun indentLines(value: TextFieldValue, width: Int): TextFieldValue {
        val text = value.text
        val sel = value.selection
        val (firstLineStart, lastLineEnd) = selectedLines(text, sel)
        val unit = " ".repeat(width)

        val block = text.substring(firstLineStart, lastLineEnd)
        val lines = block.split('\n')
        val newLines = lines.map { "$unit$it" }
        val newBlock = newLines.joinToString("\n")

        fun mapOffset(offset: Int): Int {
            if (offset < firstLineStart) return offset
            if (offset > lastLineEnd) return offset + (newBlock.length - block.length)
            var oldPos = firstLineStart
            var added = 0
            for (line in lines) {
                val oldLineEnd = oldPos + line.length
                if (offset <= oldLineEnd) {
                    return offset + added + width
                }
                added += width
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
     * Outdents the lines covered by the selection by removing up to 2 leading spaces from each
     * line; in the code of a block, up to [CODE_INDENT] spaces or a tab.
     */
    public fun outdent(value: TextFieldValue): TextFieldValue = outdentLines(value, if (inCodeBlock(value)) CODE_INDENT else LIST_INDENT)

    /** Removes up to [width] leading spaces, or a tab, from each line covered by the selection. */
    private fun outdentLines(value: TextFieldValue, width: Int): TextFieldValue {
        val text = value.text
        val sel = value.selection
        val (firstLineStart, lastLineEnd) = selectedLines(text, sel)

        val block = text.substring(firstLineStart, lastLineEnd)
        val lines = block.split('\n')
        val removedSpaces = lines.map { line ->
            if (line.startsWith("\t")) 1 else line.takeWhile { it == ' ' }.length.coerceAtMost(width)
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

    /** The spaces of a level of a list, and of a column of the code. */
    private const val LIST_INDENT = 2
    private const val CODE_INDENT = 4

    /** The fewest backticks of a fence. */
    private const val MIN_FENCE = 3

    /** The word asking for the line numbers of a block, as the editor writes it. */
    private const val LINE_NUMBERS_WORD = "showLineNumbers"
}
