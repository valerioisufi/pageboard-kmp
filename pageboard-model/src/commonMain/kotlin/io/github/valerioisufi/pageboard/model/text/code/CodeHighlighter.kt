package io.github.valerioisufi.pageboard.model.text.code

/** What a [CodeToken] is: its color tells it. */
public enum class CodeTokenKind {
    /** A word of the language: `fun`, `if`, `return`. */
    KEYWORD,

    /** A type: a primitive of the language, or a name written as a type is (`String`). */
    TYPE,

    /** A constant of the language: `true`, `null`, `None`. */
    LITERAL,

    /** A string or a character, its quotes included. */
    STRING,

    /** A number, its prefix and suffix included (`0xFF`, `1.5e-3f`). */
    NUMBER,

    /** A comment, its delimiters included. */
    COMMENT,

    /** An annotation, a decorator, a directive of the preprocessor, an attribute. */
    ANNOTATION,

    /** A function called or declared: a name followed by `(`, a macro. */
    FUNCTION,

    /** A key of JSON or YAML, a variable of the shell. */
    PROPERTY
}

/** A part of a code from [start] to [end] (offsets of the code), of [kind]. */
public data class CodeToken(val kind: CodeTokenKind, val start: Int, val end: Int)

/**
 * Tells the tokens of a code in its language, for its colors: the port through which the texts
 * draw their blocks of code without depending on an engine ([BuiltInCodeHighlighter] by default;
 * an application may give another one, e.g. on tree-sitter).
 *
 * Implementations must be thread-safe (the tiles are drawn on background workers) and, the code
 * coming from the other devices too, take a time linear in the length of the code.
 */
public interface CodeHighlighter {
    /**
     * The tokens of [code] written in [language] (a name or an alias, in any case), sorted and
     * apart; what is in no token is drawn in the color of the text. Empty when the language is
     * not known.
     */
    public fun tokens(code: String, language: String): List<CodeToken>

    public companion object {
        /** The longest code highlighted: a longer one is drawn in one color, whatever the engine. */
        public const val MAX_HIGHLIGHTED: Int = 64 * 1024
    }
}

/**
 * The highlighter of DrawView: a lexer of its own reading the code once, from the start, for the
 * languages of [languages] (the family of C, Python, the shell, SQL, JSON, YAML). It knows the
 * words, the strings, the comments, the numbers and the annotations of a language, not its
 * grammar: what it reads wrong is a color, never the text.
 */
public object BuiltInCodeHighlighter : CodeHighlighter {

    /** The names of the languages known, with their aliases, in lowercase. */
    public val languages: Set<String> get() = CodeGrammars.byName.keys

    override fun tokens(code: String, language: String): List<CodeToken> {
        if (code.length > CodeHighlighter.MAX_HIGHLIGHTED) return emptyList()
        val grammar = CodeGrammars.byName[language.lowercase()] ?: return emptyList()
        return CodeLexer(grammar, code).tokens()
    }
}
