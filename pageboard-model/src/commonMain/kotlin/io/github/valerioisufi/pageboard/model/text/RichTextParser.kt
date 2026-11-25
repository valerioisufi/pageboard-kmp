package io.github.valerioisufi.pageboard.model.text

import kotlin.math.min

/**
 * The notation of the texts: Markdown (bold, italic, strikethrough, code, blocks of code,
 * headings, block quotes, see [BlockQuote]) with formulas, `$…$` in the line and `$$…$$` in display style (on a line of their own
 * when nothing else is on their lines), and the escapes `\*` of the punctuation.
 *
 * The rules follow Pandoc for the formulas (a `$` opening a formula is followed by a character
 * other than a space, the one closing it follows such a character and is not followed by a
 * digit: `$5 and $10` is plain text) and CommonMark for the emphasis (the delimiters touch the
 * text they surround; `_` does not work inside a word: `file_name_here` is plain text) and for the
 * fences of the blocks of code. Formatting nests (`**bold *italic***`, a formula in a heading);
 * the code and the formulas are written as they are.
 */
public object RichTextParser {

    /** A kind of [Span]. */
    public enum class Kind { BOLD, ITALIC, STRIKE, CODE, HEADING, FORMULA, ESCAPE, LIST_ITEM, CODE_BLOCK, LINK, IMAGE, TABLE, QUOTE }

    /**
     * A part of a text written with the notation: from [start] to [end] (the delimiters
     * included), its content from [contentStart] to [contentEnd].
     *
     * A list item goes from the start of its line to the end of its last continuation line (see
     * [ListNumbering] for its place in its list). A table goes from the start of its header row to
     * the end of its last row (see [TableShape] for its cells).
     *
     * A block quote goes from the start of its first line to the end of its last, its content
     * from the text of the first; the markers of its other lines are not delimiters. A list item in
     * a quote starts after the marker of its line.
     *
     * A block of code goes from the start of the line of its opening fence to the end of the line
     * of its closing fence (or of the text, when no fence closes it); its content is the lines
     * between them, without the line breaks around it.
     *
     * @property level the level of a heading (the number of its `#`) or of a list item (indentation
     *   depth); for a block of code, the spaces before its opening fence, taken off its lines when
     *   it is drawn.
     * @property display a formula written `$$…$$`, drawn in display style.
     * @property block a display formula alone on its lines: a centered line of its own.
     * @property ordered whether a list item is ordered (numbered).
     * @property number the sequence number of an ordered list item.
     * @property style the style its marker asks for (see [ListMarker]), `null` for a number by level.
     * @property language the language of a block of code, the first word after its opening fence
     *   that is not [numbered] (empty when there is none).
     * @property numbered a block of code asking for the numbers of its lines, by a word after its
     *   opening fence (see [LINE_NUMBERS]).
     * @property listItem the list item a block of code belongs to (its fence indented under the
     *   text of the item), or `null`.
     * @property checked for an item of a checklist (bulleted `- [ ] text` or numbered `1. [ ] text`),
     *   whether it is checked (`true`), unchecked (`false`), or `null` for a plain list item.
     */
    public data class Span(
        val kind: Kind,
        val start: Int,
        val end: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val level: Int = 0,
        val display: Boolean = false,
        val block: Boolean = false,
        val ordered: Boolean = false,
        val number: Int = 0,
        val language: String = "",
        val numbered: Boolean = false,
        val listItem: Span? = null,
        val style: NumberingStyle? = null,
        val checked: Boolean? = null,
        val bullet: ListNumbering.Bullet? = null
    ) {
        /** The delimiters, hidden when the text is drawn: before and after the content. */
        val delimiters: List<IntRange>
            get() = if (kind == Kind.LIST_ITEM) emptyList() else listOf(start until contentStart, contentEnd until end).filterNot { it.isEmpty() }
    }

    /**
     * The spans of [text] sorted by start, an outer span before the ones it contains; spans never
     * overlap partially.
     */
    public fun parse(text: String): List<Span> {
        val verbatim = scanVerbatim(text)
        // The other delimiters are looked for outside the code and the formulas, and the emphasis
        // outside the addresses of the links and the whole of bare autolinks
        val withoutVerbatim = mask(text, verbatim)
        val bracketLinks = scanLinks(withoutVerbatim, text)
        val autolinks = scanAutolinks(withoutVerbatim, text, bracketLinks)
        val links = bracketLinks + autolinks
        val masked = mask(maskDelimiters(withoutVerbatim, bracketLinks), autolinks)
        val candidates = (verbatim + links).toMutableList()
        val quotes = quotes(text, verbatim)
        candidates += quotes
        candidates += nestedQuotes(text, quotes)
        // The patterns of the items read the markers of the quotes as indentation: the offsets stay those of the source
        val unquoted = blanked(masked, quotes)
        candidates += emphasisBetween(verbatim.filter { it.kind == Kind.CODE_BLOCK }, masked, text)
        for (match in HEADING.findAll(masked)) {
            val start = match.range.first
            val end = match.range.last + 1
            var contentStart = start
            while (contentStart < end && text[contentStart] == '#') contentStart++
            val level = contentStart - start
            while (contentStart < end && text[contentStart].isWhitespace()) contentStart++
            if (contentStart < end) candidates += Span(Kind.HEADING, start, end, contentStart, end, level = level)
        }
        // A heading in a quote: after the marker of its line
        for (quote in quotes) headingsInQuote(text, masked, quote, candidates)
        for (match in BULLET_LIST.findAll(unquoted)) {
            val lineStart = match.range.first
            val end = match.range.last + 1
            val start = lineStart + quoteMarkerOf(text, lineStart, quotes)
            val indent = match.groupValues[1]
            val marker = match.groupValues[2]
            val bullet = ListMarker.bulletAt(unquoted, lineStart + indent.length)
            var contentStart = lineStart + indent.length + marker.length
            while (contentStart < end && text[contentStart].isWhitespace()) contentStart++
            val level = indentLevel(indent.substring(start - lineStart))
            // The box of an item of a checklist, and the spaces after it, before its content
            val checked = TaskBox.at(text, contentStart, end)
            if (checked != null) {
                contentStart += TaskBox.LENGTH
                while (contentStart < end && text[contentStart].isWhitespace()) contentStart++
            }
            candidates += Span(Kind.LIST_ITEM, start, end, contentStart, end, level = level, ordered = false, checked = checked, bullet = bullet)
        }
        for (match in NUMBERED_LIST.findAll(unquoted)) {
            val lineStart = match.range.first
            val end = match.range.last + 1
            val start = lineStart + quoteMarkerOf(text, lineStart, quotes)
            val indent = match.groupValues[1]
            val marker = ListMarker.at(unquoted, lineStart + indent.length) ?: continue
            var contentStart = lineStart + indent.length + marker.length
            val checked = TaskBox.at(text, contentStart, end)
            if (checked != null) {
                contentStart += TaskBox.LENGTH
                while (contentStart < end && text[contentStart].isWhitespace()) contentStart++
            }
            candidates += Span(Kind.LIST_ITEM, start, end, contentStart, end, level = indentLevel(indent.substring(start - lineStart)), ordered = true, number = marker.number, style = marker.style, checked = checked)
        }
        withContinuations(text, candidates, verbatim, quotes)
        candidates += tables(text, masked)
        return nest(candidates)
    }

    /**
     * The block quotes of [text]: the runs of lines with the marker of a quote, out of the blocks of
     * code and the formulas ([verbatim]).
     */
    private fun quotes(text: String, verbatim: List<Span>): List<Span> {
        if ('>' !in text) return emptyList()
        val quotes = mutableListOf<Span>()
        var first = -1
        var firstLength = 0
        var lastEnd = 0
        var lineStart = 0
        while (lineStart <= text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val length = if (isIn(verbatim, lineStart)) 0 else BlockQuote.markerLength(text, lineStart, 1)
            if (length > 0) {
                if (first < 0) {
                    first = lineStart
                    firstLength = length
                }
                lastEnd = lineEnd
            } else if (first >= 0) {
                quotes += Span(Kind.QUOTE, first, lastEnd, first + firstLength, lastEnd, level = 1)
                first = -1
            }
            lineStart = lineEnd + 1
        }
        if (first >= 0) quotes += Span(Kind.QUOTE, first, lastEnd, first + firstLength, lastEnd, level = 1)
        return quotes
    }

    /**
     * The quotes in the [quotes] of [text] (`> > text`), each level a span of its own in the one of
     * the level before ([Span.level]); the content of a line of a quote of a level starts after the
     * markers of the levels up to it. The [quotes] themselves are of the first level.
     */
    private fun nestedQuotes(text: String, quotes: List<Span>): List<Span> {
        val nested = mutableListOf<Span>()
        for (quote in quotes) {
            // The lines of the quote with their levels
            val lines = ArrayList<Pair<Int, Int>>()
            var lineStart = quote.start
            var deepest = 1
            while (lineStart <= quote.end) {
                val level = BlockQuote.levelAt(text, lineStart)
                lines += lineStart to level
                if (level > deepest) deepest = level
                val next = text.indexOf('\n', lineStart)
                if (next < 0 || next >= quote.end) break
                lineStart = next + 1
            }
            for (level in 2..deepest) {
                var first = -1
                var lastEnd = 0
                for ((start, lineLevel) in lines) {
                    val lineEnd = text.indexOf('\n', start).let { if (it < 0 || it > quote.end) quote.end else it }
                    if (lineLevel >= level) {
                        if (first < 0) first = start
                        lastEnd = lineEnd
                    } else if (first >= 0) {
                        nested += Span(Kind.QUOTE, first, lastEnd, first + BlockQuote.markerLength(text, first, level), lastEnd, level = level)
                        first = -1
                    }
                }
                if (first >= 0) nested += Span(Kind.QUOTE, first, lastEnd, first + BlockQuote.markerLength(text, first, level), lastEnd, level = level)
            }
        }
        return nested
    }

    /** Adds to [candidates] the headings of the lines of [quote] in [text] ([masked]: its code and formulas hidden). */
    private fun headingsInQuote(text: String, masked: String, quote: Span, candidates: MutableList<Span>) {
        var lineStart = quote.start
        while (lineStart <= quote.end) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val start = lineStart + BlockQuote.markerLength(text, lineStart)
            var hashes = start
            while (hashes < lineEnd && masked[hashes] == '#' && hashes - start < 6) hashes++
            var contentStart = hashes
            if (hashes > start && hashes < lineEnd && (masked[hashes] == ' ' || masked[hashes] == '\t')) {
                while (contentStart < lineEnd && text[contentStart].isWhitespace()) contentStart++
                if (contentStart < lineEnd) candidates += Span(Kind.HEADING, start, lineEnd, contentStart, lineEnd, level = hashes - start)
            }
            if (lineEnd >= quote.end) break
            lineStart = lineEnd + 1
        }
    }

    /** [masked] with the markers of the lines of [quotes] replaced by spaces, the offsets kept. */
    private fun blanked(masked: String, quotes: List<Span>): String {
        if (quotes.isEmpty()) return masked
        val chars = masked.toCharArray()
        for (quote in quotes) {
            var lineStart = quote.start
            while (lineStart <= quote.end) {
                for (i in lineStart until lineStart + BlockQuote.markerLength(masked, lineStart)) chars[i] = ' '
                val next = masked.indexOf('\n', lineStart)
                if (next < 0) break
                lineStart = next + 1
            }
        }
        return chars.concatToString()
    }

    /** The length of the marker of the line starting at [lineStart] of [text] if it is in one of [quotes], else 0. */
    private fun quoteMarkerOf(text: String, lineStart: Int, quotes: List<Span>): Int =
        if (quotes.isNotEmpty() && isInQuote(quotes, lineStart)) BlockQuote.markerLength(text, lineStart) else 0

    /** Whether [offset] is in one of [quotes], its end included (the end of a quote with nothing on its last line). */
    private fun isInQuote(quotes: List<Span>, offset: Int): Boolean {
        var low = 0
        var high = quotes.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val quote = quotes[middle]
            when {
                offset < quote.start -> high = middle - 1
                offset > quote.end -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    /**
     * The tables of [text] (GitHub's notation): a header row with a `|`, a delimiter row of as many
     * cells (`---`, `:--`, `:-:`, `--:`), then the rows with a `|` up to a blank line or a line
     * without one. The `|` are looked for in [masked]: one in code or escaped (`\|`) is text.
     */
    private fun tables(text: String, masked: String): List<Span> {
        val tables = mutableListOf<Span>()
        var lineStart = 0
        while (lineStart < masked.length) {
            val lineEnd = masked.indexOf('\n', lineStart).let { if (it < 0) masked.length else it }
            val nextStart = lineEnd + 1
            if (nextStart < masked.length && hasPipe(masked, lineStart, lineEnd)) {
                val delimiterEnd = masked.indexOf('\n', nextStart).let { if (it < 0) masked.length else it }
                val delimiter = masked.substring(nextStart, delimiterEnd)
                val columns = TableShape.cellsOf(masked, lineStart, lineEnd).size
                if ('|' in delimiter && TABLE_DELIMITER.matches(delimiter) && TableShape.cellsOf(masked, nextStart, delimiterEnd).size == columns) {
                    var end = delimiterEnd
                    while (end < masked.length) {
                        val rowStart = end + 1
                        val rowEnd = masked.indexOf('\n', rowStart).let { if (it < 0) masked.length else it }
                        if (rowStart >= masked.length || masked.substring(rowStart, rowEnd).let { it.isBlank() || '|' !in it }) break
                        end = rowEnd
                    }
                    tables += Span(Kind.TABLE, lineStart, end, lineStart, end)
                    lineStart = end + 1
                    continue
                }
            }
            lineStart = nextStart
        }
        return tables
    }

    /**
     * The list items of [candidates] taking their continuation lines: the lines right after an
     * item (or after one of its continuation lines), not blank, indented at least to the text of
     * the item, that are not an item themselves nor in a block of code ([verbatim]). An item and
     * its continuation lines are one paragraph: a line break inside an item.
     */
    private fun withContinuations(text: String, candidates: MutableList<Span>, verbatim: List<Span>, quotes: List<Span>) {
        val itemStarts = HashSet<Int>()
        for (span in candidates) if (span.kind == Kind.LIST_ITEM) itemStarts += span.start
        val blocks = verbatim.filter { it.kind == Kind.CODE_BLOCK }.sortedBy { it.start }
        for ((index, item) in candidates.withIndex()) {
            if (item.kind != Kind.LIST_ITEM) continue
            val column = columnOf(text, item.start, item.contentStart)
            val quoted = quotes.isNotEmpty() && isInQuote(quotes, item.start)
            var end = item.end
            while (end < text.length) {
                val lineStart = end + 1
                if (lineStart > text.length) break
                // A line of a quote has its indentation after its marker; an item stays on its side of a quote
                val marker = quoteMarkerOf(text, lineStart, quotes)
                if ((marker > 0) != quoted) break
                val textStart = lineStart + marker
                if (textStart in itemStarts || isIn(blocks, lineStart)) break
                val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
                var k = textStart
                while (k < lineEnd && (text[k] == ' ' || text[k] == '\t')) k++
                if (k == lineEnd || columnOf(text, textStart, k) < column) break
                end = lineEnd
            }
            if (end != item.end) candidates[index] = item.copy(end = end, contentEnd = end)
        }
    }

    /** Whether [masked] has a `|` from [start] to [end] (looked for in that line only: linear over the lines). */
    private fun hasPipe(masked: String, start: Int, end: Int): Boolean {
        for (k in start until end) if (masked[k] == '|') return true
        return false
    }

    /** Whether [offset] is in one of [spans], sorted by start and not overlapping. */
    internal fun isIn(spans: List<Span>, offset: Int): Boolean {
        var low = 0
        var high = spans.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val span = spans[middle]
            when {
                offset < span.start -> high = middle - 1
                offset >= span.end -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    /** The column of [offset] in its line starting at [lineStart] of [text], a tab counted as 2. */
    internal fun columnOf(text: String, lineStart: Int, offset: Int): Int {
        var column = 0
        for (k in lineStart until offset) column += if (text[k] == '\t') 2 else 1
        return column
    }

    /**
     * The escapes, the code, the blocks of code and the formulas, scanned from the start: what is
     * inside them is not notation.
     */
    private fun scanVerbatim(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        val inline = InlineClosings(text)
        val items = OpenItems()
        var i = 0
        while (i < text.length) {
            if (i == 0 || text[i - 1] == '\n') {
                items.read(text, i)
                val fence = openingFence(text, i, items.column)
                if (fence != null) {
                    val block = codeBlock(text, i, fence, items.innermost)
                    spans += block
                    i = block.end
                    continue
                }
            }
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> {
                    spans += Span(Kind.ESCAPE, i, i + 2, i + 1, i + 2)
                    i += 2
                    continue
                }
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close > i + 1 && text.substring(i + 1, close).none { it == '\n' }) {
                        spans += Span(Kind.CODE, i, close + 1, i + 1, close)
                        i = close + 1
                        continue
                    }
                }
                c == '$' && text.startsWith("$$", i) -> {
                    val close = closingDisplay(text, i + 2)
                    if (close >= 0) {
                        spans += Span(Kind.FORMULA, i, close + 2, i + 2, close, display = true, block = isAloneOnItsLines(text, i, close + 2))
                        i = close + 2
                        continue
                    }
                    // No closing "$$": the first dollar is plain text, the second may open a formula
                }
                c == '$' -> {
                    val close = inline.closing(i)
                    if (close >= 0) {
                        spans += Span(Kind.FORMULA, i, close + 1, i + 1, close)
                        i = close + 1
                        continue
                    }
                }
            }
            i++
        }
        return spans
    }

    /**
     * Where the `$$` closing a display formula whose content starts at [from] is, or -1; a formula
     * does not go past the opening fence of a block of code.
     */
    private fun closingDisplay(text: String, from: Int): Int {
        var j = from
        while (j < text.length - 1) {
            if (text[j] == '\\') {
                j += 2
                continue
            }
            if (text[j] == '\n' && openingFence(text, j + 1) != null) return -1
            if (text[j] == '$' && text[j + 1] == '$') return if (text.substring(from, j).isBlank()) -1 else j
            j++
        }
        return -1
    }

    /**
     * The list items open at the lines of a text, read line after line: each with the column of its
     * text (the spaces before its marker, its marker and the spaces after it, a tab counted as 2).
     * A line not blank and less indented than the text of an item closes the item (there is no lazy
     * continuation of CommonMark).
     */
    private class OpenItems {
        private val columns = ArrayList<Int>()
        private val items = ArrayList<Span>()

        /** The innermost item open, under which a line is indented. */
        val innermost: Span? get() = items.lastOrNull()

        /** The column of the text of [innermost], or 0. */
        val column: Int get() = columns.lastOrNull() ?: 0

        /** Reads the line starting at [lineStart] of [text]: the items it closes, the item it opens. */
        fun read(text: String, lineStart: Int) {
            var i = lineStart
            var indent = 0
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
                indent += if (text[i] == '\t') 2 else 1
                i++
            }
            if (i == text.length || text[i] == '\n') return
            while (columns.isNotEmpty() && columns.last() > indent) {
                columns.removeAt(columns.lastIndex)
                items.removeAt(items.lastIndex)
            }
            // A marker: "-", "*" or "+" then a space or a tab, a bullet chosen, or a number (see ListMarker)
            val bullet = ListMarker.bulletAt(text, i)
            val numbered = if (text[i] == '-' || text[i] == '*' || text[i] == '+' || bullet != null) null else ListMarker.at(text, i) ?: return
            var marker = i + (numbered?.length ?: 1)
            if (numbered == null) {
                if (text.getOrNull(marker) != ' ' && text.getOrNull(marker) != '\t') return
                while (marker < text.length && (text[marker] == ' ' || text[marker] == '\t')) marker++
            }
            val lineEnd = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            val checked = TaskBox.at(text, marker, lineEnd)
            if (checked != null) {
                marker += TaskBox.LENGTH
                while (marker < lineEnd && (text[marker] == ' ' || text[marker] == '\t')) marker++
            }
            val contentStart = marker
            val column = indent + RichTextParser.columnOf(text, i, contentStart)
            columns += column
            items += Span(Kind.LIST_ITEM, lineStart, lineEnd, contentStart, lineEnd, level = indent / 2, ordered = numbered != null, number = numbered?.number ?: 0, style = numbered?.style, checked = checked, bullet = bullet)
        }
    }

    /**
     * The opening fence of a block of code: its [char] (a backtick or a tilde) repeated [length]
     * times, ending at [end] of the text, under the text of a list item at the column [base] (0
     * out of the lists).
     */
    internal class Fence(val char: Char, val length: Int, val end: Int, val base: Int = 0)

    /**
     * The fence opening a block of code on the line starting at [lineStart] of [text], or `null`:
     * the spaces of the column [base] of the list item it is under (0 out of the lists) and up to 3
     * more, then at least three backticks or tildes; after backticks, no backtick on the rest of
     * the line (it would be code in the line).
     */
    internal fun openingFence(text: String, lineStart: Int, base: Int = 0): Fence? {
        var i = lineStart
        while (i < text.length && i - lineStart < base + MAX_FENCE_INDENT && text[i] == ' ') i++
        if (i - lineStart < base) return null
        val char = text.getOrNull(i) ?: return null
        if (char != '`' && char != '~') return null
        var j = i
        while (j < text.length && text[j] == char) j++
        if (j - i < MIN_FENCE) return null
        if (char == '`') {
            var k = j
            while (k < text.length && text[k] != '\n') {
                if (text[k] == '`') return null
                k++
            }
        }
        return Fence(char, j - i, j, base)
    }

    /**
     * Where the line starting at [lineStart] of [text] ends when it closes [fence] (up to 3 spaces
     * more than the column of its list item, at least as many characters of the fence, then spaces
     * only), or -1.
     */
    private fun closingFenceEnd(text: String, lineStart: Int, fence: Fence): Int {
        var i = lineStart
        while (i < text.length && i - lineStart < fence.base + MAX_FENCE_INDENT && text[i] == ' ') i++
        var j = i
        while (j < text.length && text[j] == fence.char) j++
        if (j - i < fence.length) return -1
        while (j < text.length && text[j] != '\n' && text[j].isSpace()) j++
        return if (j == text.length || text[j] == '\n') j else -1
    }

    /**
     * The block of code opened by [fence] on the line starting at [start] of [text], under the list
     * item [item] if any: to the line closing it, to the line before one less indented than the
     * text of its item, or to the end of the text. Each line is read once.
     */
    private fun codeBlock(text: String, start: Int, fence: Fence, item: Span?): Span {
        val openingEnd = text.indexOf('\n', fence.end).let { if (it < 0) text.length else it }
        // The words of the information, without the braces and the dots of the attributes of Pandoc
        val words = text.substring(fence.end, openingEnd).split(' ', '\t')
            .map { it.trimStart('{', '.').trimEnd('}') }
            .filter { it.isNotEmpty() }
        val numbered = words.any { it.lowercase() in LINE_NUMBERS }
        val language = words.firstOrNull { it.lowercase() !in LINE_NUMBERS }.orEmpty()
        val indent = fence.end - fence.length - start
        val contentStart = min(openingEnd + 1, text.length)
        var line = contentStart
        while (line < text.length) {
            val closingEnd = closingFenceEnd(text, line, fence)
            if (closingEnd >= 0) {
                // The line break before the closing fence belongs to it
                val contentEnd = if (line > contentStart) line - 1 else contentStart
                return Span(Kind.CODE_BLOCK, start, closingEnd, contentStart, contentEnd, level = indent, language = language, numbered = numbered, listItem = item)
            }
            if (fence.base > 0 && isLessIndented(text, line, fence.base)) {
                // The item ends, and its block with it, before the line
                val end = line - 1
                return Span(Kind.CODE_BLOCK, start, end, min(contentStart, end), end, level = indent, language = language, numbered = numbered, listItem = item)
            }
            val next = text.indexOf('\n', line)
            if (next < 0) break
            line = next + 1
        }
        return Span(Kind.CODE_BLOCK, start, text.length, contentStart, text.length, level = indent, language = language, numbered = numbered, listItem = item)
    }

    /** Whether the line starting at [lineStart] of [text] is not blank and indented less than [column] (a tab counted as 2). */
    private fun isLessIndented(text: String, lineStart: Int, column: Int): Boolean {
        var i = lineStart
        var indent = 0
        while (i < text.length && indent < column && (text[i] == ' ' || text[i] == '\t')) {
            indent += if (text[i] == '\t') 2 else 1
            i++
        }
        return indent < column && i < text.length && text[i] != '\n'
    }

    /**
     * The `$` closing the formulas in the line of [text] (Pandoc: a character other than a space
     * after the opening one and before the closing one, no digit after the closing one; on one
     * line), asked for its `$` in the order of the text.
     *
     * The walk from a `$` moves by one character, or by two after a backslash: it never jumps over
     * the character after a later `$`, where the walk from that `$` starts, and from there on they
     * are the same walk. Whether a `$` closes depends on its neighbours only. So the `$` passed by
     * a walk that found no closing `$` have none either, and no part of the text is walked twice.
     */
    internal class InlineClosings(private val text: String) {
        /** The `$` before here close no formula: a walk passed them and found none. */
        private var unclosedBefore = 0

        /** Where the `$` closing the formula opened at [open] is, or -1. */
        fun closing(open: Int): Int {
            if (open < unclosedBefore) return -1
            val first = text.getOrNull(open + 1) ?: return -1
            if (first.isWhitespace() || first == '$') return -1
            var j = open + 1
            while (j < text.length) {
                val c = text[j]
                if (c == '\n') break
                if (c == '\\') {
                    j += 2
                    continue
                }
                if (c == '$') {
                    val closes = !text[j - 1].isWhitespace() && text.getOrNull(j + 1)?.isDigit() != true && text.getOrNull(j + 1) != '$'
                    if (closes) return j
                }
                j++
            }
            unclosedBefore = j
            return -1
        }
    }

    /**
     * Whether only spaces are around [start] .. [end] on their lines: the spaces next to them are
     * walked, up to the line break or another character, not the whole line.
     */
    internal fun isAloneOnItsLines(text: String, start: Int, end: Int): Boolean {
        var before = start - 1
        while (before >= 0 && text[before] != '\n' && text[before].isWhitespace()) before--
        if (before >= 0 && text[before] != '\n') return false
        var after = end
        while (after < text.length && text[after] != '\n' && text[after].isWhitespace()) after++
        return after == text.length || text[after] == '\n'
    }

    /**
     * The emphasis of [text], found in [masked] (its code and formulas masked): `*` and `_` by the
     * CommonMark delimiter-run rules (flanking, nesting, `***x***`, rule of 3) in linear time, and
     * `~~` strikethrough by a linear scan.
     */
    internal fun emphasis(masked: String, text: String): List<Span> {
        val spans = mutableListOf<Span>()
        CommonMarkEmphasis.scan(masked, text, spans)
        STRIKE_EMPHASIS.scan(masked, spans)
        return spans
    }

    /**
     * The emphasis of [text] between its [blocks] of code (sorted): an emphasis does not go from
     * one side of a block to the other, as in CommonMark, where the blocks come before the
     * emphasis of the paragraphs around them.
     */
    private fun emphasisBetween(blocks: List<Span>, masked: String, text: String): List<Span> {
        if (blocks.isEmpty()) return emphasis(masked, text)
        val spans = mutableListOf<Span>()
        var from = 0
        for (index in 0..blocks.size) {
            val to = blocks.getOrNull(index)?.start ?: text.length
            if (to > from) {
                for (span in emphasis(masked.substring(from, to), text.substring(from, to))) {
                    spans += span.copy(start = span.start + from, end = span.end + from, contentStart = span.contentStart + from, contentEnd = span.contentEnd + from)
                }
            }
            from = blocks.getOrNull(index)?.end ?: break
        }
        return spans
    }

    /** [text] with the characters of [spans] replaced, the offsets kept. */
    private fun mask(text: String, spans: List<Span>): String {
        if (spans.isEmpty()) return text
        val chars = text.toCharArray()
        for (span in spans) for (i in span.start until span.end) chars[i] = MASK
        return chars.concatToString()
    }

    /** [text] with the delimiters of [spans] replaced, their content kept. */
    private fun maskDelimiters(text: String, spans: List<Span>): String {
        if (spans.isEmpty()) return text
        val chars = text.toCharArray()
        for (span in spans) for (range in span.delimiters) for (i in range) chars[i] = MASK
        return chars.concatToString()
    }

    /** The address of a [Kind.LINK] [span] of [text] (its own range when an autolink). */
    public fun linkUrl(text: String, span: Span): String =
        if (span.contentEnd == span.end) text.substring(span.start, span.end)
        else text.substring(span.contentEnd + 2, span.end - 1)

    /** The address of a [Kind.IMAGE] [span] of [text]. */
    public fun imageUrl(text: String, span: Span): String = text.substring(span.contentEnd + 2, span.end - 1)

    /**
     * [raw] normalized if it is an openable web or mail address (`http`, `https`, `mailto`, or
     * `www.` normalized to `https://`), or `null` otherwise.
     */
    public fun openableUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isSpace() }) return null
        val normalized = if (trimmed.startsWith("www.", ignoreCase = true)) "https://$trimmed" else trimmed
        return when {
            normalized.startsWith("https://", ignoreCase = true) && normalized.length > 8 ->
                "https://" + normalized.substring(8)
            normalized.startsWith("http://", ignoreCase = true) && normalized.length > 7 ->
                "http://" + normalized.substring(7)
            normalized.startsWith("mailto:", ignoreCase = true) && normalized.indexOf('@', 7) > 7 ->
                "mailto:" + normalized.substring(7)
            else -> null
        }
    }

    /** The openable address of a [Kind.LINK] or [Kind.IMAGE] [span] of [text], or `null`. */
    public fun openableLinkUrl(text: String, span: Span): String? = when (span.kind) {
        Kind.LINK -> openableUrl(linkUrl(text, span))
        Kind.IMAGE -> openableUrl(imageUrl(text, span))
        else -> null
    }

    /**
     * The `[text](url)` links and `![alt](url)` images in [masked] (code, formulas and escapes
     * masked): on a single line, the address without spaces and with balanced parentheses; a link's
     * text is non-empty, an image's alt text may be empty.
     */
    private fun scanLinks(masked: String, text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var i = 0
        var open = -1
        var isImage = false
        while (i < masked.length) {
            val c = masked[i]
            when {
                text[i] == '\n' -> {
                    open = -1
                    isImage = false
                }
                c == '[' -> {
                    if (masked.getOrNull(i - 1) == '!') {
                        open = i - 1
                        isImage = true
                    } else {
                        open = i
                        isImage = false
                    }
                }
                c == ']' && open >= 0 && masked.getOrNull(i + 1) == '(' -> {
                    val contentStart = open + if (isImage) 2 else 1
                    if (isImage || i > contentStart) {
                        val end = closingLinkUrl(masked, text, i + 2)
                        if (end > 0) {
                            spans += Span(if (isImage) Kind.IMAGE else Kind.LINK, open, end, contentStart, i)
                            open = -1
                            isImage = false
                            i = end
                            continue
                        }
                    }
                    open = -1
                    isImage = false
                }
            }
            i++
        }
        return spans
    }

    /**
     * Bare web and mail URLs (`https://`, `http://`, `mailto:`, `www.`) in [masked], outside
     * verbatim spans and [existingLinks], with trailing sentence punctuation and unmatched closing
     * delimiters trimmed.
     */
    private fun scanAutolinks(masked: String, text: String, existingLinks: List<Span>): List<Span> {
        val spans = mutableListOf<Span>()
        var linkIndex = 0
        var i = 0
        while (i < masked.length) {
            while (linkIndex < existingLinks.size && existingLinks[linkIndex].end <= i) linkIndex++
            val currentLink = existingLinks.getOrNull(linkIndex)
            if (currentLink != null && i >= currentLink.start) {
                i = currentLink.end
                continue
            }
            val prev = masked.getOrNull(i - 1)
            val atBoundary = prev == null || prev == MASK || prev.isSpace() || prev in AUTOLINK_BEFORE
            if (!atBoundary) {
                i++
                continue
            }
            val prefix = when {
                masked.startsWith("https://", i, ignoreCase = true) -> "https://"
                masked.startsWith("http://", i, ignoreCase = true) -> "http://"
                masked.startsWith("mailto:", i, ignoreCase = true) -> "mailto:"
                masked.startsWith("www.", i, ignoreCase = true) -> "www."
                else -> null
            }
            if (prefix == null) {
                i++
                continue
            }
            val nextExistingStart = currentLink?.start ?: masked.length
            var end = i + prefix.length
            while (end < nextExistingStart && masked[end] != MASK && !text[end].isSpace() && masked[end] != '<') end++
            end = trimAutolinkEnd(masked, i, i + prefix.length, end)
            if (isValidAutolink(text, i, prefix, end)) {
                spans += Span(Kind.LINK, i, end, i, end)
                i = end
            } else {
                i += prefix.length
            }
        }
        return spans
    }

    private fun trimAutolinkEnd(masked: String, start: Int, minEnd: Int, rawEnd: Int): Int {
        var end = rawEnd
        while (end > minEnd) {
            val last = masked[end - 1]
            when (last) {
                in AUTOLINK_TRAILING -> end--
                ')', ']', '}' -> {
                    val opener = when (last) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    var balance = 0
                    for (k in start until end) {
                        when (masked[k]) {
                            opener -> balance++
                            last -> balance--
                        }
                    }
                    if (balance < 0) end-- else break
                }
                else -> break
            }
        }
        return end
    }

    private fun isValidAutolink(text: String, start: Int, prefix: String, end: Int): Boolean {
        val bodyStart = start + prefix.length
        if (end <= bodyStart) return false
        return when {
            prefix.equals("mailto:", ignoreCase = true) -> {
                val at = text.indexOf('@', bodyStart)
                at > bodyStart && at + 1 < end && text.indexOf('.', at + 1).let { it in (at + 2) until (end - 1) }
            }
            prefix.equals("www.", ignoreCase = true) -> {
                val dot = text.indexOf('.', bodyStart)
                dot > bodyStart && dot + 1 < end
            }
            else -> text[bodyStart].let { it.isLetterOrDigit() }
        }
    }

    /** The offset after the `)` closing a link address starting at [urlStart], or -1. */
    private fun closingLinkUrl(masked: String, text: String, urlStart: Int): Int {
        var depth = 0
        var k = urlStart
        while (k < masked.length) {
            if (text[k] == '\n' || text[k].isSpace()) return -1
            when (masked[k]) {
                '(' -> depth++
                ')' -> if (depth == 0) return if (k > urlStart) k + 1 else -1 else depth--
            }
            k++
        }
        return -1
    }

    /**
     * The spans that nest, sorted: an outer one first; a span overlapping one kept partially, or
     * inside a delimiter, code or formula, is dropped. The [candidates] are not empty.
     *
     * The spans kept and still open where a span starts nest in each other, each in the content of
     * the one before it: a span in the content of the innermost one is in the content of all of
     * them, so it is checked against that one only.
     */
    internal fun nest(candidates: List<Span>): List<Span> {
        // By start, then by end backwards: an outer span before the ones it contains
        val sorted = candidates.sortedWith { a, b -> if (a.start != b.start) a.start.compareTo(b.start) else b.end.compareTo(a.end) }
        val kept = mutableListOf<Span>()
        // The spans kept and still open, the innermost last
        val open = mutableListOf<Span>()
        for (span in sorted) {
            while (open.isNotEmpty() && open.last().end <= span.start) open.removeAt(open.lastIndex)
            val outer = open.lastOrNull()
            val fits = outer == null ||
                // Inside the content of a span whose content is notation
                (outer.kind != Kind.CODE && outer.kind != Kind.CODE_BLOCK && outer.kind != Kind.FORMULA && outer.kind != Kind.ESCAPE &&
                    span.start >= outer.contentStart && span.end <= outer.contentEnd)
            if (fits) {
                kept += span
                open += span
            }
        }
        return kept
    }

    /**
     * Linear-time CommonMark delimiter-run emphasis (`*` and `_`): handles flanking around spaces
     * and punctuation, nested runs such as `***x***`, intraword `_`, and the multiple-of-3 rule.
     */
    private object CommonMarkEmphasis {
        private class DelimiterRun(
            val char: Char,
            var start: Int,
            var length: Int,
            val origLength: Int,
            val canOpen: Boolean,
            val canClose: Boolean,
            var prev: Int,
            var next: Int,
            var alive: Boolean = true
        )

        fun scan(masked: String, text: String, spans: MutableList<Span>) {
            val runs = ArrayList<DelimiterRun>()
            var i = 0
            while (i < masked.length) {
                val c = masked[i]
                if (c == '*' || c == '_') {
                    val start = i
                    while (i < masked.length && masked[i] == c) i++
                    val length = i - start
                    val before = text.getOrNull(start - 1)
                    val after = text.getOrNull(i)
                    val beforeWs = before == null || before.isWhitespace()
                    val afterWs = after == null || after.isWhitespace()
                    val beforePunct = before != null && isPunctuation(before)
                    val afterPunct = after != null && isPunctuation(after)
                    val leftFlanking = !afterWs && (!afterPunct || beforeWs || beforePunct)
                    val rightFlanking = !beforeWs && (!beforePunct || afterWs || afterPunct)
                    val canOpen = if (c == '*') leftFlanking else leftFlanking && (!rightFlanking || beforePunct)
                    val canClose = if (c == '*') rightFlanking else rightFlanking && (!leftFlanking || afterPunct)
                    if (canOpen || canClose) {
                        val idx = runs.size
                        runs += DelimiterRun(c, start, length, length, canOpen, canClose, prev = idx - 1, next = idx + 1)
                    }
                } else {
                    i++
                }
            }
            if (runs.isEmpty()) return
            runs.last().next = -1

            var firstRun = 0
            fun unlink(idx: Int) {
                val run = runs[idx]
                if (!run.alive) return
                run.alive = false
                if (run.prev != -1) runs[run.prev].next = run.next else firstRun = run.next
                if (run.next != -1) runs[run.next].prev = run.prev
            }

            // Monotonic lower-bound start offset per (char, closerOrigLength % 3, closerCanOpen) so no
            // unmatched opener is ever re-scanned even if intermediate delimiter runs are unlinked.
            val openersBottom = IntArray(12) { -1 }
            var closerIdx = firstRun
            while (closerIdx != -1) {
                val closer = runs[closerIdx]
                if (!closer.alive || !closer.canClose || closer.length == 0) {
                    closerIdx = closer.next
                    continue
                }
                val key = (if (closer.char == '_') 6 else 0) + (closer.origLength % 3) * 2 + (if (closer.canOpen) 1 else 0)
                val bottomStart = openersBottom[key]
                var openerIdx = closer.prev
                var found = -1
                while (openerIdx != -1) {
                    val opener = runs[openerIdx]
                    if (opener.start <= bottomStart) break
                    if (opener.alive && opener.length > 0 && opener.canOpen && opener.char == closer.char) {
                        val ruleOf3Blocked = (opener.canClose || closer.canOpen) &&
                            (opener.origLength + closer.origLength) % 3 == 0 &&
                            (opener.origLength % 3 != 0 || closer.origLength % 3 != 0)
                        if (!ruleOf3Blocked) {
                            found = openerIdx
                            break
                        }
                    }
                    openerIdx = opener.prev
                }
                if (found == -1) {
                    openersBottom[key] = if (closer.prev != -1) runs[closer.prev].start else -1
                    val next = closer.next
                    if (!closer.canOpen) unlink(closerIdx)
                    closerIdx = next
                } else {
                    val opener = runs[found]
                    val use = if (opener.length >= 2 && closer.length >= 2) 2 else 1
                    val kind = if (use == 2) Kind.BOLD else Kind.ITALIC
                    val spanStart = opener.start + opener.length - use
                    val spanEnd = closer.start + use
                    spans += Span(kind, spanStart, spanEnd, spanStart + use, closer.start)
                    // Drop unmatched runs strictly between opener and closer
                    var between = opener.next
                    while (between != -1 && between != closerIdx) {
                        val nextBetween = runs[between].next
                        unlink(between)
                        between = nextBetween
                    }
                    opener.length -= use
                    closer.start += use
                    closer.length -= use
                    if (opener.length == 0) unlink(found)
                    if (closer.length == 0) {
                        val next = closer.next
                        unlink(closerIdx)
                        closerIdx = next
                    }
                }
            }
        }

        private fun isPunctuation(c: Char): Boolean = c in ASCII_PUNCTUATION || when (c.category) {
            CharCategory.DASH_PUNCTUATION,
            CharCategory.START_PUNCTUATION,
            CharCategory.END_PUNCTUATION,
            CharCategory.CONNECTOR_PUNCTUATION,
            CharCategory.OTHER_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION,
            CharCategory.FINAL_QUOTE_PUNCTUATION,
            CharCategory.MATH_SYMBOL,
            CharCategory.CURRENCY_SYMBOL,
            CharCategory.MODIFIER_SYMBOL,
            CharCategory.OTHER_SYMBOL -> true
            else -> false
        }

        private const val ASCII_PUNCTUATION = "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    }

    /**
     * A delimiter pair scanned linearly from the start of the text (`~~` strikethrough): each from
     * a delimiter that opens to the first one closing after a character of content.
     */
    private class Emphasis(val kind: Kind, val delimiter: String) {
        private val char = delimiter[0]

        fun opensAt(masked: String, i: Int): Boolean {
            if (!masked.startsWith(delimiter, i)) return false
            val next = masked.getOrNull(i + delimiter.length) ?: return false
            return !next.isSpace()
        }

        fun closesAt(masked: String, i: Int): Boolean {
            if (!masked.startsWith(delimiter, i)) return false
            val previous = masked.getOrNull(i - 1) ?: return false
            return !previous.isSpace()
        }

        fun scan(masked: String, spans: MutableList<Span>) {
            val length = delimiter.length
            var open = masked.indexOf(char)
            while (open >= 0) {
                if (!opensAt(masked, open)) {
                    open = masked.indexOf(char, open + 1)
                    continue
                }
                var close = masked.indexOf(char, open + length + 1)
                while (close >= 0 && !closesAt(masked, close)) close = masked.indexOf(char, close + 1)
                if (close < 0) return
                val end = close + length
                spans += Span(kind, open, end, open + length, close)
                open = masked.indexOf(char, end)
            }
        }
    }

    private val STRIKE_EMPHASIS = Emphasis(Kind.STRIKE, "~~")

    /** A space around the delimiters of the emphasis: the spaces of ASCII. */
    private fun Char.isSpace() = this == ' ' || this in '\t'..'\r'

    // Inline flags such as (?m) are not supported everywhere
    private val HEADING = Regex("""^#{1,6}[ \t]+\S.*$""", RegexOption.MULTILINE)
    private val BULLET_LIST = Regex("""^([ \t]*)([-*+]|[•◦▪–](?=  ))[ \t]+(.*)$""", RegexOption.MULTILINE)
    private val NUMBERED_LIST = Regex("""^([ \t]*)(?:\([0-9A-Za-z]+\)|[0-9A-Za-z]+[.)](?:[0-9]+\.)*)[ \t]+(.*)$""", RegexOption.MULTILINE)
    private val TABLE_DELIMITER = Regex("""^ {0,3}\|?[ \t]*:?-+:?[ \t]*(\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")

    private fun indentLevel(indent: String): Int {
        var spaces = 0
        for (c in indent) spaces += if (c == '\t') 2 else 1
        return spaces / 2
    }

    /** The punctuation an escape writes as it is. */
    private const val ESCAPABLE = """\`*_{}[]()#+-.!${'$'}~|<>"""

    /**
     * The words after a fence asking for the numbers of the lines of its block, in lowercase:
     * `showLineNumbers` (written by the editor), `numberLines` of Pandoc (also `.numberLines`,
     * `{.numberLines}`), `linenums`.
     */
    public val LINE_NUMBERS: Set<String> = setOf("showlinenumbers", "numberlines", "linenums")

    /** The fewest backticks or tildes of a fence, and the most spaces before it. */
    private const val MIN_FENCE = 3
    private const val MAX_FENCE_INDENT = 3

    private const val AUTOLINK_BEFORE = "(<[{\"'*_~"
    private const val AUTOLINK_TRAILING = ".,;:!?'\"*_~>"

    private const val MASK = ''
}
