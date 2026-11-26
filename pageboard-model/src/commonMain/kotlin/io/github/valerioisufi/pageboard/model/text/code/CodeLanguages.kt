package io.github.valerioisufi.pageboard.model.text.code

import io.github.valerioisufi.pageboard.model.text.RichTextParser

/**
 * The names of the languages completed while one is typed after the opening fence of a block of
 * code (see `docs/plans/code-blocks-more.md`), as the commands of LaTeX are in a formula.
 */
public object CodeLanguages {

    /**
     * A name being typed at `[tokenStart]..[tokenEnd)` of the text, [prefix] so far, and the names
     * of the languages known starting with it ([candidates], at most [MOST]).
     */
    public data class Match(
        public val tokenStart: Int,
        public val tokenEnd: Int,
        public val prefix: String,
        public val candidates: List<String>
    )

    /** The names offered: the usual name of each language first, then its aliases. */
    private val NAMES: List<String> = listOf(
        "kotlin", "java", "c", "cpp", "csharp", "javascript", "typescript", "swift", "go", "rust", "python",
        "shell", "bash", "sql", "json", "yaml"
    ).let { usual -> usual + CodeGrammars.byName.keys.filter { it !in usual }.sorted() }

    /**
     * The name being typed at [caret] of [text]: the first word after the opening fence of a block
     * of code, the caret at its end; `null` elsewhere, or when no other name starts with it.
     */
    public fun match(text: String, caret: Int): Match? {
        if (caret <= 0 || caret > text.length) return null
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        if (caret > lineEnd) return null
        // The line of the caret opens a block
        val block = RichTextParser.parse(text).firstOrNull { it.kind == RichTextParser.Kind.CODE_BLOCK && it.start == lineStart } ?: return null
        var tokenStart = block.start + block.level
        val fence = text[tokenStart]
        while (tokenStart < text.length && text[tokenStart] == fence) tokenStart++
        while (tokenStart < caret && text[tokenStart] == ' ') tokenStart++
        if (tokenStart >= caret) return null
        val prefix = text.substring(tokenStart, caret)
        if (prefix.any { !it.isLetterOrDigit() && it != '+' && it != '#' }) return null
        var tokenEnd = caret
        while (tokenEnd < text.length && (text[tokenEnd].isLetterOrDigit() || text[tokenEnd] == '+' || text[tokenEnd] == '#')) tokenEnd++
        val lower = prefix.lowercase()
        val candidates = NAMES.filter { it.startsWith(lower) && it != lower }.take(MOST)
        return if (candidates.isEmpty()) null else Match(tokenStart, tokenEnd, prefix, candidates)
    }

    /** [text] with the name of [match] replaced by [name], and the caret after it. */
    public fun apply(text: String, match: Match, name: String): Pair<String, Int> =
        text.substring(0, match.tokenStart) + name + text.substring(match.tokenEnd) to match.tokenStart + name.length

    private const val MOST = 8
}
