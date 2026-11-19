package io.github.valerioisufi.drawview.model.text

/**
 * The marker of a line of a block quote, the only place knowing how it is written: up to 3 spaces,
 * then `> `, or `>` alone on its line. `>5` and `>=` stay text, which is stricter than CommonMark:
 * they are common at the start of a line of notes.
 */
public object BlockQuote {

    /** The most spaces before a marker. */
    private const val MAX_INDENT = 3

    /** The written marker of a line. */
    public const val MARKER: String = "> "

    /** The length of the marker of the line starting at [lineStart] of [text], with the space after it; 0 for none. */
    public fun markerLength(text: CharSequence, lineStart: Int): Int {
        var i = lineStart
        while (i < text.length && i - lineStart < MAX_INDENT && text[i] == ' ') i++
        if (text.getOrNull(i) != '>') return 0
        val next = text.getOrNull(i + 1)
        return when (next) {
            null, '\n' -> i + 1 - lineStart
            ' ' -> i + 2 - lineStart
            else -> 0
        }
    }

    /** The start of the line of [offset] of [text]. */
    internal fun lineStartOf(text: CharSequence, offset: Int): Int {
        var k = offset
        while (k > 0 && text[k - 1] != '\n') k--
        return k
    }

    /** Whether the line of [offset] of [text] has a marker before [offset]. */
    public fun isQuotedAt(text: CharSequence, offset: Int): Boolean {
        val start = lineStartOf(text, offset)
        val length = markerLength(text, start)
        return length > 0 && start + length <= offset
    }
}
