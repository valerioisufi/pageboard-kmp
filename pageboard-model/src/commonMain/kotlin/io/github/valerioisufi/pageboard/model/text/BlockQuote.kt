package io.github.valerioisufi.pageboard.model.text

/**
 * The marker of a line of a block quote, the only place knowing how it is written: up to 3 spaces,
 * then `> `, or `>` alone on its line. `>5` and `>=` stay text, which is stricter than CommonMark:
 * they are common at the start of a line of notes. A quote in a quote has one more `> ` for each
 * level (`> > text`); `>>` stays text, as in the notes written before the levels.
 */
public object BlockQuote {

    /** The most spaces before a marker. */
    private const val MAX_INDENT = 3

    /** The written marker of a line. */
    public const val MARKER: String = "> "

    /** The most levels of quotes in quotes read: deeper than any note. */
    private const val MAX_LEVELS = 8

    /**
     * The length of the marker of the line starting at [lineStart] of [text], all its levels and the
     * space after each; 0 for none.
     */
    public fun markerLength(text: CharSequence, lineStart: Int): Int = markerOf(text, lineStart, MAX_LEVELS).first

    /** The levels of the quotes the line starting at [lineStart] of [text] is in: 0 out of a quote, 2 for `> > text`. */
    public fun levelAt(text: CharSequence, lineStart: Int): Int = markerOf(text, lineStart, MAX_LEVELS).second

    /** The length of the first [levels] levels of the marker of the line starting at [lineStart] of [text]. */
    public fun markerLength(text: CharSequence, lineStart: Int, levels: Int): Int = markerOf(text, lineStart, levels).first

    /** The marker written for a line in [level] quotes: `> ` for each. */
    public fun markerOf(level: Int): String = MARKER.repeat(level.coerceAtLeast(0))

    /** The length and the levels of the marker of a line, reading [most] levels at most. */
    private fun markerOf(text: CharSequence, lineStart: Int, most: Int): Pair<Int, Int> {
        var i = lineStart
        while (i < text.length && i - lineStart < MAX_INDENT && text[i] == ' ') i++
        var length = 0
        var levels = 0
        while (levels < most && text.getOrNull(i) == '>') {
            when (text.getOrNull(i + 1)) {
                null, '\n' -> return (i + 1 - lineStart) to levels + 1
                ' ' -> {
                    i += 2
                    levels++
                    length = i - lineStart
                }
                else -> break
            }
        }
        return length to levels
    }

    /** The start of the line of [offset] of [text]. */
    public fun lineStartOf(text: CharSequence, offset: Int): Int {
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
