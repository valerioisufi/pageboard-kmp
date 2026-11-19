package io.github.valerioisufi.drawview.model.text.code

/**
 * A kind of string of a language: from [open] to [close]; with backslash [escapes] or not; the
 * close written twice standing for itself ([doubled]: `''` in SQL); on many lines or one
 * ([multiline]: a string not closed on its line ends with it).
 */
internal class Quote(
    val open: String,
    val close: String = open,
    val escapes: Boolean = true,
    val doubled: Boolean = false,
    val multiline: Boolean = false
)

/**
 * What [CodeLexer] knows of a language: its words, its comments, its strings and the forms
 * particular to it. Data only: the lexer reads every language the same way.
 *
 * @property caseSensitive whether case matters in [keywords], [types] and [literals] (no: SQL,
 *   YAML), which are then written in lowercase.
 * @property capitalizedTypes a name starting with a capital letter (and not all in capitals) is a
 *   type.
 * @property calls a name followed by `(` is a function.
 * @property hashComments `#` starts a comment at the start of a word only (shell, YAML).
 * @property stringPrefixes the words written just before a quote, part of the string (`r`, `f`
 *   of Python), in lowercase (they are matched in any case); a prefix with `r` makes it raw (no
 *   escapes).
 * @property charLiterals `'a'`, `'\n'` are characters; [lifetimes]: `'a` alone is a lifetime of
 *   Rust (an annotation).
 * @property annotations `@Name` (and `@a.b`) is an annotation.
 * @property preprocessor a line starting with `#` is a directive of the preprocessor.
 * @property attributes `#[…]` and `#![…]` are attributes of Rust.
 * @property rustRawStrings `r"…"`, `r#"…"#` (and `br…`); [swiftRawStrings]: `#"…"#`, and
 *   `#name` a directive of Swift; [cppRawStrings]: `R"(…)"` and `R"x(…)x"`.
 * @property macros a name followed by `!` is a macro of Rust.
 * @property variables `$name`, `${…}`, `$1` are variables of the shell, `$'…'` a string.
 * @property keys a string followed by `:` is a key (JSON, YAML); [plainKeys]: so is the word
 *   starting a line before `: ` (YAML); [nodes]: `&anchor`, `*alias` and `!tag` of YAML.
 * @property dollarInNames `$` is part of the names (JavaScript).
 */
internal class CodeGrammar(
    val keywords: Set<String>,
    val types: Set<String> = emptySet(),
    val literals: Set<String> = emptySet(),
    val functions: Set<String> = emptySet(),
    val caseSensitive: Boolean = true,
    val capitalizedTypes: Boolean = false,
    val calls: Boolean = true,
    val lineComments: List<String> = emptyList(),
    val hashComments: Boolean = false,
    val blockComment: Pair<String, String>? = null,
    val nestedComments: Boolean = false,
    quotes: List<Quote> = emptyList(),
    val stringPrefixes: Set<String> = emptySet(),
    val charLiterals: Boolean = false,
    val lifetimes: Boolean = false,
    val annotations: Boolean = false,
    val preprocessor: Boolean = false,
    val attributes: Boolean = false,
    val rustRawStrings: Boolean = false,
    val swiftRawStrings: Boolean = false,
    val cppRawStrings: Boolean = false,
    val macros: Boolean = false,
    val variables: Boolean = false,
    val keys: Boolean = false,
    val plainKeys: Boolean = false,
    val nodes: Boolean = false,
    val dollarInNames: Boolean = false
) {
    /** The strings, the longest opening first (`"""` before `"`). */
    val quotes: List<Quote> = quotes.sortedByDescending { it.open.length }
}

/**
 * Reads [code] once, from the start, into its tokens in the language of [grammar].
 *
 * Every step takes at least one character; what a step reads ahead without taking it is bounded
 * (a character, a delimiter of C++) or is read once more by the next steps only (the spaces before
 * a `(`, the line of a key of YAML): the time is linear in the length of the code.
 */
internal class CodeLexer(private val grammar: CodeGrammar, private val code: String) {
    private val tokens = mutableListOf<CodeToken>()
    private var i = 0

    /** Only spaces since the start of the line. */
    private var lineStart = true

    fun tokens(): List<CodeToken> {
        while (i < code.length) {
            val c = code[i]
            if (c == '\n') {
                i++
                lineStart = true
                continue
            }
            if (c == ' ' || c == '\t' || c == '\r') {
                i++
                continue
            }
            val atLineStart = lineStart
            lineStart = false
            val taken = (atLineStart && grammar.plainKeys && plainKey()) ||
                (atLineStart && grammar.preprocessor && c == '#' && directive()) ||
                comment() || rawString() || string() || character() || variable() || annotation() ||
                node() || number() || name()
            if (!taken) i++
        }
        return tokens
    }

    private fun add(kind: CodeTokenKind, start: Int, end: Int) {
        if (end > start) tokens += CodeToken(kind, start, end)
    }

    private fun at(index: Int): Char = if (index in code.indices) code[index] else '\u0000'

    private fun isNameStart(c: Char) = c.isLetter() || c == '_' || (c == '$' && grammar.dollarInNames)

    private fun isNamePart(c: Char) = c.isLetterOrDigit() || c == '_' || (c == '$' && grammar.dollarInNames)

    /** Where the name starting at [from] ends. */
    private fun nameEnd(from: Int): Int {
        var j = from
        while (j < code.length && isNamePart(code[j])) j++
        return j
    }

    /** Where the line of [from] ends (its line break, or the end of the code). */
    private fun lineEnd(from: Int): Int = code.indexOf('\n', from).let { if (it < 0) code.length else it }

    private fun atWordStart(): Boolean = i == 0 || at(i - 1).isWhitespace() || at(i - 1) == ';' || at(i - 1) == '('

    private fun comment(): Boolean {
        for (prefix in grammar.lineComments) {
            if (code.startsWith(prefix, i)) return lineComment()
        }
        if (grammar.hashComments && code[i] == '#' && atWordStart()) return lineComment()
        val (open, close) = grammar.blockComment ?: return false
        if (!code.startsWith(open, i)) return false
        val start = i
        var depth = 0
        while (i < code.length) {
            when {
                code.startsWith(open, i) && (depth == 0 || grammar.nestedComments) -> {
                    depth++
                    i += open.length
                }
                code.startsWith(close, i) -> {
                    depth--
                    i += close.length
                    if (depth == 0) break
                }
                else -> i++
            }
        }
        add(CodeTokenKind.COMMENT, start, i)
        return true
    }

    private fun lineComment(): Boolean {
        val end = lineEnd(i)
        add(CodeTokenKind.COMMENT, i, end)
        i = end
        return true
    }

    /** The quote of the language opening at [index], or `null`. */
    private fun quoteAt(index: Int): Quote? = grammar.quotes.firstOrNull { code.startsWith(it.open, index) }

    private fun string(): Boolean {
        val quote = quoteAt(i) ?: return false
        val start = i
        i = closeString(quote, i + quote.open.length, quote.escapes)
        stringOrKey(start)
        return true
    }

    /**
     * Where the string of [quote] whose content starts at [from] ends: after its close, at the end
     * of its line when it is on one line and not closed there, or at the end of the code.
     */
    private fun closeString(quote: Quote, from: Int, escapes: Boolean): Int {
        var j = from
        while (j < code.length) {
            val c = code[j]
            when {
                c == '\n' && !quote.multiline -> return j
                c == '\\' && escapes -> j += 2
                code.startsWith(quote.close, j) -> {
                    if (quote.doubled && code.startsWith(quote.close, j + quote.close.length)) {
                        j += 2 * quote.close.length
                    } else {
                        return j + quote.close.length
                    }
                }
                else -> j++
            }
        }
        return code.length
    }

    /** The string from [start] to here, or a key when the language has keys and `:` follows it. */
    private fun stringOrKey(start: Int) {
        var j = i
        while (j < code.length && (code[j] == ' ' || code[j] == '\t')) j++
        add(if (grammar.keys && at(j) == ':') CodeTokenKind.PROPERTY else CodeTokenKind.STRING, start, i)
    }

    /** A string written with a prefix (`f"…"` of Python), the prefix [start] .. [end] read as a name. */
    private fun prefixedString(start: Int, end: Int): Boolean {
        val prefix = code.substring(start, end).lowercase()
        if (prefix !in grammar.stringPrefixes) return false
        val quote = quoteAt(end) ?: return false
        i = closeString(quote, end + quote.open.length, quote.escapes && 'r' !in prefix)
        stringOrKey(start)
        return true
    }

    /** The raw strings of Rust, Swift and C++. */
    private fun rawString(): Boolean {
        val c = code[i]
        if (grammar.rustRawStrings && (c == 'r' || (c == 'b' && at(i + 1) == 'r'))) {
            var j = if (c == 'b') i + 2 else i + 1
            val hashesStart = j
            while (at(j) == '#') j++
            if (at(j) != '"') return false
            val close = "\"" + "#".repeat(j - hashesStart)
            return rawUntil(close, j + 1)
        }
        if (grammar.swiftRawStrings && c == '#') {
            var j = i
            while (at(j) == '#') j++
            val hashes = j - i
            if (at(j) == '"') {
                val triple = code.startsWith("\"\"\"", j)
                val close = (if (triple) "\"\"\"" else "\"") + "#".repeat(hashes)
                return rawUntil(close, j + if (triple) 3 else 1)
            }
            if (hashes == 1 && isNameStart(at(j))) {
                val end = nameEnd(j)
                add(CodeTokenKind.ANNOTATION, i, end)
                i = end
                return true
            }
            // The hashes taken together: none of them opens a string
            i = j
            return true
        }
        if (grammar.cppRawStrings && c == 'R' && at(i + 1) == '"') {
            // A delimiter of up to 16 characters before the parenthesis
            var j = i + 2
            while (j < code.length && j - i - 2 <= MAX_CPP_DELIMITER && code[j] != '(' && code[j] != '"' && code[j] != '\n' && !code[j].isWhitespace()) j++
            if (at(j) != '(') return false
            val close = ")" + code.substring(i + 2, j) + "\""
            return rawUntil(close, j + 1)
        }
        return false
    }

    /** A raw string from here, its content from [from] to after [close] (or to the end of the code). */
    private fun rawUntil(close: String, from: Int): Boolean {
        val found = code.indexOf(close, from)
        val end = if (found < 0) code.length else found + close.length
        add(CodeTokenKind.STRING, i, end)
        i = end
        return true
    }

    /** A character (`'a'`, `'\n'`, `'\u{1F600}'`), or a lifetime of Rust. */
    private fun character(): Boolean {
        if (!grammar.charLiterals || code[i] != '\'') return false
        val next = at(i + 1)
        val close = when {
            next == '\\' -> {
                // After the escaped character: '\'' is a character too
                var j = i + 3
                while (j < code.length && j - i <= MAX_CHARACTER && code[j] != '\'' && code[j] != '\n') j++
                if (at(j) == '\'' && at(i + 2) != '\n') j else -1
            }
            next.isHighSurrogate() && at(i + 2).isLowSurrogate() -> if (at(i + 3) == '\'') i + 3 else -1
            next != '\n' && next != '\'' && next != '\u0000' -> if (at(i + 2) == '\'') i + 2 else -1
            else -> -1
        }
        if (close >= 0) {
            add(CodeTokenKind.STRING, i, close + 1)
            i = close + 1
            return true
        }
        if (grammar.lifetimes && isNameStart(next)) {
            val end = nameEnd(i + 1)
            add(CodeTokenKind.ANNOTATION, i, end)
            i = end
            return true
        }
        return false
    }

    /** A variable of the shell, or its string `$'…'`. */
    private fun variable(): Boolean {
        if (!grammar.variables || code[i] != '$') return false
        val next = at(i + 1)
        val end = when {
            next == '{' -> {
                // To the brace closing it on the line, or to the end of the line
                var j = i + 2
                while (j < code.length && code[j] != '}' && code[j] != '\n') j++
                if (at(j) == '}') j + 1 else j
            }
            next == '\'' -> {
                val stringEnd = closeString(ANSI_C_QUOTE, i + 2, escapes = true)
                add(CodeTokenKind.STRING, i, stringEnd)
                i = stringEnd
                return true
            }
            isNameStart(next) -> nameEnd(i + 1)
            next.isDigit() || next in SPECIAL_VARIABLES -> i + 2
            else -> return false
        }
        add(CodeTokenKind.PROPERTY, i, end)
        i = end
        return true
    }

    /** An annotation `@Name` or `@a.b`, or an attribute `#[…]` of Rust. */
    private fun annotation(): Boolean {
        val c = code[i]
        if (grammar.annotations && c == '@' && isNameStart(at(i + 1))) {
            var end = nameEnd(i + 1)
            while (at(end) == '.' && isNameStart(at(end + 1))) end = nameEnd(end + 1)
            add(CodeTokenKind.ANNOTATION, i, end)
            i = end
            return true
        }
        if (grammar.attributes && c == '#' && (at(i + 1) == '[' || (at(i + 1) == '!' && at(i + 2) == '['))) {
            // To the bracket closing it on the line, or to the end of the line
            var j = i + 1
            var depth = 0
            while (j < code.length && code[j] != '\n') {
                if (code[j] == '[') depth++
                if (code[j] == ']' && --depth == 0) {
                    j++
                    break
                }
                j++
            }
            add(CodeTokenKind.ANNOTATION, i, j)
            i = j
            return true
        }
        return false
    }

    /** A directive of the preprocessor at the start of a line: `#include <a.h>`. */
    private fun directive(): Boolean {
        var j = i + 1
        while (at(j) == ' ' || at(j) == '\t') j++
        if (!isNameStart(at(j))) return false
        val end = nameEnd(j)
        add(CodeTokenKind.ANNOTATION, i, end)
        i = end
        val word = code.substring(j, end)
        if (word == "include" || word == "import") {
            var k = i
            while (at(k) == ' ' || at(k) == '\t') k++
            if (at(k) == '<') {
                var close = k + 1
                while (close < code.length && code[close] != '>' && code[close] != '\n') close++
                if (at(close) == '>') {
                    add(CodeTokenKind.STRING, k, close + 1)
                    i = close + 1
                }
            }
        }
        return true
    }

    /** An anchor, an alias or a tag of YAML, at the start of a word. */
    private fun node(): Boolean {
        if (!grammar.nodes) return false
        val c = code[i]
        if ((c != '&' && c != '*' && c != '!') || !atWordStart()) return false
        var j = i + 1
        while (j < code.length && !code[j].isWhitespace() && code[j] != ',' && code[j] != ']' && code[j] != '}') j++
        if (j == i + 1) return false
        add(CodeTokenKind.ANNOTATION, i, j)
        i = j
        return true
    }

    /**
     * A key of YAML starting the line here: after the `- ` of the items of a list, a word up to a
     * `:` followed by a space or the end of the line.
     */
    private fun plainKey(): Boolean {
        var j = i
        while (at(j) == '-' && (at(j + 1) == ' ' || at(j + 1) == '\t')) {
            j += 2
            while (at(j) == ' ' || at(j) == '\t') j++
        }
        val start = j
        if (at(start) in NOT_PLAIN_KEY) return false
        val line = lineEnd(start)
        while (j < line) {
            val c = code[j]
            if (c == ':' && (j + 1 == code.length || at(j + 1).isWhitespace())) {
                var end = j
                while (end > start && code[end - 1].isWhitespace()) end--
                if (end == start) return false
                add(CodeTokenKind.PROPERTY, start, end)
                i = j + 1
                return true
            }
            if (c == '#' && j > start && code[j - 1].isWhitespace()) return false
            j++
        }
        return false
    }

    private fun number(): Boolean {
        val c = code[i]
        val startsNumber = c.isDigit() || (c == '.' && at(i + 1).isDigit() && !isNamePart(at(i - 1)))
        if (!startsNumber) return false
        val hex = c == '0' && (at(i + 1) == 'x' || at(i + 1) == 'X')
        var j = i
        while (j < code.length) {
            val d = code[j]
            val previous = at(j - 1)
            j += when {
                d.isLetterOrDigit() || d == '_' -> 1
                d == '.' && at(j + 1).isDigit() -> 1
                (d == '+' || d == '-') && j > i && ((!hex && (previous == 'e' || previous == 'E')) || previous == 'p' || previous == 'P') -> 1
                else -> break
            }
        }
        add(CodeTokenKind.NUMBER, i, j)
        i = j
        return true
    }

    private fun name(): Boolean {
        if (!isNameStart(code[i])) return false
        val start = i
        val end = nameEnd(i)
        if (grammar.stringPrefixes.isNotEmpty() && prefixedString(start, end)) return true
        val word = code.substring(start, end)
        val key = if (grammar.caseSensitive) word else word.lowercase()
        var tokenEnd = end
        val kind = when {
            key in grammar.keywords -> CodeTokenKind.KEYWORD
            key in grammar.literals -> CodeTokenKind.LITERAL
            key in grammar.types -> CodeTokenKind.TYPE
            grammar.macros && at(end) == '!' && at(end + 1) != '=' -> CodeTokenKind.FUNCTION.also { tokenEnd = end + 1 }
            key in grammar.functions || (grammar.calls && callFollows(end)) -> CodeTokenKind.FUNCTION
            grammar.capitalizedTypes && word[0].isUpperCase() && word.any { it.isLowerCase() } -> CodeTokenKind.TYPE
            else -> null
        }
        if (kind != null) add(kind, start, tokenEnd)
        i = tokenEnd
        return true
    }

    /** Whether a `(` follows [from], after spaces on the same line. */
    private fun callFollows(from: Int): Boolean {
        var j = from
        while (at(j) == ' ' || at(j) == '\t') j++
        return at(j) == '('
    }

    private companion object {
        /** The longest character with an escape (`'\u{10FFFF}'`), and delimiter of a raw string of C++. */
        const val MAX_CHARACTER = 12
        const val MAX_CPP_DELIMITER = 16

        /** The variables of the shell of one character after `$`. */
        const val SPECIAL_VARIABLES = "@*#?$!-"

        /** What a key of YAML does not start with (a quoted key is a string followed by `:`). */
        const val NOT_PLAIN_KEY = "\"'#[{&*!|>%@`\u0000\n"

        val ANSI_C_QUOTE = Quote("'", multiline = true)
    }
}
