package io.github.valerioisufi.drawview.model.text

/**
 * The notation of the texts: Markdown (bold, italic, strikethrough, code, headings) with formulas,
 * `$…$` in the line and `$$…$$` in display style (on a line of their own when nothing else is on
 * their lines), and the escapes `\*` of the punctuation.
 *
 * The rules follow Pandoc for the formulas (a `$` opening a formula is followed by a character
 * other than a space, the one closing it follows such a character and is not followed by a
 * digit: `$5 and $10` is plain text) and CommonMark for the emphasis (the delimiters touch the
 * text they surround; `_` does not work inside a word: `file_name_here` is plain text).
 * Formatting nests (`**bold *italic***`, a formula in a heading); the code and the formulas are
 * written as they are.
 */
public object RichTextParser {

    /** A kind of [Span]. */
    public enum class Kind { BOLD, ITALIC, STRIKE, CODE, HEADING, FORMULA, ESCAPE, LIST_ITEM }

    /**
     * A part of a text written with the notation: from [start] to [end] (the delimiters
     * included), its content from [contentStart] to [contentEnd].
     *
     * @property level the level of a heading (the number of its `#`) or of a list item (indentation depth).
     * @property display a formula written `$$…$$`, drawn in display style.
     * @property block a display formula alone on its lines: a centered line of its own.
     * @property ordered whether a list item is ordered (numbered).
     * @property number the sequence number of an ordered list item.
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
        val number: Int = 0
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
        // The other delimiters are looked for outside the code and the formulas
        val masked = mask(text, verbatim)
        val candidates = verbatim.toMutableList()
        for (pattern in EMPHASIS) {
            for (match in pattern.regex.findAll(masked)) {
                val start = match.range.first
                val end = match.range.last + 1
                if (pattern.wordBound && (text.isWordAt(start - 1) || text.isWordAt(end))) continue
                candidates += Span(pattern.kind, start, end, start + pattern.delimiter, end - pattern.delimiter)
            }
        }
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
        return nest(candidates)
    }

    /**
     * The escapes, the code and the formulas, scanned from the start: what is inside them is not
     * notation.
     */
    private fun scanVerbatim(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var i = 0
        while (i < text.length) {
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
                    val close = closingInline(text, i)
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

    /** Where the `$$` closing a display formula whose content starts at [from] is, or -1. */
    private fun closingDisplay(text: String, from: Int): Int {
        var j = from
        while (j < text.length - 1) {
            if (text[j] == '\\') {
                j += 2
                continue
            }
            if (text[j] == '$' && text[j + 1] == '$') return if (text.substring(from, j).isBlank()) -1 else j
            j++
        }
        return -1
    }

    /**
     * Where the `$` closing the formula opened at [open] is, or -1 (Pandoc: a character other
     * than a space after the opening one and before the closing one, no digit after the closing
     * one; on one line).
     */
    private fun closingInline(text: String, open: Int): Int {
        val first = text.getOrNull(open + 1) ?: return -1
        if (first.isWhitespace() || first == '$') return -1
        var j = open + 1
        while (j < text.length) {
            val c = text[j]
            if (c == '\n') return -1
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
        return -1
    }

    /** Whether only spaces are around [start] .. [end] on their lines. */
    private fun isAloneOnItsLines(text: String, start: Int, end: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        return text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank()
    }

    /** [text] with the characters of [spans] replaced, the offsets kept. */
    private fun mask(text: String, spans: List<Span>): String {
        if (spans.isEmpty()) return text
        val chars = text.toCharArray()
        for (span in spans) for (i in span.start until span.end) chars[i] = MASK
        return chars.concatToString()
    }

    private fun String.isWordAt(index: Int) = getOrNull(index)?.isLetterOrDigit() == true

    /**
     * The spans that nest, sorted: an outer one first; a span overlapping one kept partially, or
     * inside a delimiter, code or formula, is dropped.
     */
    private fun nest(candidates: List<Span>): List<Span> {
        val sorted = candidates.sortedWith(compareBy<Span> { it.start }.thenByDescending { it.end })
        val kept = mutableListOf<Span>()
        for (span in sorted) {
            val fits = kept.all { other ->
                span.end <= other.start || span.start >= other.end ||
                    // Inside the content of a span whose content is notation
                    (other.kind != Kind.CODE && other.kind != Kind.FORMULA && other.kind != Kind.ESCAPE &&
                        span.start >= other.contentStart && span.end <= other.contentEnd)
            }
            if (fits) kept += span
        }
        return kept
    }

    private class Emphasis(val kind: Kind, val delimiter: Int, val regex: Regex, val wordBound: Boolean = false)

    // The content touches its delimiters; [\s\S]: any character, the line breaks included
    private val EMPHASIS = listOf(
        Emphasis(Kind.BOLD, 2, Regex("""\*\*(?=\S)([\s\S]+?)(?<=\S)\*\*""")),
        Emphasis(Kind.BOLD, 2, Regex("""__(?=\S)([\s\S]+?)(?<=\S)__"""), wordBound = true),
        Emphasis(Kind.STRIKE, 2, Regex("""~~(?=\S)([\s\S]+?)(?<=\S)~~""")),
        Emphasis(Kind.ITALIC, 1, Regex("""(?<!\*)\*(?![\s*])([\s\S]+?)(?<![\s*])\*(?!\*)""")),
        Emphasis(Kind.ITALIC, 1, Regex("""(?<!_)_(?![\s_])([\s\S]+?)(?<![\s_])_(?!_)"""), wordBound = true)
    )

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

    private const val MASK = ''
}
