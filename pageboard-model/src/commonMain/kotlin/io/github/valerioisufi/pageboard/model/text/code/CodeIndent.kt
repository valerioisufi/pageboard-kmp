package io.github.valerioisufi.pageboard.model.text.code

/**
 * The indentation of the code of a block in its language (see `docs/plans/code-blocks-more.md`):
 * which lines open a block of the language, which characters close one, and the step a block
 * indents by. It reads the openers of the grammar of the language; a language it does not know
 * opens and closes nothing, and its lines keep the indentation of the line before.
 */
public object CodeIndent {

    /**
     * The character opening a block that [code] ends with, out of its comments and its strings,
     * after the last character of its last line that is not a space: `{`, `(` or `[`, `:` in
     * Python and YAML. `null` when it ends with anything else, or [language] is not known.
     */
    public fun openerAtEnd(code: String, language: String): Char? {
        val grammar = CodeGrammars.byName[language.lowercase()] ?: return null
        if (code.length > CodeHighlighter.MAX_HIGHLIGHTED) return null
        var last = code.length - 1
        while (last >= 0 && (code[last] == ' ' || code[last] == '\t')) last--
        if (last < 0 || code[last] == '\n' || code[last] !in grammar.openers) return null
        // What a comment or a string holds opens nothing: `// {`, `"("`
        val covered = CodeLexer(grammar, code).tokens().any {
            (it.kind == CodeTokenKind.COMMENT || it.kind == CodeTokenKind.STRING) && last in it.start until it.end
        }
        return if (covered) null else code[last]
    }

    /** The character closing the block [opener] opens: `}` for `{`; `null` for `:`, closed by the indentation alone. */
    public fun closerOf(opener: Char): Char? = when (opener) {
        '{' -> '}'
        '(' -> ')'
        '[' -> ']'
        else -> null
    }

    /** Whether [char] closes a block of [language]: typed alone on an indented line, the line goes one step back. */
    public fun closes(char: Char, language: String): Boolean {
        val grammar = CodeGrammars.byName[language.lowercase()] ?: return false
        return grammar.openers.any { closerOf(it) == char }
    }

    /**
     * The step of indentation of the block of code [code], whose lines are under a fence indented
     * by [base] spaces: a tab when its first line indented further starts with one, else the least
     * indentation of its lines indented further, else the step of [language] (four spaces, two in
     * JSON and YAML).
     */
    public fun stepOf(code: String, language: String, base: Int = 0): String {
        var least = Int.MAX_VALUE
        var lineStart = 0
        while (lineStart <= code.length) {
            val lineEnd = code.indexOf('\n', lineStart).let { if (it < 0) code.length else it }
            var i = lineStart
            // The spaces of the fence belong to the block, not to its code
            while (i < lineEnd && i - lineStart < base && code[i] == ' ') i++
            if (i < lineEnd && code[i] == '\t') {
                if (least == Int.MAX_VALUE) return "\t"
            } else {
                var j = i
                while (j < lineEnd && code[j] == ' ') j++
                if (j > i && j < lineEnd) least = minOf(least, j - i)
            }
            lineStart = lineEnd + 1
        }
        val step = if (least != Int.MAX_VALUE) least else CodeGrammars.byName[language.lowercase()]?.indentStep ?: DEFAULT_STEP
        return " ".repeat(step.coerceIn(1, MAX_STEP))
    }

    private const val DEFAULT_STEP = 4
    private const val MAX_STEP = 8
}
