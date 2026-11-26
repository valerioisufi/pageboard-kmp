package io.github.valerioisufi.pageboard.search

/**
 * The words of a text being written, as the writing suggestions read them (see
 * `docs/plans/writing-suggestions.md` and `docs/plans/writing-suggestions-more.md`).
 */
public object WritingWords {

    /**
     * The word being written before [caret] in [source], `null` when it is shorter than
     * [minPrefix], goes on after the caret, or is in a formula, in code, in the address of a link or
     * a command of LaTeX.
     */
    public fun prefixAt(source: String, caret: Int, minPrefix: Int = 3): String? {
        if (caret <= 0 || caret > source.length) return null
        if (caret < source.length && source[caret].isLetterOrDigit()) return null
        var start = caret
        while (start > 0 && source[start - 1].isLetterOrDigit()) start--
        val prefix = source.substring(start, caret)
        if (prefix.length < minPrefix || !prefix.first().isLetter()) return null
        if (start > 0 && source[start - 1] == '\\') return null
        val line = source.substring(source.lastIndexOf('\n', start - 1) + 1, start)
        if (unescapedCount(line, '$') % 2 == 1 || unescapedCount(line, '`') % 2 == 1) return null
        val link = line.lastIndexOf("](")
        if (link >= 0 && line.indexOf(')', link) < 0) return null
        return prefix
    }

    /**
     * The rest of the word [prefix] begins, in the case the letters typed ask for: all in capitals,
     * the rest is too.
     */
    public fun restOf(word: String, prefix: String): String {
        val rest = word.substring(prefix.length)
        return if (prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() }) rest.uppercase() else rest
    }

    private fun unescapedCount(line: String, char: Char): Int =
        line.indices.count { line[it] == char && (it == 0 || line[it - 1] != '\\') }
}
