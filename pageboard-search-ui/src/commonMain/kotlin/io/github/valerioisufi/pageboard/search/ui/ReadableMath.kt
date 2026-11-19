package io.github.valerioisufi.pageboard.search.ui

import io.github.valerioisufi.pageboard.model.text.RichTextParser

/**
 * The formulas of a text of the notes written as plain math on one line, for the snippets of the
 * search: `$\cos x \le \frac{\sin x}{x}$` reads `cos x ≤ (sin x)/x` (Pure Fabrication). The
 * formulas are the ones [RichTextParser] finds, as the page draws them; the rest of the text is
 * kept as it is.
 */
internal object ReadableMath {

    /** [text] with each of its formulas written as plain math. */
    fun of(text: String): String {
        val formulas = RichTextParser.parse(text).filter { it.kind == RichTextParser.Kind.FORMULA }
        if (formulas.isEmpty()) return text
        val out = StringBuilder(text.length)
        var at = 0
        for (formula in formulas) {
            if (formula.start < at) continue
            out.append(text, at, formula.start)
            out.append(latex(text.substring(formula.contentStart, formula.contentEnd)))
            at = formula.end
        }
        out.append(text, at, text.length)
        return out.toString()
    }

    /** The LaTeX of a formula, [source], written as plain math. */
    fun latex(source: String): String =
        Reader(source).readUntil(end = null, depth = 0).replace(SPACES, " ").trim()

    /** A reader of the LaTeX of [source], from its start. */
    private class Reader(private val source: String) {
        private var at = 0

        /** Whether the command read last was an `\end`, closing the environment being read. */
        private var environmentEnded = false

        /**
         * What follows up to [end] (a closing brace or bracket, consumed) or the end of the formula;
         * in an environment ([inEnvironment]), up to its `\end`, its cells apart by [cell].
         */
        fun readUntil(end: Char?, depth: Int, cell: String = " ", inEnvironment: Boolean = false): String {
            val out = StringBuilder()
            while (at < source.length) {
                val ch = source[at]
                if (ch == end) {
                    at++
                    return out.toString()
                }
                when (ch) {
                    '{' -> {
                        at++
                        out.append(group(depth))
                    }
                    '}' -> at++
                    '^' -> {
                        at++
                        out.append(script(argument(depth), SUPERSCRIPTS, "^"))
                    }
                    '_' -> {
                        at++
                        out.append(script(argument(depth), SUBSCRIPTS, "_"))
                    }
                    '&' -> {
                        at++
                        out.append(cell)
                    }
                    '~' -> {
                        at++
                        out.append(' ')
                    }
                    '\\' -> {
                        val read = command(depth)
                        if (environmentEnded) {
                            environmentEnded = false
                            if (inEnvironment) return out.toString()
                        }
                        out.append(read)
                    }
                    else -> {
                        at++
                        out.append(ch)
                    }
                }
            }
            return out.toString()
        }

        /** The content of a group whose `{` was read. */
        private fun group(depth: Int): String {
            if (depth >= MAX_DEPTH) {
                // Kept as written, up to its brace: the text is the user's
                val start = at
                var open = 1
                while (at < source.length && open > 0) {
                    when (source[at]) {
                        '{' -> open++
                        '}' -> open--
                    }
                    at++
                }
                return source.substring(start, if (open == 0) at - 1 else at)
            }
            return readUntil('}', depth + 1)
        }

        /** The argument of a command or of a script: a group, a command or one character. */
        private fun argument(depth: Int): String {
            while (at < source.length && source[at] == ' ') at++
            if (at >= source.length) return ""
            return when (source[at]) {
                '{' -> {
                    at++
                    group(depth)
                }
                // A command as the argument of a command, `\frac\alpha2`: one level more, as a group
                '\\' -> if (depth >= MAX_DEPTH) source[at++].toString() else command(depth + 1)
                else -> source[at++].toString()
            }
        }

        /** An optional argument in brackets, `null` when there is none. */
        private fun optional(depth: Int): String? {
            if (at >= source.length || source[at] != '[') return null
            at++
            if (depth >= MAX_DEPTH) {
                val close = source.indexOf(']', at).takeIf { it >= 0 } ?: source.length
                return source.substring(at, close).also { at = (close + 1).coerceAtMost(source.length) }
            }
            return readUntil(']', depth + 1)
        }

        /** A command, from its backslash. */
        private fun command(depth: Int): String {
            at++
            if (at >= source.length) return ""
            val first = source[at]
            if (!first.isLetter()) {
                at++
                return when (first) {
                    ',', ';', ':', ' ' -> " "
                    '!' -> ""
                    '\\' -> "; "
                    else -> first.toString()
                }
            }
            val start = at
            while (at < source.length && source[at].isLetter()) at++
            val name = source.substring(start, at)
            SYMBOLS[name]?.let { return it }
            if (name in FUNCTIONS) return if (at < source.length && source[at].isLetterOrDigit()) "$name " else name
            return when (name) {
                "frac", "dfrac", "tfrac", "cfrac" -> {
                    val numerator = argument(depth).trim()
                    val denominator = argument(depth).trim()
                    "${term(numerator)}/${term(denominator)}"
                }
                "binom", "dbinom", "tbinom" -> {
                    val n = argument(depth).trim()
                    val k = argument(depth).trim()
                    "C($n, $k)"
                }
                "sqrt" -> {
                    val index = optional(depth)?.trim()
                    val radicand = argument(depth).trim()
                    val root = when (index) {
                        null, "", "2" -> "√"
                        "3" -> "∛"
                        "4" -> "∜"
                        else -> script(index, SUPERSCRIPTS, "^") + "√"
                    }
                    root + term(radicand)
                }
                "mathbb" -> argument(depth).let { content -> content.map { DOUBLE_STRUCK[it] ?: it }.joinToString("") }
                "text", "textrm", "textit", "textbf", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "mathcal",
                "mathscr", "mathfrak", "boldsymbol", "bm", "mbox", "hbox", "overline", "underline", "mathop",
                "widehat", "widetilde", "hat", "tilde", "bar", "vec", "dot", "ddot", "overrightarrow", "boxed",
                "cancel", "bcancel", "xcancel" -> argument(depth)
                "operatorname" -> {
                    if (at < source.length && source[at] == '*') at++
                    argument(depth)
                }
                "underbrace", "overbrace" -> {
                    // The label under or over the brace, after what it labels
                    val content = argument(depth)
                    skipSpaces()
                    if (at < source.length && (source[at] == '_' || source[at] == '^')) {
                        at++
                        "$content (${argument(depth).trim()})"
                    } else {
                        content
                    }
                }
                "overset", "underset", "stackrel" -> {
                    argument(depth)
                    argument(depth)
                }
                "not" -> argument(depth).trim().let { negated -> NEGATIONS[negated] ?: "$negated̸" }
                "textcolor" -> {
                    argument(depth)
                    argument(depth)
                }
                "color" -> {
                    argument(depth)
                    ""
                }
                "left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr",
                "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "nonumber", "notag", "phantom" -> ""
                "begin" -> environment(depth)
                "end" -> {
                    argument(depth)
                    environmentEnded = true
                    ""
                }
                "quad", "qquad", "enspace", "thinspace" -> " "
                else -> unknown(name, depth)
            }
        }

        /**
         * An environment whose `\begin` was read, up to its `\end`: a matrix in its brackets, its
         * rows apart by `;`; the cases in braces, each value and its condition apart by a comma.
         */
        private fun environment(depth: Int): String {
            val name = argument(depth).trim().removeSuffix("*")
            if (name == "array") argument(depth)
            if (depth >= MAX_DEPTH) return ""
            val (open, close, cell) = ENVIRONMENTS[name] ?: Triple("", "", " ")
            environmentEnded = false
            val body = readUntil(end = null, depth = depth + 1, cell = cell, inEnvironment = true)
                .replace(SPACES, " ").replace(" ,", ",").replace(" ;", ";").trim().removeSuffix(";").trim()
            return " $open$body$close "
        }

        /**
         * A command not known: by the content of its arguments in braces, which a command of
         * formatting or of a package would show; by its name when it has none, as a word or a
         * symbol of the formula would be.
         */
        private fun unknown(name: String, depth: Int): String {
            val after = at
            skipSpaces()
            if (at >= source.length || source[at] != '{') {
                at = after
                return name
            }
            val out = StringBuilder()
            while (at < source.length && source[at] == '{') {
                if (out.isNotEmpty()) out.append(' ')
                out.append(argument(depth))
                skipSpaces()
            }
            return out.toString()
        }

        private fun skipSpaces() {
            while (at < source.length && source[at] == ' ') at++
        }

        /** [content] alone, in parentheses when it is more than one term. */
        private fun term(content: String): String =
            if (content.length <= 1 || content.all { it.isLetterOrDigit() || it == '.' } || content.isWrapped()) content else "($content)"

        /** [content] as a superscript or subscript: by its characters when all have one, else after [mark]. */
        private fun script(content: String, characters: Map<Char, Char>, mark: String): String {
            val trimmed = content.trim()
            if (trimmed.isEmpty()) return ""
            if (trimmed.all { it in characters }) return trimmed.map { characters.getValue(it) }.joinToString("")
            return mark + if (trimmed.length == 1 || trimmed.isWrapped()) trimmed else "($trimmed)"
        }

        /** Whether this is one group in parentheses or brackets, `(a+b)` but not `(a)+(b)`. */
        private fun String.isWrapped(): Boolean {
            val open = firstOrNull() ?: return false
            val close = when (open) {
                '(' -> ')'
                '[' -> ']'
                else -> return false
            }
            if (length < 2 || last() != close) return false
            var level = 0
            for (i in 0 until lastIndex) {
                if (this[i] == open) level++ else if (this[i] == close) level--
                if (level == 0) return false
            }
            return true
        }
    }

    /** How deep the groups of a formula are read; deeper ones are kept as written. */
    private const val MAX_DEPTH = 32

    private val SPACES = Regex("\\s+")

    /** The names LaTeX writes upright. */
    private val FUNCTIONS: Set<String> = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "coth",
        "ln", "log", "lg", "exp", "lim", "liminf", "limsup", "max", "min", "sup", "inf", "det", "dim", "gcd",
        "arg", "deg", "hom", "ker", "Pr", "mod", "bmod", "pmod"
    )

    /** The commands written by one character. */
    private val SYMBOLS: Map<String, String> = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ",
        "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω", "Gamma" to "Γ", "Delta" to "Δ",
        "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
        "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "le" to "≤", "leq" to "≤", "leqslant" to "≤", "ge" to "≥", "geq" to "≥", "geqslant" to "≥",
        "ne" to "≠", "neq" to "≠", "approx" to "≈", "equiv" to "≡", "sim" to "∼", "simeq" to "≃",
        "cong" to "≅", "ll" to "≪", "gg" to "≫", "propto" to "∝", "times" to "×", "cdot" to "·",
        "ast" to "∗", "div" to "÷", "pm" to "±", "mp" to "∓", "to" to "→", "rightarrow" to "→",
        "leftarrow" to "←", "gets" to "←", "leftrightarrow" to "↔", "mapsto" to "↦", "Rightarrow" to "⇒",
        "implies" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "iff" to "⇔",
        "longrightarrow" to "⟶", "longleftarrow" to "⟵", "Longrightarrow" to "⟹",
        "Longleftrightarrow" to "⟺", "uparrow" to "↑", "downarrow" to "↓",
        "partial" to "∂", "nabla" to "∇", "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂",
        "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩",
        "setminus" to "∖", "emptyset" to "∅", "varnothing" to "∅", "forall" to "∀", "exists" to "∃",
        "nexists" to "∄", "neg" to "¬", "lnot" to "¬", "wedge" to "∧", "land" to "∧", "vee" to "∨",
        "lor" to "∨", "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
        "circ" to "∘", "oplus" to "⊕", "otimes" to "⊗", "perp" to "⊥", "parallel" to "∥", "mid" to "∣",
        "angle" to "∠", "ell" to "ℓ", "hbar" to "ℏ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ",
        "infty" to "∞", "langle" to "⟨", "rangle" to "⟩", "lceil" to "⌈", "rceil" to "⌉",
        "lfloor" to "⌊", "rfloor" to "⌋", "vert" to "|", "lvert" to "|", "rvert" to "|", "Vert" to "‖",
        "lVert" to "‖", "rVert" to "‖", "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫",
        "iint" to "∬", "iiint" to "∭", "oint" to "∮", "bigcup" to "⋃", "bigcap" to "⋂", "bigvee" to "⋁",
        "bigwedge" to "⋀", "bigoplus" to "⊕", "bigotimes" to "⊗", "surd" to "√", "prime" to "′",
        "degree" to "°", "star" to "⋆", "bullet" to "•", "triangle" to "△", "square" to "□",
        "colon" to ":", "lbrace" to "{", "rbrace" to "}", "backslash" to "\\"
    )

    /** The environments by their delimiters and what is between their cells; the others have none. */
    private val ENVIRONMENTS: Map<String, Triple<String, String, String>> = mapOf(
        "pmatrix" to Triple("(", ")", " "),
        "bmatrix" to Triple("[", "]", " "),
        "Bmatrix" to Triple("{", "}", " "),
        "vmatrix" to Triple("|", "|", " "),
        "Vmatrix" to Triple("‖", "‖", " "),
        "cases" to Triple("{ ", " }", ", "),
        "dcases" to Triple("{ ", " }", ", ")
    )

    /** The symbols `\not` makes by a character of their own. */
    private val NEGATIONS: Map<String, String> = mapOf(
        "=" to "≠", "∈" to "∉", "⊂" to "⊄", "⊃" to "⊅", "⊆" to "⊈", "⊇" to "⊉", "≡" to "≢", "∼" to "≁",
        "≃" to "≄", "≅" to "≇", "≈" to "≉", "<" to "≮", ">" to "≯", "≤" to "≰", "≥" to "≱", "∃" to "∄",
        "|" to "∤", "∣" to "∤", "∥" to "∦"
    )

    private val SUPERSCRIPTS: Map<Char, Char> = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷',
        '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '−' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ', '′' to '′', '*' to '*', '∗' to '*'
    )

    private val SUBSCRIPTS: Map<Char, Char> = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇',
        '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '−' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ', 'h' to 'ₕ', 'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ',
        'n' to 'ₙ', 'p' to 'ₚ', 's' to 'ₛ', 't' to 'ₜ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'r' to 'ᵣ', 'u' to 'ᵤ', 'v' to 'ᵥ'
    )

    private val DOUBLE_STRUCK: Map<Char, Char> = mapOf(
        'R' to 'ℝ', 'N' to 'ℕ', 'Z' to 'ℤ', 'Q' to 'ℚ', 'C' to 'ℂ', 'P' to 'ℙ', 'H' to 'ℍ'
    )
}
