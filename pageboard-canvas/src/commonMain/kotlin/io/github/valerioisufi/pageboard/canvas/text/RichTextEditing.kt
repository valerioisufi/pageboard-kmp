package io.github.valerioisufi.pageboard.canvas.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.model.text.BlockQuote
import io.github.valerioisufi.pageboard.model.text.ListMarker
import io.github.valerioisufi.pageboard.model.text.ListNumbering
import io.github.valerioisufi.pageboard.model.text.ListRenumbering
import io.github.valerioisufi.pageboard.model.text.NumberingStyle
import io.github.valerioisufi.pageboard.model.text.RichTextParser
import io.github.valerioisufi.pageboard.model.text.TaskBox
import io.github.valerioisufi.pageboard.model.text.code.CodeIndent
import kotlin.math.max
import kotlin.math.min

/**
 * The formatting of a text being written, as changes of its notation (see [RichTextParser]): the
 * delimiters around the selection, the heading of a line, the lists, the blocks of code. Inside a
 * block of code the text is code: the formats do not apply, Enter keeps the indentation of the
 * line (one step more after a line opening a block of its language, see [CodeIndent]) and
 * continues no list, Tab moves by the columns of the code.
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
     * The code of the block of code of the selection of [value], without its fences and without the
     * spaces its lines take under an indented fence (a block in a list item), as it is copied
     * alone; `null` when the selection is in no block.
     */
    public fun codeOfBlockAt(value: TextFieldValue): String? {
        val block = codeBlockAt(value) ?: return null
        return codeOf(value.text, block)
    }

    /** The code of [block] of [text]: its lines without the [RichTextParser.Span.level] spaces of its fence. */
    internal fun codeOf(text: String, block: RichTextParser.Span): String {
        val code = text.substring(block.contentStart, block.contentEnd)
        if (block.level == 0) return code
        return code.split('\n').joinToString("\n") { line ->
            line.drop(line.take(block.level).takeWhile { it == ' ' }.length)
        }
    }

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
        return withInfo(value, block) { info -> lineNumbersToggled(info, block.numbered) }
    }

    /** [info] (the information after a fence) asking for the numbers of the lines, or not any more when [numbered]. */
    private fun lineNumbersToggled(info: String, numbered: Boolean): String =
        if (numbered) {
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

    /**
     * The block of code of the selection of [value] numbering its lines from [first] (written
     * `startFrom="10"` after its fence, as Pandoc does; nothing written for 1), its lines numbered
     * if they were not. [first] is kept from 0 to [RichTextParser.MAX_FIRST_LINE].
     */
    public fun setCodeFirstLine(value: TextFieldValue, first: Int): TextFieldValue {
        val block = codeBlockAt(value) ?: return value
        val number = first.coerceIn(0, RichTextParser.MAX_FIRST_LINE)
        return withInfo(value, block) { info ->
            val numbered = if (block.numbered) info else lineNumbersToggled(info, numbered = false)
            val words = numbered.trim().split(' ', '\t').filter { it.isNotEmpty() }
            val kept = words.filterNot { word ->
                '=' in word && word.trimStart('{').substringBefore('=').equals(RichTextParser.START_FROM, ignoreCase = true)
            }
            var written = kept.joinToString(" ")
            // The brace closing the attributes, gone with the attribute it was written after
            if ('{' in written && '}' !in written) written += "}"
            val attribute = "${RichTextParser.START_FROM}=\"$number\""
            val newInfo = when {
                number == 1 -> written
                written.isEmpty() -> attribute
                '{' in written && written.endsWith('}') -> written.dropLast(1).trimEnd() + " " + attribute + "}"
                else -> "$written $attribute"
            }
            if (newInfo.isEmpty()) "" else numbered.takeWhile { it == ' ' || it == '\t' } + newInfo
        }
    }

    /**
     * [value] with the information after the opening fence of [block] (to the end of its line)
     * made [transform] of it, the selection on the same characters (in the information: kept in it).
     */
    private fun withInfo(value: TextFieldValue, block: RichTextParser.Span, transform: (String) -> String): TextFieldValue {
        val text = value.text
        var infoStart = block.start + block.level
        val fence = text[infoStart]
        while (infoStart < text.length && text[infoStart] == fence) infoStart++
        val infoEnd = text.indexOf('\n', infoStart).let { if (it < 0) text.length else it }
        val info = text.substring(infoStart, infoEnd)
        val newInfo = transform(info)
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
            val rest = line.substring(BlockQuote.markerLength(line, 0)).dropWhile { it == ' ' || it == '\t' }
            if (ordered) ListMarker.at(rest) != null else rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ ")
        }
    }

    /** The level of the heading of the line of the caret (of the start of the selection) of [value], 0 for a line that is not one. */
    public fun headingLevelOf(value: TextFieldValue): Int {
        if (inCodeBlock(value)) return 0
        val text = value.text
        val lineStart = text.lastIndexOf('\n', min(value.selection.start, value.selection.end) - 1) + 1
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val marker = HEADING_MARKER.find(text.substring(lineStart, lineEnd)) ?: return 0
        return marker.value.count { it == '#' }
    }

    /**
     * Makes the line of the caret (of the start of the selection) a heading of [level] (1 to 6), or
     * plain text for 0: its `#` and the spaces after them written again, the caret following.
     */
    public fun setHeading(value: TextFieldValue, level: Int): TextFieldValue {
        if (inCodeBlock(value) || level !in 0..6) return value
        val text = value.text
        val sel = value.selection
        val lineStart = text.lastIndexOf('\n', min(sel.start, sel.end) - 1) + 1
        // No heading in a quote, its marker first on the line
        if (BlockQuote.markerLength(text, lineStart) > 0) return value
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val removed = HEADING_MARKER.find(text.substring(lineStart, lineEnd))?.value?.length ?: 0
        val inserted = if (level == 0) "" else "#".repeat(level) + " "
        val shift = inserted.length - removed
        fun moved(offset: Int) = if (offset < lineStart) offset else max(lineStart, offset + shift)
        return value.copy(
            text = text.substring(0, lineStart) + inserted + text.substring(lineStart + removed),
            selection = TextRange(moved(sel.start), moved(sel.end))
        )
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
        // No heading in a quote, its marker first on the line
        if (BlockQuote.markerLength(text, lineStart) > 0) return value
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

    /** The items of a checklist (see [TaskBox]) the lines of the selection of [value] start, out of the blocks of code. */
    private fun selectedTasks(value: TextFieldValue): List<RichTextParser.Span> {
        if (inCodeBlock(value)) return emptyList()
        val (firstLineStart, lastLineEnd) = selectedLines(value.text, value.selection)
        return RichTextParser.parse(value.text).filter {
            it.kind == RichTextParser.Kind.LIST_ITEM && it.checked != null && it.end >= firstLineStart && it.start <= lastLineEnd
        }
    }

    /** Whether every line of the selection of [value] starts an item of a checklist. */
    public fun inChecklist(value: TextFieldValue): Boolean {
        if (inCodeBlock(value)) return false
        val (firstLineStart, lastLineEnd) = selectedLines(value.text, value.selection)
        val starts = selectedTasks(value).map { it.start }.toSet()
        var lineStart = firstLineStart
        while (true) {
            // A line of a quote starts its item after its marker
            if (lineStart !in starts && lineStart + BlockQuote.markerLength(value.text, lineStart) !in starts) return false
            val next = value.text.indexOf('\n', lineStart)
            if (next < 0 || next >= lastLineEnd) return true
            lineStart = next + 1
        }
    }

    /**
     * Makes the lines of the selection items of a checklist, not checked (`- [ ] `), or plain text
     * again if they are all items of a checklist: a bulleted item takes a box, any other line the
     * marker of a bulleted item with its box (in place of its number or its `#`).
     */
    public fun toggleChecklist(value: TextFieldValue): TextFieldValue {
        if (inCodeBlock(value)) return value
        val text = value.text
        val remove = inChecklist(value)
        val (firstLineStart, lastLineEnd) = selectedLines(text, value.selection)
        val items = RichTextParser.parse(text).filter { it.kind == RichTextParser.Kind.LIST_ITEM }
        // Each change: the range replaced and its new text, in the order of the text
        val edits = ArrayList<Triple<Int, Int, String>>()
        val box = TaskBox.UNCHECKED + " "
        var lineStart = firstLineStart
        while (lineStart <= lastLineEnd) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val textStart = lineStart + BlockQuote.markerLength(text, lineStart)
            val item = items.firstOrNull { it.start == textStart || it.start == lineStart }
            val continuation = item == null && items.any { it.start < lineStart && lineStart <= it.end }
            var indentEnd = textStart
            while (indentEnd < lineEnd && (text[indentEnd] == ' ' || text[indentEnd] == '\t')) indentEnd++
            when {
                continuation -> {}
                remove && item != null -> edits += Triple(indentEnd, item.contentStart, "")
                // A bulleted item: the box after its marker and its space
                item != null && !item.ordered && item.checked == null -> edits += Triple(indentEnd + 2, item.contentStart, box)
                item != null && item.checked == null -> edits += Triple(indentEnd, item.contentStart, BULLET_PREFIX + box)
                item == null && !remove -> {
                    val heading = HEADING_MARKER.find(text.substring(indentEnd, lineEnd))?.value?.length ?: 0
                    edits += Triple(indentEnd, indentEnd + heading, BULLET_PREFIX + box)
                }
            }
            if (lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }
        return applied(value, edits)
    }

    /**
     * Checks the items of a checklist the selection of [value] is in, or unchecks them when they
     * are all checked. [value] as it is out of the checklists.
     */
    public fun toggleChecked(value: TextFieldValue): TextFieldValue {
        val tasks = selectedTasks(value)
        if (tasks.isEmpty()) return value
        val check = tasks.any { it.checked == false }
        return withBoxes(value, tasks.filter { it.checked != check }, check)
    }

    /**
     * Checks or unchecks the item of a checklist of [value] starting at [itemStart]; [value] as it
     * is for any other offset.
     */
    public fun toggleCheckedAt(value: TextFieldValue, itemStart: Int): TextFieldValue {
        val item = RichTextParser.parse(value.text).firstOrNull { it.kind == RichTextParser.Kind.LIST_ITEM && it.start == itemStart && it.checked != null }
            ?: return value
        return withBoxes(value, listOf(item), item.checked != true)
    }

    /**
     * Makes the lines of the selection numbered checklist items (`1. [ ] `), or plain text again if
     * they are all numbered checklist items already. Preserves existing checked state.
     */
    public fun toggleNumberedChecklist(value: TextFieldValue): TextFieldValue {
        if (inCodeBlock(value)) return value
        val text = value.text
        val (firstLineStart, lastLineEnd) = selectedLines(text, value.selection)
        val items = RichTextParser.parse(text).filter { it.kind == RichTextParser.Kind.LIST_ITEM }
        val edits = ArrayList<Triple<Int, Int, String>>()
        val selectedStarts = mutableListOf<RichTextParser.Span>()
        var lineStart = firstLineStart
        var nonContinuationAllOrderedTasks = true
        while (lineStart <= lastLineEnd) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val textStart = lineStart + BlockQuote.markerLength(text, lineStart)
            val item = items.firstOrNull { it.start == textStart || it.start == lineStart }
            val continuation = item == null && items.any { it.start < lineStart && lineStart <= it.end }
            if (!continuation) {
                if (item == null || !item.ordered || item.checked == null) nonContinuationAllOrderedTasks = false
                if (item != null) selectedStarts += item
            }
            if (lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }
        val remove = nonContinuationAllOrderedTasks
        val numbers = LinkedHashMap<Int, Int>()
        lineStart = firstLineStart
        while (lineStart <= lastLineEnd) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val textStart = lineStart + BlockQuote.markerLength(text, lineStart)
            val item = items.firstOrNull { it.start == textStart || it.start == lineStart }
            val continuation = item == null && items.any { it.start < lineStart && lineStart <= it.end }
            var indentEnd = textStart
            while (indentEnd < lineEnd && (text[indentEnd] == ' ' || text[indentEnd] == '\t')) indentEnd++
            if (!continuation) {
                if (remove && item != null) {
                    edits += Triple(indentEnd, item.contentStart, "")
                } else {
                    val depth = indentEnd - textStart
                    numbers.keys.removeAll { it > depth }
                    val number = (numbers[depth] ?: 0) + 1
                    numbers[depth] = number
                    val box = TaskBox.write(item?.checked ?: false) + " "
                    val marker = ListMarker.write(item?.style, number) + box
                    if (item != null) {
                        edits += Triple(indentEnd, item.contentStart, marker)
                    } else {
                        val heading = HEADING_MARKER.find(text.substring(indentEnd, lineEnd))?.value?.length ?: 0
                        edits += Triple(indentEnd, indentEnd + heading, marker)
                    }
                }
            }
            if (lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }
        return applied(value, edits)
    }

    /**
     * Completed and total checklist item counts (`(completed, total)`) for the checklist run
     * at the caret/selection of [value] (or across the whole text if the caret is outside a list).
     */
    public fun checklistProgressOf(value: TextFieldValue): Pair<Int, Int> =
        ListNumbering.checklistCountsAt(value.text, min(value.selection.start, value.selection.end))

    /**
     * Moves completed items (`[x]`) to the bottom of their checklist sibling group, keeping
     * nested children, continuation lines, and indented code blocks attached to each item and
     * keeping numbered checklist ordinals sequential.
     *
     * When [onlyItemStart] is non-negative, only the checklist run containing the item starting at
     * [onlyItemStart] is reordered; otherwise if the caret/selection is inside a checklist run, only
     * that run is reordered, else every checklist run in [value] is reordered.
     */
    public fun moveCompletedToEnd(value: TextFieldValue, onlyItemStart: Int? = null): TextFieldValue {
        if ('[' !in value.text) return value
        var curText = value.text
        var selStart = value.selection.start
        var selEnd = value.selection.end
        val initialSpans = RichTextParser.parse(curText)
        val initialItems = ListNumbering.items(curText, initialSpans)
        if (initialItems.none { it.checked != null }) return value
        val targetRun = when {
            onlyItemStart != null -> initialItems.firstOrNull { it.span.start == onlyItemStart }?.run
            else -> initialItems.firstOrNull { min(selStart, selEnd) in it.span.start..it.span.end && it.checked != null }?.run
        }
        val maxDepth = initialItems.maxOfOrNull { it.depth } ?: 0
        for (depth in maxDepth downTo 0) {
            // Each round sorts one group of siblings, never to be taken again: as many rounds at most as items
            for (round in 0..initialItems.size) {
                val spans = RichTextParser.parse(curText)
                val items = ListNumbering.items(curText, spans)
                val codeBlocks = spans.filter { it.kind == RichTextParser.Kind.CODE_BLOCK }
                val group = items.indices
                    .filter { items[it].depth == depth && (targetRun == null || items[it].run == targetRun) }
                    .groupBy { items[it].list }
                    .values
                    .firstOrNull { siblings -> completedLast(siblings, items) != siblings }
                    ?: break
                val reordered = completedLast(group, items)
                val subStarts = IntArray(group.size)
                val subEnds = IntArray(group.size)
                for ((pos, itemIdx) in group.withIndex()) {
                    val head = items[itemIdx]
                    subStarts[pos] = curText.lastIndexOf('\n', head.span.start - 1) + 1
                    var lastIdx = itemIdx
                    while (lastIdx + 1 < items.size && items[lastIdx + 1].run == head.run && items[lastIdx + 1].depth > depth) {
                        lastIdx++
                    }
                    val memberStarts = (itemIdx..lastIdx).map { items[it].span.start }.toSet()
                    var end = items[lastIdx].span.end
                    for (cb in codeBlocks) {
                        if (cb.listItem?.start in memberStarts) end = max(end, cb.end)
                    }
                    subEnds[pos] = end
                }
                val groupStart = subStarts.first()
                val groupEnd = subEnds.last()
                val slotByItem = HashMap<Int, Int>(group.size)
                for ((pos, itemIdx) in group.withIndex()) slotByItem[itemIdx] = pos
                val seps = Array(group.size - 1) { pos -> curText.substring(subEnds[pos], subStarts[pos + 1]) }
                val slices = Array(group.size) { pos -> curText.substring(subStarts[pos], subEnds[pos]) }
                val rebuilt = StringBuilder()
                val newSubStarts = IntArray(group.size)
                for (newPos in reordered.indices) {
                    if (newPos > 0) rebuilt.append(seps[newPos - 1])
                    val oldPos = slotByItem.getValue(reordered[newPos])
                    newSubStarts[newPos] = groupStart + rebuilt.length
                    rebuilt.append(slices[oldPos])
                }
                var groupBlock = rebuilt.toString()
                val firstItem = items[group.first()]
                if (firstItem.span.ordered) {
                    val blockSpans = RichTextParser.parse(groupBlock)
                    val blockItems = ListNumbering.items(groupBlock, blockSpans)
                    val topLevel = blockItems.filter { it.depth == 0 && it.span.ordered }.map { it.span }
                    if (topLevel.size == reordered.size) {
                        val baseStyle = firstItem.style
                        val startOrdinal = firstItem.ordinal
                        val renumbered = ListRenumbering.withMarkers(groupBlock, topLevel) { pos ->
                            val item = topLevel[pos]
                            val box = item.checked?.let { TaskBox.write(it) + " " }.orEmpty()
                            ListMarker.write(item.style ?: baseStyle, startOrdinal + pos) + box
                        }
                        groupBlock = renumbered.text
                    }
                }
                val nextText = curText.substring(0, groupStart) + groupBlock + curText.substring(groupEnd)
                val shiftAfter = groupBlock.length - (groupEnd - groupStart)
                fun mapCaret(offset: Int): Int {
                    if (offset < groupStart) return offset
                    if (offset > groupEnd) return offset + shiftAfter
                    for (oldPos in group.indices) {
                        if (offset in subStarts[oldPos]..subEnds[oldPos]) {
                            val newPos = reordered.indexOf(group[oldPos])
                            val mapped = newSubStarts[newPos] + (offset - subStarts[oldPos])
                            return mapped.coerceIn(groupStart, groupStart + groupBlock.length)
                        }
                    }
                    return (offset + shiftAfter).coerceIn(groupStart, groupStart + groupBlock.length)
                }
                selStart = mapCaret(selStart)
                selEnd = mapCaret(selEnd)
                curText = nextText
            }
        }
        return TextFieldValue(curText, TextRange(selStart, selEnd))
    }

    /** The items [group] of [items] with the ones done after the others, each part in its order. */
    private fun completedLast(group: List<Int>, items: List<ListNumbering.Item>): List<Int> =
        group.filter { items[it].checked != true } + group.filter { items[it].checked == true }

    /** [value] with the boxes of the items of a checklist [items] written [checked]: one character each, the selection where it was. */
    private fun withBoxes(value: TextFieldValue, items: List<RichTextParser.Span>, checked: Boolean): TextFieldValue {
        val chars = value.text.toCharArray()
        for (item in items) {
            var box = item.start
            while (chars[box] != '[') box++
            chars[box + 1] = if (checked) 'x' else ' '
        }
        return value.copy(text = chars.concatToString())
    }

    /** [value] with [edits] (ranges of its text and their new text, not overlapping) applied, its selection following them. */
    private fun applied(value: TextFieldValue, edits: List<Triple<Int, Int, String>>): TextFieldValue {
        if (edits.isEmpty()) return value
        val sorted = edits.sortedBy { it.first }
        val out = StringBuilder()
        var from = 0
        for ((start, end, inserted) in sorted) {
            out.append(value.text, from, start).append(inserted)
            from = end
        }
        out.append(value.text, from, value.text.length)
        fun moved(offset: Int): Int {
            var shift = 0
            for ((start, end, inserted) in sorted) {
                when {
                    offset >= end -> shift += inserted.length - (end - start)
                    offset > start -> return start + shift + inserted.length
                    else -> return offset + shift
                }
            }
            return offset + shift
        }
        return value.copy(text = out.toString(), selection = TextRange(moved(value.selection.start), moved(value.selection.end)))
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
            // The marker of a quote stays first on the line
            val quote = BlockQuote.markerLength(line, 0)
            val indent = line.substring(0, quote) + line.substring(quote).takeWhile { it == ' ' || it == '\t' }
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
                    val quote = BlockQuote.markerLength(head.indent, 0)
                    if (allSame) head.indent else head.indent.substring(0, quote) + " ".repeat(newPrefixes[itemOf[i]].length - quote)
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

    /** The numbered list item at the start of the selection of [value], or `null`. */
    private fun numberedItemOf(value: TextFieldValue): ListNumbering.Item? {
        val caret = min(value.selection.start, value.selection.end)
        return ListNumbering.items(value.text, RichTextParser.parse(value.text))
            .firstOrNull { it.span.ordered && caret >= it.span.start && caret <= it.span.end }
    }

    /** The style of the numbered list at the selection of [value] (`null` for none, or a number by level). */
    public fun numberingStyleOf(value: TextFieldValue): NumberingStyle? = numberedItemOf(value)?.style

    /** The number the numbered list at the selection of [value] starts from (1 out of a list). */
    public fun numberingStartOf(value: TextFieldValue): Int {
        val item = numberedItemOf(value) ?: return 1
        return ListNumbering.items(value.text, RichTextParser.parse(value.text)).first { it.list == item.list }.ordinal
    }

    /**
     * The list at the selection of [value] in [style] from [start]: a numbered list, a bulleted one
     * turning numbered with its level, or the lines of the selection made a list.
     */
    public fun setNumbering(value: TextFieldValue, style: NumberingStyle?, start: Int): TextFieldValue {
        if (inCodeBlock(value)) return value
        val caret = min(value.selection.start, value.selection.end)
        val current = ListNumbering.items(value.text, RichTextParser.parse(value.text))
            .firstOrNull { caret >= it.span.start && caret <= it.span.end }
        val made = if (current != null) value else toggleNumberedList(value)
        val items = ListNumbering.items(made.text, RichTextParser.parse(made.text))
        val madeCaret = min(made.selection.start, made.selection.end)
        val item = items.firstOrNull { madeCaret >= it.span.start && madeCaret <= it.span.end } ?: return value
        val level = items.filter { it.list == item.list }.map { it.span }
        val styled = withMarkersOf(made, level) { position ->
            val box = level[position].checked?.let { TaskBox.write(it) + " " }.orEmpty()
            ListMarker.write(style, start + position) + box
        }
        return if (style?.outline == true) outlinedUnder(styled, item.span.start) else styled
    }

    /**
     * [value] with the numbered lists nested in the list of the item starting at [itemStart]
     * counting by level too (`1.1.`, `1.1.1.`): the numbers by level are chosen for a list and all
     * the numbered lists in it.
     */
    private fun outlinedUnder(value: TextFieldValue, itemStart: Int): TextFieldValue {
        val text = value.text
        val items = ListNumbering.items(text, RichTextParser.parse(text))
        val top = items.firstOrNull { it.span.start == itemStart }?.list ?: return value
        fun under(index: Int): Boolean {
            var parent = items[index].parent
            while (parent >= 0) {
                if (items[parent].list == top) return true
                parent = items[parent].parent
            }
            return false
        }
        val nested = items.indices.filter { items[it].span.ordered && under(it) }
        if (nested.isEmpty()) return value
        return withMarkersOf(value, nested.map { items[it].span }) { position ->
            val index = nested[position]
            val box = items[index].checked?.let { TaskBox.write(it) + " " }.orEmpty()
            ListMarker.write(NumberingStyle.OUTLINE, items[index].ordinal, ListNumbering.outlineParents(items, index)) + box
        }
    }

    /**
     * Chooses the [bullet] of the bulleted list of the caret (`null`: the bullet of its level),
     * making one of the lines there if there is none.
     */
    public fun setBullet(value: TextFieldValue, bullet: ListNumbering.Bullet?): TextFieldValue {
        if (inCodeBlock(value)) return value
        val caret = min(value.selection.start, value.selection.end)
        val current = ListNumbering.items(value.text, RichTextParser.parse(value.text))
            .firstOrNull { caret >= it.span.start && caret <= it.span.end && !it.span.ordered }
        val made = if (current != null) value else toggleBulletList(value)
        val items = ListNumbering.items(made.text, RichTextParser.parse(made.text))
        val madeCaret = min(made.selection.start, made.selection.end)
        val item = items.firstOrNull { madeCaret >= it.span.start && madeCaret <= it.span.end && !it.span.ordered } ?: return value
        val level = items.filter { it.list == item.list }.map { it.span }
        return withMarkersOf(made, level) { position ->
            val box = level[position].checked?.let { TaskBox.write(it) + " " }.orEmpty()
            ListMarker.writeBullet(bullet) + box
        }
    }

    /** The bullet chosen for the bulleted list of the caret of [value], `null` for the bullet of its level or out of such a list. */
    public fun bulletOf(value: TextFieldValue): ListNumbering.Bullet? {
        val caret = min(value.selection.start, value.selection.end)
        return ListNumbering.items(value.text, RichTextParser.parse(value.text))
            .firstOrNull { caret >= it.span.start && caret <= it.span.end && !it.span.ordered }?.chosenBullet
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
        return withMarkersOf(value, level) { position ->
            val box = level[position].checked?.let { TaskBox.write(it) + " " }.orEmpty()
            (if (ordered) "${position + 1}. " else BULLET_PREFIX) + box
        }
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
        val quote = BlockQuote.markerOf(BlockQuote.levelAt(text, BlockQuote.lineStartOf(text, item.start)))
        val inserted = "\n" + quote + " ".repeat(ListNumbering.textColumnOf(text, item))
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
        // The text of a line of a quote starts after its marker, which a new line takes too
        val quoteLength = BlockQuote.markerLength(text, lineStart)
        // At the levels of quotes of its line
        val quote = BlockQuote.markerOf(BlockQuote.levelAt(text, lineStart))
        val textStart = lineStart + quoteLength
        val line = text.substring(textStart, lineEnd)
        val cursorInLine = (cursor - textStart).coerceAtLeast(0)

        // On a line break inside an item: the next item, the text after the caret in it
        val continued = RichTextParser.parse(text).firstOrNull { it.kind == RichTextParser.Kind.LIST_ITEM && it.start < lineStart && lineStart <= it.end }
        if (continued != null) {
            var markerStart = continued.start
            while (text[markerStart] == ' ' || text[markerStart] == '\t') markerStart++
            val box = if (continued.checked != null) TaskBox.UNCHECKED + " " else ""
            val marker = if (continued.ordered) {
                ListMarker.write(continued.style, continued.number + 1, ListMarker.at(text, markerStart)?.parents.orEmpty()) + box
            } else {
                (ListMarker.bulletAt(text, markerStart)?.let { ListMarker.writeBullet(it) } ?: (text[markerStart] + " ")) + box
            }
            var after = cursor
            while (after < text.length && (text[after] == ' ' || text[after] == '\t')) after++
            // At the start of the line: the next item takes the whole line
            val before = if (text.substring(textStart, cursor).isBlank()) text.substring(0, lineStart - 1) else text.substring(0, cursor)
            val prefix = "\n" + quote + text.substring(continued.start, markerStart) + marker
            return TextFieldValue(before + prefix + text.substring(after), TextRange(before.length + prefix.length))
        }

        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val rest = line.substring(indent.length)
        val numbered = ListMarker.at(rest)
        // A bullet chosen: its glyph and the spaces after it
        val chosenBullet = ListMarker.bulletAt(rest)
        val chosenLength = if (chosenBullet == null) 0 else 1 + rest.drop(1).takeWhile { it == ' ' || it == '\t' }.length

        var (markerType, markerLen, numValue, bulletMarker) = when {
            chosenBullet != null -> MarkerInfo(MarkerType.BULLET, chosenLength, 0, ListMarker.writeBullet(chosenBullet))
            rest.startsWith("- ") -> MarkerInfo(MarkerType.BULLET, 2, 0, "- ")
            rest.startsWith("* ") -> MarkerInfo(MarkerType.BULLET, 2, 0, "* ")
            rest.startsWith("+ ") -> MarkerInfo(MarkerType.BULLET, 2, 0, "+ ")
            numbered != null -> MarkerInfo(MarkerType.NUMBERED, numbered.length, numbered.number, "")
            else -> MarkerInfo(MarkerType.NONE, 0, 0, "")
        }

        // The box of an item of a checklist goes with its marker: the next item takes an empty one
        var boxSuffix = ""
        if ((markerType == MarkerType.BULLET || markerType == MarkerType.NUMBERED) && TaskBox.at(rest, markerLen) != null) {
            markerLen += TaskBox.LENGTH
            while (markerLen < rest.length && (rest[markerLen] == ' ' || rest[markerLen] == '\t')) markerLen++
            boxSuffix = TaskBox.UNCHECKED + " "
        }

        if (markerType == MarkerType.NONE) {
            if (quoteLength == 0) return null
            // A line of a quote: an empty one goes one level out (out of it at the first), another goes on in it
            if (line.isBlank()) {
                val outer = BlockQuote.markerOf(BlockQuote.levelAt(text, lineStart) - 1)
                return TextFieldValue(text.substring(0, lineStart) + outer + text.substring(lineEnd), TextRange(lineStart + outer.length))
            }
            val after = text.substring(cursor.coerceAtLeast(textStart)).trimStartSpaces()
            val head = text.substring(0, cursor.coerceAtLeast(textStart)) + "\n" + quote
            return TextFieldValue(head + after, TextRange(head.length))
        }

        val markerEnd = indent.length + markerLen
        val content = rest.substring(markerLen)

        // Empty list item: one level out, or out of the list by clearing the marker on this line
        if (content.isBlank()) {
            val items = ListNumbering.items(text, RichTextParser.parse(text))
            items.firstOrNull { it.span.start == textStart && it.parent >= 0 }?.let { return oneLevelOut(text, items, it) }
            val newText = text.substring(0, textStart) + text.substring(lineEnd)
            return TextFieldValue(newText, TextRange(textStart))
        }

        // Caret before the marker: regular newline above the item
        if (cursorInLine < markerEnd) return null

        val beforeCursor = line.substring(0, cursorInLine)
        val afterCursor = line.substring(cursorInLine).trimStart()
        val nextPrefix = if (markerType == MarkerType.NUMBERED) {
            indent + ListMarker.write(numbered?.style, numValue + 1, numbered?.parents.orEmpty()) + boxSuffix
        } else {
            "$indent$bulletMarker$boxSuffix"
        }

        val newLineBlock = beforeCursor + "\n" + quote + nextPrefix + afterCursor
        val newText = text.substring(0, textStart) + newLineBlock + text.substring(lineEnd)
        val newCursor = textStart + beforeCursor.length + 1 + quote.length + nextPrefix.length
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
        val opener = CodeIndent.openerAtEnd(text.substring(block.contentStart, start), block.language)
            ?: return TextFieldValue(text.substring(0, start) + "\n" + indent + text.substring(start), TextRange(start + 1 + indent.length))
        // After a line opening a block of the language: one step more
        val deeper = indent + CodeIndent.stepOf(text.substring(block.contentStart, block.contentEnd), block.language, block.level)
        val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val rest = text.substring(start, lineEnd)
        val closer = CodeIndent.closerOf(opener)
        val closing = rest.trimStart(' ', '\t')
        if (closer != null && closing.startsWith(closer)) {
            // Between `{` and `}`: the caret on a line of its own, the `}` on the next at the indentation of the `{`
            val after = start + rest.length - closing.length
            return TextFieldValue(text.substring(0, start) + "\n" + deeper + "\n" + indent + text.substring(after), TextRange(start + 1 + deeper.length))
        }
        return TextFieldValue(text.substring(0, start) + "\n" + deeper + text.substring(start), TextRange(start + 1 + deeper.length))
    }

    /**
     * [newValue], when it types a character closing a block of the language of a block of code
     * (`}`, `)` or `]`, see [CodeIndent.closes]) alone on an indented line of [oldValue], with that
     * line one step of the block back, the caret after the character. `null` for any other edit.
     */
    public fun closerTyped(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        val caret = oldValue.selection.start
        if (!oldValue.selection.collapsed || !newValue.selection.collapsed || newValue.selection.start != caret + 1) return null
        val text = newValue.text
        if (text.length != oldValue.text.length + 1 || text.substring(0, caret) != oldValue.text.substring(0, caret)) return null
        if (text.substring(caret + 1) != oldValue.text.substring(caret)) return null
        val typed = text[caret]
        if (typed != '}' && typed != ')' && typed != ']') return null
        val block = codeBlockAt(newValue) ?: return null
        if (caret < block.contentStart || caret >= block.contentEnd || !CodeIndent.closes(typed, block.language)) return null
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val indent = text.substring(lineStart, caret)
        if (indent.isEmpty() || indent.any { it != ' ' && it != '\t' }) return null
        val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        if (text.substring(caret + 1, lineEnd).isNotBlank()) return null
        val step = CodeIndent.stepOf(text.substring(block.contentStart, block.contentEnd), block.language, block.level)
        // The spaces of the fence stay: the block keeps its place in its list item
        if (!indent.endsWith(step) || indent.length - step.length < block.level) return null
        val back = text.substring(0, lineStart) + indent.dropLast(step.length) + text.substring(caret)
        return TextFieldValue(back, TextRange(caret + 1 - step.length))
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
        quoteMarkerAt(newValue.text, caret)?.let { marker ->
            val backwards = oldValue.selection.collapsed && oldValue.selection.start == marker.last && caret < marker.last
            return newValue.copy(selection = TextRange(if (backwards && marker.first > 0) marker.first - 1 else marker.last))
        }
        ListNumbering.continuationIndentAt(newValue.text, caret)?.let { indent ->
            if (caret == indent.last) return null
            val backwards = oldValue.selection.collapsed && oldValue.selection.start == indent.last && caret < indent.last
            return newValue.copy(selection = TextRange(if (backwards) indent.first - 1 else indent.last))
        }
        val item = listItemMarkerAt(newValue.text, caret) ?: return null
        val backwards = oldValue.selection.collapsed && oldValue.selection.start == item.contentStart && caret < item.contentStart
        val lineStart = text0LineStart(newValue.text, item.start)
        val target = if (backwards && lineStart > 0) lineStart - 1 else item.contentStart
        return newValue.copy(selection = TextRange(target))
    }

    /**
     * What a deletion of [oldValue] into [newValue] does when it deletes a character that is not
     * shown: Backspace at the start of the text of a list item deletes the last character of its
     * marker, and takes the whole marker away instead (a nested item goes one level out); at the
     * start of the text of a line of a block quote, it takes the line out of the quote; Backspace
     * at the start of a continuation line
     * of an item deletes a space of its hidden indent, and joins the line to the one before instead;
     * Backspace at the start of the code of a block
     * deletes the line break of its opening fence (hidden while the code is written), and moves the
     * caret onto the fence instead, which shows it; at the start of the text of a heading, whose `#`
     * are not shown, it makes the line text again; right after a formula, drawn whole, it goes into its
     * source (shown in a strip), and at the start of its source out of it. `null` when [newValue] is
     * not such a deletion.
     */
    public fun handleConcealedDeletion(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        val old = oldValue.text
        if (!oldValue.selection.collapsed || newValue.text.length != old.length - 1) return null
        val caret = oldValue.selection.start
        if (caret == 0 || newValue.text != old.removeRange(caret - 1, caret)) return null
        val spans = RichTextParser.parse(old)
        if (spans.any { it.kind == RichTextParser.Kind.CODE_BLOCK && it.contentStart == caret && it.start < caret }) return TextFieldValue(old, TextRange(caret - 1))
        spans.firstOrNull { it.kind == RichTextParser.Kind.HEADING && it.contentStart == caret && it.start < caret }?.let { heading ->
            return TextFieldValue(old.removeRange(heading.start, heading.contentStart), TextRange(heading.start))
        }
        spans.firstOrNull { it.kind == RichTextParser.Kind.FORMULA && (it.end == caret || it.contentStart == caret) }?.let { formula ->
            // Right after a formula: into its source, at its end; at the start of its source: out of it, before it
            return TextFieldValue(old, TextRange(if (formula.end == caret) formula.contentEnd else formula.start))
        }
        ListNumbering.continuationIndentAt(old, caret)?.let { indent ->
            // The line break and the indent of a line break in an item, hidden: the line joined back
            if (caret == indent.last) return TextFieldValue(old.removeRange(indent.first - 1, caret), TextRange(indent.first - 1))
        }
        val item = spans.firstOrNull { it.kind == RichTextParser.Kind.LIST_ITEM && it.contentStart == caret && it.start < caret }
        if (item == null) {
            // At the start of the text of a line of a quote: the line out of it
            val lineStart = text0LineStart(old, caret)
            val marker = BlockQuote.markerLength(old, lineStart)
            if (marker == 0 || caret != lineStart + marker || !inQuoteSpan(old, lineStart)) return null
            return TextFieldValue(old.removeRange(lineStart, caret), TextRange(lineStart))
        }
        val items = ListNumbering.items(old, spans)
        // A nested item goes one level out; one of the first level becomes text
        items.firstOrNull { it.span.start == item.start && it.parent >= 0 }?.let { return oneLevelOut(old, items, it) }
        return TextFieldValue(old.removeRange(item.start, item.contentStart), TextRange(item.start))
    }

    /**
     * The space typed after `a.`, `i.`, `a)`, `i)` or `1)` at the start of a line, of a list in that
     * style: the second space of its marker added (see [ListMarker]). `null` for any other edit. Not
     * the capital letters: `A. Rossi` at the start of a line is a name.
     */
    public fun listFromTypedMarker(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        val caret = newValue.selection.start
        if (!newValue.selection.collapsed || caret < 1 || newValue.text.length != oldValue.text.length + 1) return null
        if (newValue.text[caret - 1] != ' ' || newValue.text != oldValue.text.substring(0, caret - 1) + " " + oldValue.text.substring(caret - 1)) return null
        val lineStart = newValue.text.lastIndexOf('\n', caret - 1) + 1
        val typed = newValue.text.substring(lineStart, caret).trimStart(' ', '\t')
        if (typed !in TYPED_MARKERS || inCodeBlock(oldValue)) return null
        if (RichTextParser.parse(oldValue.text).any { it.kind == RichTextParser.Kind.TABLE && caret - 1 in it.start..it.end }) return null
        return TextFieldValue(newValue.text.substring(0, caret) + " " + newValue.text.substring(caret), TextRange(caret + 1))
    }

    /** The start of the line of [offset] of [text]. */
    private fun text0LineStart(text: String, offset: Int): Int = text.lastIndexOf('\n', offset - 1) + 1

    /**
     * The marker of a line of a block quote [offset] of [text] is in, from the start of the line to the
     * start of its text, or `null`: the caret stays out of it, also at its end when a list item
     * follows (see [listItemMarkerAt]).
     */
    private fun quoteMarkerAt(text: String, offset: Int): IntRange? {
        val lineStart = text0LineStart(text, offset)
        val length = BlockQuote.markerLength(text, lineStart)
        if (length == 0 || offset >= lineStart + length) return null
        if (!inQuoteSpan(text, lineStart)) return null
        return lineStart..lineStart + length
    }

    /** Whether the line starting at [lineStart] of [text] is a line of a block quote: not one of a block of code. */
    private fun inQuoteSpan(text: String, lineStart: Int): Boolean =
        RichTextParser.parse(text).any { it.kind == RichTextParser.Kind.QUOTE && lineStart >= it.start && lineStart <= it.end }

    /** Whether every line of the selection of [value] is a line of a block quote. */
    public fun inQuote(value: TextFieldValue): Boolean {
        if (inCodeBlock(value)) return false
        val (first, last) = selectedLines(value.text, value.selection)
        var lineStart = first
        while (true) {
            if (BlockQuote.markerLength(value.text, lineStart) == 0) return false
            val next = value.text.indexOf('\n', lineStart)
            if (next < 0 || next >= last) return true
            lineStart = next + 1
        }
    }

    /**
     * Makes the lines of the selection of [value] lines of a block quote, or takes them out of it
     * when they all are; a list item with its lines.
     */
    public fun toggleQuote(value: TextFieldValue): TextFieldValue {
        if (inCodeBlock(value)) return value
        val text = value.text
        val removing = inQuote(value)
        val (first, last) = selectedItemLines(text, value.selection, inCode = false)
        val out = StringBuilder(text.length + 8)
        out.append(text, 0, first)
        // Where each line starts, and how much it changed before it
        val starts = ArrayList<Int>()
        val shifts = ArrayList<Int>()
        var lineStart = first
        while (true) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0 || it > last) last else it }
            starts += lineStart
            val length = BlockQuote.markerLength(text, lineStart)
            when {
                removing -> {
                    shifts += -length
                    out.append(text, lineStart + length, lineEnd)
                }
                length > 0 -> {
                    shifts += 0
                    out.append(text, lineStart, lineEnd)
                }
                else -> {
                    shifts += BlockQuote.MARKER.length
                    out.append(BlockQuote.MARKER).append(text, lineStart, lineEnd)
                }
            }
            if (lineEnd >= last) break
            out.append('\n')
            lineStart = lineEnd + 1
        }
        out.append(text, last, text.length)
        fun moved(offset: Int): Int {
            if (offset < first) return offset
            var total = 0
            for ((index, start) in starts.withIndex()) {
                if (offset < start) break
                total += if (shifts[index] < 0) -minOf(-shifts[index], offset - start) else shifts[index]
            }
            return offset + total
        }
        return TextFieldValue(out.toString(), TextRange(moved(value.selection.start), moved(value.selection.end)))
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
        val (lineStart, _) = selectedLines(text, value.selection)
        val first = lineStart + BlockQuote.markerLength(text, lineStart)
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
        // After the marker of a quote, which stays first on the line
        val quotes = lines.map { if (inCode) 0 else BlockQuote.markerLength(it, 0) }
        val newLines = lines.mapIndexed { i, line -> line.substring(0, quotes[i]) + unit + line.substring(quotes[i]) }
        val newBlock = newLines.joinToString("\n")

        fun mapOffset(offset: Int): Int {
            if (offset < firstLineStart) return offset
            if (offset > lastLineEnd) return offset + (newBlock.length - block.length)
            var oldPos = firstLineStart
            var added = 0
            for ((i, line) in lines.withIndex()) {
                val oldLineEnd = oldPos + line.length
                if (offset <= oldLineEnd) {
                    return offset + added + if (offset - oldPos < quotes[i]) 0 else width
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
        // After the marker of a quote, which stays first on the line
        val quotes = lines.map { if (inCode) 0 else BlockQuote.markerLength(it, 0) }
        val removedSpaces = lines.mapIndexed { i, line ->
            val rest = line.substring(quotes[i])
            if (rest.startsWith("\t")) 1 else rest.takeWhile { it == ' ' }.length.coerceAtMost(width)
        }
        val newLines = lines.mapIndexed { i, line -> line.substring(0, quotes[i]) + line.substring(quotes[i] + removedSpaces[i]) }
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
                    val newInLine = if (inLine <= quotes[i]) inLine else (inLine - rem).coerceAtLeast(quotes[i])
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

    /** This text without the spaces and tabs it starts with. */
    private fun String.trimStartSpaces(): String = trimStart(' ', '\t')

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

    /** What typed at the start of a line and followed by a space starts a list in a style. */
    private val TYPED_MARKERS = setOf("a. ", "i. ", "a) ", "i) ", "1) ", "(a) ", "(i) ", "(1) ")
    private const val HEADING_PREFIX = "# "

    /** The spaces of a level of a list, and of a column of the code. */
    private const val LIST_INDENT = 2
    private const val CODE_INDENT = 4

    /** The fewest backticks of a fence. */
    private const val MIN_FENCE = 3

    /** The word asking for the line numbers of a block, as the editor writes it. */
    private const val LINE_NUMBERS_WORD = "showLineNumbers"
}
