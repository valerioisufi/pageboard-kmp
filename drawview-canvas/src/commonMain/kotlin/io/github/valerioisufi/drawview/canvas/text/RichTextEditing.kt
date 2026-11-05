package io.github.valerioisufi.drawview.canvas.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.model.text.ListMarker
import io.github.valerioisufi.drawview.model.text.ListNumbering
import io.github.valerioisufi.drawview.model.text.ListRenumbering
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
     * Adds [format] to the selection or takes it off, by the spans of the notation (see
     * [InlineFormatting.toggle]); code over several lines is a block of code ([toggleCodeBlock]).
     * Nothing in a block of code.
     */
    public fun toggle(value: TextFieldValue, format: Format): TextFieldValue {
        if (inCodeBlock(value)) return value
        val start = min(value.selection.start, value.selection.end)
        val end = max(value.selection.start, value.selection.end)
        if (format == Format.CODE && value.text.substring(start, end).contains('\n')) return toggleCodeBlock(value)
        return InlineFormatting.toggle(value, format)
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

    /** Whether the line of the caret (of the start of the selection) of [value] is a heading. */
    public fun inHeading(value: TextFieldValue): Boolean {
        if (inCodeBlock(value)) return false
        val text = value.text
        val lineStart = text.lastIndexOf('\n', min(value.selection.start, value.selection.end) - 1) + 1
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        return HEADING_MARKER.containsMatchIn(text.substring(lineStart, lineEnd))
    }

    /** Whether every line of the selection of [value] is a bullet list item. */
    public fun inBulletList(value: TextFieldValue): Boolean = inListLines(value, ordered = false)

    /** Whether every line of the selection of [value] is a numbered list item. */
    public fun inNumberedList(value: TextFieldValue): Boolean = inListLines(value, ordered = true)

    private fun inListLines(value: TextFieldValue, ordered: Boolean): Boolean {
        if (inCodeBlock(value)) return false
        val (firstLineStart, lastLineEnd) = selectedLines(value.text, value.selection)
        val lines = value.text.substring(firstLineStart, lastLineEnd).split('\n')
        return lines.isNotEmpty() && lines.all { line ->
            val rest = line.dropWhile { it == ' ' || it == '\t' }
            if (ordered) ListMarker.at(rest) != null else rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ ")
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
        wholeLevelTurned(value, ordered)?.let { return it }
        val text = value.text
        val sel = value.selection
        val items = RichTextParser.parse(text).filter { it.kind == RichTextParser.Kind.LIST_ITEM }
        // The whole items the selection touches: their continuation lines follow them
        var (firstLineStart, lastLineEnd) = selectedLines(text, sel)
        for (item in items) {
            if (item.start < firstLineStart && item.end >= firstLineStart) firstLineStart = item.start
            if (item.start <= lastLineEnd && item.end > lastLineEnd) lastLineEnd = item.end
        }

        val block = text.substring(firstLineStart, lastLineEnd)
        val lines = block.split('\n')
        // The continuation lines, by the index of the line of their item
        val itemOf = IntArray(lines.size) { -1 }
        var offset = firstLineStart
        var current = -1
        for ((i, line) in lines.withIndex()) {
            val item = items.firstOrNull { it.start < offset && offset <= it.end }
            if (item == null) current = i else itemOf[i] = current
            offset += line.length + 1
        }

        val parsed = lines.mapIndexed { i, line ->
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            val rest = line.substring(indent.length)
            val numbered = ListMarker.at(rest)
            val headMatch = HEADING_MARKER.find(rest)
            val (type, len) = when {
                itemOf[i] >= 0 -> MarkerType.CONTINUATION to 0
                rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ ") -> {
                    var mLen = 2
                    while (mLen < rest.length && rest[mLen].isWhitespace()) mLen++
                    MarkerType.BULLET to mLen
                }
                numbered != null -> MarkerType.NUMBERED to numbered.length
                headMatch != null -> MarkerType.HEADING to headMatch.value.length
                else -> MarkerType.NONE to 0
            }
            LineInfo(indent, type, len, rest.substring(len))
        }

        val heads = parsed.filter { it.markerType != MarkerType.CONTINUATION }
        val allSame = heads.all { it.markerType == if (ordered) MarkerType.NUMBERED else MarkerType.BULLET }

        // The new prefix of each line (its indent and its marker), the old one it replaces
        val newPrefixes = ArrayList<String>(lines.size)
        // Each level counted on its own: by the indent of the lines, a less indented line closing the deeper ones
        val numbers = LinkedHashMap<Int, Int>()
        for ((i, info) in parsed.withIndex()) {
            newPrefixes += when {
                info.markerType == MarkerType.CONTINUATION -> {
                    // At the text of its item as it is now, or at its indent out of the list
                    val head = parsed[itemOf[i]]
                    if (allSame) head.indent else " ".repeat(newPrefixes[itemOf[i]].length)
                }
                allSame -> info.indent
                ordered -> {
                    numbers.keys.removeAll { it > info.indent.length }
                    val number = (numbers[info.indent.length] ?: 0) + 1
                    numbers[info.indent.length] = number
                    info.indent + "$number. "
                }
                else -> info.indent + BULLET_PREFIX
            }
        }
        val oldPrefixLengths = parsed.map { it.indent.length + it.markerLength }
        val newLines = parsed.mapIndexed { i, info -> newPrefixes[i] + info.content }
        val newBlock = newLines.joinToString("\n")

        fun mapOffset(offset: Int): Int {
            if (offset < firstLineStart) return offset
            if (offset > lastLineEnd) return offset + (newBlock.length - block.length)
            var oldPos = firstLineStart
            var newPos = firstLineStart
            for (i in lines.indices) {
                val oldLineEnd = oldPos + lines[i].length
                if (offset <= oldLineEnd) {
                    val inLine = offset - oldPos
                    val oldPrefix = oldPrefixLengths[i]
                    val newPrefix = newPrefixes[i].length
                    val keepsIndent = parsed[i].markerType != MarkerType.CONTINUATION && inLine < parsed[i].indent.length
                    val mapped = when {
                        keepsIndent -> inLine
                        inLine <= oldPrefix -> newPrefix
                        else -> newPrefix + (inLine - oldPrefix)
                    }
                    return (newPos + mapped).coerceIn(newPos, newPos + newLines[i].length)
                }
                oldPos = oldLineEnd + 1
                newPos += newLines[i].length + 1
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
     * With no selection, on an item of the other kind: the items of its level around it turned, as
     * one list (their numbers from the first). `null` anywhere else.
     */
    private fun wholeLevelTurned(value: TextFieldValue, ordered: Boolean): TextFieldValue? {
        if (!value.selection.collapsed) return null
        val text = value.text
        val items = ListNumbering.items(text, RichTextParser.parse(text))
        val caret = value.selection.start
        val item = items.firstOrNull { caret >= it.span.start && caret <= it.span.end } ?: return null
        if (item.span.ordered == ordered) return null
        val level = items.filter { it.list == item.list }.map { it.span }
        return withMarkersOf(value, level) { position -> if (ordered) "${position + 1}. " else BULLET_PREFIX }
    }

    /** [value] with the markers of [items] replaced, its selection following them. */
    private fun withMarkersOf(value: TextFieldValue, items: List<RichTextParser.Span>, marker: (Int) -> String): TextFieldValue {
        val written = ListRenumbering.withMarkers(value.text, items, marker)
        return TextFieldValue(written.text, TextRange(written.map(value.selection.start), written.map(value.selection.end)))
    }

    /**
     * The nested list item [item] of [text] one level out, its marker of the kind of its parent, the
     * caret at its text. The lines nested under it stay under it.
     */
    private fun oneLevelOut(text: String, items: List<ListNumbering.Item>, item: ListNumbering.Item): TextFieldValue {
        val parent = items[item.parent].span
        var parentMarker = parent.start
        while (text[parentMarker] == ' ' || text[parentMarker] == '\t') parentMarker++
        val marker = if (parent.ordered) ListMarker.write(parent.style, parent.number + 1) else text[parentMarker] + " "
        val prefix = text.substring(parent.start, parentMarker) + marker
        return TextFieldValue(
            text.substring(0, item.span.start) + prefix + text.substring(item.span.contentStart),
            TextRange(item.span.start + prefix.length)
        )
    }

    /**
     * Shift+Enter in a list item: a line break inside the item, the next line at its text (a
     * continuation line, see [RichTextParser.Span]), the spaces after the caret dropped. `null` out
     * of the list items (a plain line break) and in a block of code.
     */
    public fun lineBreakInItem(value: TextFieldValue): TextFieldValue? {
        if (inCodeBlock(value)) return null
        val start = min(value.selection.start, value.selection.end)
        val end = max(value.selection.start, value.selection.end)
        val text = value.text.substring(0, start) + value.text.substring(end)
        val item = RichTextParser.parse(text).firstOrNull { it.kind == RichTextParser.Kind.LIST_ITEM && start >= it.contentStart && start <= it.end } ?: return null
        var after = start
        while (after < text.length && (text[after] == ' ' || text[after] == '\t')) after++
        val inserted = "\n" + " ".repeat(ListNumbering.textColumnOf(text, item))
        return TextFieldValue(text.substring(0, start) + inserted + text.substring(after), TextRange(start + inserted.length))
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

        // On a line break inside an item: the next item, the text after the caret in it
        val continued = RichTextParser.parse(text).firstOrNull { it.kind == RichTextParser.Kind.LIST_ITEM && it.start < lineStart && lineStart <= it.end }
        if (continued != null) {
            var markerStart = continued.start
            while (text[markerStart] == ' ' || text[markerStart] == '\t') markerStart++
            val marker = if (continued.ordered) ListMarker.write(continued.style, continued.number + 1) else text[markerStart] + " "
            var after = cursor
            while (after < text.length && (text[after] == ' ' || text[after] == '\t')) after++
            // At the start of the line: the next item takes the whole line
            val before = if (text.substring(lineStart, cursor).isBlank()) text.substring(0, lineStart - 1) else text.substring(0, cursor)
            val prefix = "\n" + text.substring(continued.start, markerStart) + marker
            return TextFieldValue(before + prefix + text.substring(after), TextRange(before.length + prefix.length))
        }

        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val rest = line.substring(indent.length)
        val numbered = ListMarker.at(rest)

        val (markerType, markerLen, numValue, bulletMarker) = when {
            rest.startsWith("- ") -> MarkerInfo(MarkerType.BULLET, 2, 0, "- ")
            rest.startsWith("* ") -> MarkerInfo(MarkerType.BULLET, 2, 0, "* ")
            rest.startsWith("+ ") -> MarkerInfo(MarkerType.BULLET, 2, 0, "+ ")
            numbered != null -> MarkerInfo(MarkerType.NUMBERED, numbered.length, numbered.number, "")
            else -> MarkerInfo(MarkerType.NONE, 0, 0, "")
        }

        if (markerType == MarkerType.NONE) return null

        val markerEnd = indent.length + markerLen
        val content = rest.substring(markerLen)

        // Empty list item: one level out, or out of the list by clearing the marker on this line
        if (content.isBlank()) {
            val items = ListNumbering.items(text, RichTextParser.parse(text))
            items.firstOrNull { it.span.start == lineStart && it.parent >= 0 }?.let { return oneLevelOut(text, items, it) }
            val newText = text.substring(0, lineStart) + text.substring(lineEnd)
            return TextFieldValue(newText, TextRange(lineStart))
        }

        // Caret before the marker: regular newline above the item
        if (cursorInLine < markerEnd) return null

        val beforeCursor = line.substring(0, cursorInLine)
        val afterCursor = line.substring(cursorInLine).trimStart()
        val nextPrefix = if (markerType == MarkerType.NUMBERED) {
            indent + ListMarker.write(numbered?.style, numValue + 1)
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
     * The same for the indent of a continuation line, hidden.
     * `null` when [newValue] needs no change (its text changed, a selection, no marker).
     */
    public fun keepCaretOutOfMarkers(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        if (oldValue.text != newValue.text || !newValue.selection.collapsed) return null
        val caret = newValue.selection.start
        ListNumbering.continuationIndentAt(newValue.text, caret)?.let { indent ->
            if (caret == indent.last) return null
            val backwards = oldValue.selection.collapsed && oldValue.selection.start == indent.last && caret < indent.last
            return newValue.copy(selection = TextRange(if (backwards) indent.first - 1 else indent.last))
        }
        val item = listItemMarkerAt(newValue.text, caret) ?: return null
        val backwards = oldValue.selection.collapsed && oldValue.selection.start == item.contentStart && caret < item.contentStart
        val target = if (backwards && item.start > 0) item.start - 1 else item.contentStart
        return newValue.copy(selection = TextRange(target))
    }

    /**
     * What a deletion of [oldValue] into [newValue] does when it deletes a character that is not
     * shown: Backspace at the start of the text of a list item deletes the last character of its
     * marker, and takes the whole marker away instead (a nested item goes one level out); Backspace
     * at the start of a continuation line
     * of an item deletes a space of its hidden indent, and joins the line to the one before instead;
     * Backspace at the start of the code of a block
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
        ListNumbering.continuationIndentAt(old, caret)?.let { indent ->
            // The line break and the indent of a line break in an item, hidden: the line joined back
            if (caret == indent.last) return TextFieldValue(old.removeRange(indent.first - 1, caret), TextRange(indent.first - 1))
        }
        val item = spans.firstOrNull { it.kind == RichTextParser.Kind.LIST_ITEM && it.contentStart == caret && it.start < caret } ?: return null
        val items = ListNumbering.items(old, spans)
        // A nested item goes one level out; one of the first level becomes text
        items.firstOrNull { it.span.start == item.start && it.parent >= 0 }?.let { return oneLevelOut(old, items, it) }
        return TextFieldValue(old.removeRange(item.start, item.contentStart), TextRange(item.start))
    }

    /**
     * [newValue] with the numbers of the lists the edit from [oldValue] touched made right (see
     * [ListRenumbering]), its selection following them.
     */
    public fun withListsRenumbered(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue {
        val renumbered = ListRenumbering.after(oldValue.text, newValue.text) ?: return newValue
        return TextFieldValue(
            renumbered.text,
            TextRange(renumbered.map(newValue.selection.start), renumbered.map(newValue.selection.end)),
            newValue.composition?.let { TextRange(renumbered.map(it.start), renumbered.map(it.end)) }
        )
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
        if (!inCodeBlock(value)) {
            // An item goes under the sibling before it: the first of a list has none
            val first = firstItemOf(value)
            return if (first != null && !first.second) value else indentLines(value, LIST_INDENT, inCode = false)
        }
        val start = min(value.selection.start, value.selection.end)
        if (start != max(value.selection.start, value.selection.end)) return indentLines(value, CODE_INDENT, inCode = true)
        val text = value.text
        var column = 0
        for (i in text.lastIndexOf('\n', start - 1) + 1 until start) {
            column = if (text[i] == '\t') (column / CODE_INDENT + 1) * CODE_INDENT else column + 1
        }
        val spaces = " ".repeat(CODE_INDENT - column % CODE_INDENT)
        return value.copy(text = text.substring(0, start) + spaces + text.substring(start), selection = TextRange(start + spaces.length))
    }

    /**
     * The first list item the selection of [value] touches, if its first line is one, and whether a
     * sibling comes before it.
     */
    private fun firstItemOf(value: TextFieldValue): Pair<ListNumbering.Item, Boolean>? {
        val text = value.text
        val items = ListNumbering.items(text, RichTextParser.parse(text))
        val (first, _) = selectedLines(text, value.selection)
        val index = items.indexOfFirst { it.span.start <= first && first <= it.span.end }
        if (index < 0) return null
        val item = items[index]
        var k = index - 1
        while (k >= 0 && items[k].run == item.run && items[k].depth > item.depth) k--
        return item to (k >= 0 && items[k].run == item.run && items[k].depth == item.depth)
    }

    /**
     * The lines the selection covers, with the lines of the list items it touches and of the items
     * nested under them (out of the blocks of code): an item moves with what belongs to it.
     */
    private fun selectedItemLines(text: String, selection: TextRange, inCode: Boolean): Pair<Int, Int> {
        var (first, last) = selectedLines(text, selection)
        if (inCode) return first to last
        val spans = RichTextParser.parse(text)
        for (item in spans) {
            if (item.kind != RichTextParser.Kind.LIST_ITEM) continue
            if (item.start < first && item.end >= first) first = item.start
            if (item.start <= last && item.end > last) last = item.end
        }
        val items = ListNumbering.items(text, spans)
        val lastSelected = items.indexOfLast { it.span.start <= last && last <= it.span.end }
        if (lastSelected >= 0) {
            var k = lastSelected + 1
            while (k < items.size && items[k].run == items[lastSelected].run && items[k].depth > items[lastSelected].depth) {
                last = items[k].span.end
                k++
            }
        }
        return first to last
    }

    /** Adds [width] spaces at the beginning of each line covered by the selection. */
    private fun indentLines(value: TextFieldValue, width: Int, inCode: Boolean): TextFieldValue {
        val text = value.text
        val sel = value.selection
        val (firstLineStart, lastLineEnd) = selectedItemLines(text, sel, inCode)
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
    public fun outdent(value: TextFieldValue): TextFieldValue {
        if (inCodeBlock(value)) return outdentLines(value, CODE_INDENT, inCode = true)
        // A nested item goes back to the level of its parent, however far it was written from it
        val item = firstItemOf(value)?.first ?: return outdentLines(value, LIST_INDENT, inCode = false)
        val items = ListNumbering.items(value.text, RichTextParser.parse(value.text))
        val parent = items.firstOrNull { it.span.start == item.span.start }?.parent?.takeIf { it >= 0 }?.let { items[it] }
        val width = ListNumbering.markerColumnOf(value.text, item.span) - (parent?.let { ListNumbering.markerColumnOf(value.text, it.span) } ?: 0)
        return outdentLines(value, width.coerceAtLeast(LIST_INDENT), inCode = false)
    }

    /** Removes up to [width] leading spaces, or a tab, from each line covered by the selection. */
    private fun outdentLines(value: TextFieldValue, width: Int, inCode: Boolean): TextFieldValue {
        val text = value.text
        val sel = value.selection
        val (firstLineStart, lastLineEnd) = selectedItemLines(text, sel, inCode)

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

    private enum class MarkerType { NONE, BULLET, NUMBERED, HEADING, CONTINUATION }

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
