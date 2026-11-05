package io.github.valerioisufi.drawview.html

import io.github.valerioisufi.drawview.model.text.ListMarker
import io.github.valerioisufi.drawview.model.text.RichTextParser

/**
 * The characters of a text that would read as the notation (see `RichTextParser`) written so that
 * they stay characters, and only those: `file_name` and `[1]` are written as they are.
 */
internal object MarkdownEscape {

    /** The punctuation an escape applies to, as `RichTextParser` reads it. */
    private const val ESCAPABLE = """\`*_{}[]()#+-.!${'$'}~|<>"""

    private val HEADING_MARKER = Regex("""^#{1,6}[ \t]""")

    /** What a formula has and a sentence between two prices has not. */
    private val FORMULA_SIGN = Regex("""[\\^_{}=+\-*/<>()]""")

    /** A formula this short is a symbol, as `$l$`. */
    private const val SYMBOL_CHARS = 3

    /**
     * The formulas written as text in [text], in their order: some applications (Gemini) copy a
     * formula as its source, between dollars. They are those the notation reads, when they look like a
     * formula: `$5 e $10` and `$a e b$` stay text.
     */
    fun formulasIn(text: String): List<IntRange> {
        if ('$' !in text) return emptyList()
        return RichTextParser.parse(text)
            .filter { it.kind == RichTextParser.Kind.FORMULA }
            .filter { it.contentEnd - it.contentStart <= SYMBOL_CHARS || FORMULA_SIGN.containsMatchIn(text.substring(it.contentStart, it.contentEnd)) }
            .map { it.start until it.end }
    }

    /**
     * [text] of a line, which [startsLine] when nothing is before it on its line.
     *
     * @param inLink it goes between the brackets of a link.
     * @param inTable it goes in a cell of a table.
     */
    fun escape(text: String, startsLine: Boolean, inLink: Boolean = false, inTable: Boolean = false): String {
        val out = StringBuilder(text.length + 8)
        for ((i, c) in text.withIndex()) {
            val previous = text.getOrNull(i - 1)
            val next = text.getOrNull(i + 1)
            val escaped = when (c) {
                '\\' -> next == null || next in ESCAPABLE
                '`', '*', '$' -> true
                '_' -> previous?.isLetterOrDigit() != true || next?.isLetterOrDigit() != true
                '~' -> previous == '~' || next == '~'
                '[' -> inLink
                ']' -> inLink || next == '('
                '|' -> inTable
                else -> false
            }
            if (escaped) out.append('\\')
            out.append(c)
        }
        return if (startsLine) withLineStartEscaped(out.toString(), text) else out.toString()
    }

    /** [escaped], the text [source] with its characters escaped, with a marker of a block at its start escaped too. */
    private fun withLineStartEscaped(escaped: String, source: String): String {
        val marker = when (source.firstOrNull()) {
            '#' -> if (HEADING_MARKER.containsMatchIn(source)) 0 else -1
            '-', '+' -> if (source.getOrNull(1) == ' ') 0 else -1
            '>' -> if (source.length == 1 || source[1] == ' ') 0 else -1
            else -> if (ListMarker.at(source) == null) -1 else source.indexOfFirst { it == '.' || it == ')' }
        }
        if (marker < 0) return escaped
        // Nothing before a marker was escaped: it is made of letters or digits
        return escaped.substring(0, marker) + '\\' + escaped.substring(marker)
    }
}
