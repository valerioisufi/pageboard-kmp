package io.github.valerioisufi.drawview.model.text

import kotlin.math.min

/**
 * The notation of the texts: Markdown (bold, italic, strikethrough, code, blocks of code,
 * headings) with formulas, `$…$` in the line and `$$…$$` in display style (on a line of their own
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
    public enum class Kind { BOLD, ITALIC, STRIKE, CODE, HEADING, FORMULA, ESCAPE, LIST_ITEM, CODE_BLOCK, LINK }

    /**
     * A part of a text written with the notation: from [start] to [end] (the delimiters
     * included), its content from [contentStart] to [contentEnd].
     *
     * A list item goes from the start of its line to the end of its last continuation line (see
     * [ListNumbering] for its place in its list).
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
     * @property language the language of a block of code, the first word after its opening fence
     *   that is not [numbered] (empty when there is none).
     * @property numbered a block of code asking for the numbers of its lines, by a word after its
     *   opening fence (see [LINE_NUMBERS]).
     * @property listItem the list item a block of code belongs to (its fence indented under the
     *   text of the item), or `null`.
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
        val listItem: Span? = null
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
        // outside the addresses of the links
        val withoutVerbatim = mask(text, verbatim)
        val links = scanLinks(withoutVerbatim, text)
        val masked = maskDelimiters(withoutVerbatim, links)
        val candidates = (verbatim + links).toMutableList()
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
        for (match in BULLET_LIST.findAll(masked)) {
            val start = match.range.first
            val end = match.range.last + 1
            val indent = match.groupValues[1]
            val marker = match.groupValues[2]
            var contentStart = start + indent.length + marker.length
            while (contentStart < end && text[contentStart].isWhitespace()) contentStart++
            val level = indentLevel(indent)
            candidates += Span(Kind.LIST_ITEM, start, end, contentStart, end, level = level, ordered = false)
        }
        for (match in NUMBERED_LIST.findAll(masked)) {
            val start = match.range.first
            val end = match.range.last + 1
            val indent = match.groupValues[1]
            val numberStr = match.groupValues[2]
            var contentStart = start + indent.length + numberStr.length + 1
            while (contentStart < end && text[contentStart].isWhitespace()) contentStart++
            val level = indentLevel(indent)
            val number = numberStr.toIntOrNull() ?: 1
            candidates += Span(Kind.LIST_ITEM, start, end, contentStart, end, level = level, ordered = true, number = number)
        }
        withContinuations(text, candidates, verbatim)
        return nest(candidates)
    }

    /**
     * The list items of [candidates] taking their continuation lines: the lines right after an
     * item (or after one of its continuation lines), not blank, indented at least to the text of
     * the item, that are not an item themselves nor in a block of code ([verbatim]). An item and
     * its continuation lines are one paragraph: a line break inside an item.
     */
    private fun withContinuations(text: String, candidates: MutableList<Span>, verbatim: List<Span>) {
        val itemStarts = HashSet<Int>()
        for (span in candidates) if (span.kind == Kind.LIST_ITEM) itemStarts += span.start
        val blocks = verbatim.filter { it.kind == Kind.CODE_BLOCK }.sortedBy { it.start }
        for ((index, item) in candidates.withIndex()) {
            if (item.kind != Kind.LIST_ITEM) continue
            val column = columnOf(text, item.start, item.contentStart)
            var end = item.end
            while (end < text.length) {
                val lineStart = end + 1
                if (lineStart > text.length || lineStart in itemStarts || isIn(blocks, lineStart)) break
                val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
                var k = lineStart
                while (k < lineEnd && (text[k] == ' ' || text[k] == '\t')) k++
                if (k == lineEnd || columnOf(text, lineStart, k) < column) break
                end = lineEnd
            }
            if (end != item.end) candidates[index] = item.copy(end = end, contentEnd = end)
        }
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
            // A marker: "-", "*" or "+", or digits and ".", then a space or a tab
            var marker = i
            val ordered = text[i].isDigit()
            if (ordered) {
                while (marker < text.length && text[marker].isDigit()) marker++
                if (text.getOrNull(marker) != '.') return
            } else if (text[i] != '-' && text[i] != '*' && text[i] != '+') {
                return
            }
            marker++
            if (text.getOrNull(marker) != ' ' && text.getOrNull(marker) != '\t') return
            var contentStart = marker
            var column = indent + (marker - i)
            while (contentStart < text.length && (text[contentStart] == ' ' || text[contentStart] == '\t')) {
                column += if (text[contentStart] == '\t') 2 else 1
                contentStart++
            }
            val lineEnd = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            val number = if (ordered) text.substring(i, marker - 1).toIntOrNull() ?: 1 else 0
            columns += column
            items += Span(Kind.LIST_ITEM, lineStart, lineEnd, contentStart, lineEnd, level = indent / 2, ordered = ordered, number = number)
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
     * The emphasis of [text], found in [masked] (its code and formulas masked) rule after rule, each
     * rule from the start of the text.
     */
    internal fun emphasis(masked: String, text: String): List<Span> {
        val spans = mutableListOf<Span>()
        for (rule in EMPHASIS) rule.scan(masked, text, spans)
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

    /** The address of a [Kind.LINK] [span] of [text]. */
    public fun linkUrl(text: String, span: Span): String = text.substring(span.contentEnd + 2, span.end - 1)

    /**
     * The `[text](url)` links in [masked] (code, formulas and escapes masked): on a single line,
     * neither the text nor the address empty, the address without spaces and with balanced
     * parentheses; `![image](url)` is left out.
     */
    private fun scanLinks(masked: String, text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var i = 0
        var open = -1
        while (i < masked.length) {
            val c = masked[i]
            when {
                text[i] == '\n' -> open = -1
                c == '[' && masked.getOrNull(i - 1) != '!' -> open = i
                c == ']' && open >= 0 && i > open + 1 && masked.getOrNull(i + 1) == '(' -> {
                    val end = closingLinkUrl(masked, text, i + 2)
                    if (end > 0) {
                        spans += Span(Kind.LINK, open, end, open + 1, i)
                        open = -1
                        i = end
                        continue
                    }
                    open = -1
                }
            }
            i++
        }
        return spans
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

    private fun String.isWordAt(index: Int) = getOrNull(index)?.isLetterOrDigit() == true

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
     * A rule of emphasis: its [delimiter] around a content touching it; a rule bound to words
     * ([wordBound]) does not work inside a word.
     */
    private class Emphasis(val kind: Kind, val delimiter: String, val wordBound: Boolean = false) {
        private val char = delimiter[0]
        private val single = delimiter.length == 1

        /**
         * Whether a delimiter opening a span is at [i] of [masked]: a character other than a space
         * follows it; a single one neither follows its character nor is followed by it.
         */
        fun opensAt(masked: String, i: Int): Boolean {
            if (!masked.startsWith(delimiter, i)) return false
            val next = masked.getOrNull(i + delimiter.length) ?: return false
            return !next.isSpace() && (!single || (next != char && masked.getOrNull(i - 1) != char))
        }

        /**
         * Whether a delimiter closing a span is at [i] of [masked]: it follows a character other
         * than a space; a single one neither follows its character nor is followed by it.
         */
        fun closesAt(masked: String, i: Int): Boolean {
            if (!masked.startsWith(delimiter, i)) return false
            val previous = masked.getOrNull(i - 1) ?: return false
            return !previous.isSpace() && (!single || (previous != char && masked.getOrNull(i + 1) != char))
        }

        /**
         * Adds to [spans] the spans of the rule in [masked], from the start: each from a delimiter
         * that opens to the first one closing after a character of content, the next one after it.
         *
         * Whether a delimiter closes does not depend on where its span opened: when none closes
         * after a delimiter that opens, none closes after the later ones either. So each character
         * is read once looking for a delimiter that opens and once for one that closes.
         */
        fun scan(masked: String, text: String, spans: MutableList<Span>) {
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
                // A span touching a word is dropped, its text taken all the same
                if (!wordBound || !(text.isWordAt(open - 1) || text.isWordAt(end))) {
                    spans += Span(kind, open, end, open + length, close)
                }
                open = masked.indexOf(char, end)
            }
        }
    }

    private val EMPHASIS = listOf(
        Emphasis(Kind.BOLD, "**"),
        Emphasis(Kind.BOLD, "__", wordBound = true),
        Emphasis(Kind.STRIKE, "~~"),
        Emphasis(Kind.ITALIC, "*"),
        Emphasis(Kind.ITALIC, "_", wordBound = true)
    )

    /** A space around the delimiters of the emphasis: the spaces of ASCII. */
    private fun Char.isSpace() = this == ' ' || this in '\t'..'\r'

    // Inline flags such as (?m) are not supported everywhere
    private val HEADING = Regex("""^#{1,6}[ \t]+\S.*$""", RegexOption.MULTILINE)
    private val BULLET_LIST = Regex("""^([ \t]*)([-*+])[ \t]+(.*)$""", RegexOption.MULTILINE)
    private val NUMBERED_LIST = Regex("""^([ \t]*)(\d+)\.[ \t]+(.*)$""", RegexOption.MULTILINE)

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

    private const val MASK = ''
}
