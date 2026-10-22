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
        candidates += emphasis(masked, text)
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
        val inline = InlineClosings(text)
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
                (outer.kind != Kind.CODE && outer.kind != Kind.FORMULA && outer.kind != Kind.ESCAPE &&
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

    private const val MASK = ''
}
