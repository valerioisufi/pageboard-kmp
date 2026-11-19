package io.github.valerioisufi.pageboard.html

/** How text is written in LaTeX, for what turns the equations of other applications into formulas ([OmmlToLatex], [MathmlToLatex]). */
internal object LatexWriting {

    /** Thrown by a conversion at what cannot be written: a formula with a piece missing would say something else. */
    class Unconvertible : Exception()

    /** How deep an equation may nest. */
    const val MAX_DEPTH = 64

    private val ONE_TOKEN = Regex("""^(\\[A-Za-z]+|.)$""")

    /** The names LaTeX has commands for, written upright. */
    val FUNCTIONS: Set<String> = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "coth",
        "ln", "log", "exp", "lim", "max", "min", "sup", "inf", "det", "dim", "gcd", "arg", "deg", "hom", "ker"
    )

    /** The commands whose limits are written as subscripts. */
    val LIMIT_COMMANDS: Set<String> = setOf(
        "\\lim", "\\max", "\\min", "\\sup", "\\inf", "\\sum", "\\prod", "\\coprod", "\\bigcup", "\\bigcap", "\\bigvee", "\\bigwedge"
    )

    private val SYMBOLS = mapOf(
        'α' to "\\alpha", 'β' to "\\beta", 'γ' to "\\gamma", 'δ' to "\\delta", 'ε' to "\\epsilon",
        'ϵ' to "\\epsilon", 'ζ' to "\\zeta", 'η' to "\\eta", 'θ' to "\\theta", 'ϑ' to "\\vartheta",
        'ι' to "\\iota", 'κ' to "\\kappa", 'λ' to "\\lambda", 'μ' to "\\mu", 'ν' to "\\nu",
        'ξ' to "\\xi", 'π' to "\\pi", 'ρ' to "\\rho", 'σ' to "\\sigma", 'ς' to "\\varsigma",
        'τ' to "\\tau", 'υ' to "\\upsilon", 'φ' to "\\phi", 'ϕ' to "\\phi", 'χ' to "\\chi",
        'ψ' to "\\psi", 'ω' to "\\omega", 'Γ' to "\\Gamma", 'Δ' to "\\Delta", 'Θ' to "\\Theta",
        'Λ' to "\\Lambda", 'Ξ' to "\\Xi", 'Π' to "\\Pi", 'Σ' to "\\Sigma", 'Υ' to "\\Upsilon",
        'Φ' to "\\Phi", 'Ψ' to "\\Psi", 'Ω' to "\\Omega",
        '∞' to "\\infty", '≤' to "\\leq", '≥' to "\\geq", '≠' to "\\neq", '≈' to "\\approx",
        '≡' to "\\equiv", '∼' to "\\sim", '≃' to "\\simeq", '≅' to "\\cong", '≪' to "\\ll",
        '≫' to "\\gg", '∝' to "\\propto", '×' to "\\times", '⋅' to "\\cdot", '·' to "\\cdot",
        '∗' to "*", '÷' to "\\div", '±' to "\\pm", '∓' to "\\mp", '→' to "\\to",
        '←' to "\\leftarrow", '↔' to "\\leftrightarrow", '↦' to "\\mapsto", '⇒' to "\\Rightarrow",
        '⇐' to "\\Leftarrow", '⇔' to "\\Leftrightarrow", '⟶' to "\\longrightarrow",
        '⟵' to "\\longleftarrow", '⟹' to "\\Longrightarrow", '⟺' to "\\Longleftrightarrow",
        '∂' to "\\partial", '∇' to "\\nabla", '∈' to "\\in", '∉' to "\\notin", '∋' to "\\ni",
        '⊂' to "\\subset", '⊃' to "\\supset", '⊆' to "\\subseteq", '⊇' to "\\supseteq",
        '∪' to "\\cup", '∩' to "\\cap", '∖' to "\\setminus", '∅' to "\\emptyset", '∀' to "\\forall",
        '∃' to "\\exists", '¬' to "\\neg", '∧' to "\\wedge", '∨' to "\\vee", '…' to "\\ldots",
        '⋯' to "\\cdots", '⋮' to "\\vdots", '⋱' to "\\ddots", '∘' to "\\circ", '⊕' to "\\oplus",
        '⊗' to "\\otimes", '⊥' to "\\perp", '∥' to "\\parallel", '∠' to "\\angle", 'ℓ' to "\\ell",
        'ℏ' to "\\hbar", '⟨' to "\\langle", '⟩' to "\\rangle", '‖' to "\\|", '∣' to "|",
        '∑' to "\\sum", '∏' to "\\prod", '∐' to "\\coprod", '∫' to "\\int", '∬' to "\\iint",
        '∭' to "\\iiint", '∮' to "\\oint", '⋃' to "\\bigcup", '⋂' to "\\bigcap", '⋁' to "\\bigvee",
        '⋀' to "\\bigwedge", '√' to "\\surd", 'ℝ' to "\\mathbb{R}", 'ℕ' to "\\mathbb{N}",
        'ℤ' to "\\mathbb{Z}", 'ℚ' to "\\mathbb{Q}", 'ℂ' to "\\mathbb{C}", '′' to "\\prime",
        '″' to "\\prime\\prime", '−' to "-", '{' to "\\{", '}' to "\\}", '%' to "\\%", '#' to "\\#", '&' to "\\&",
        '_' to "\\_", '^' to "\\wedge", '~' to "\\sim", '\\' to "\\backslash"
    )

    private val DELIMITERS = mapOf(
        "{" to "\\{", "}" to "\\}", "‖" to "\\|", "∣" to "|", "⟨" to "\\langle", "⟩" to "\\rangle",
        "⌈" to "\\lceil", "⌉" to "\\rceil", "⌊" to "\\lfloor", "⌋" to "\\rfloor"
    )

    /** [text] after [out], with the space a command needs before a letter. */
    fun append(out: StringBuilder, text: String) {
        if (text.isEmpty()) return
        if (text[0].isLetter() && endsWithCommand(out)) out.append(' ')
        out.append(text)
    }

    private fun endsWithCommand(out: CharSequence): Boolean {
        var i = out.length - 1
        while (i >= 0 && out[i].isLetter()) i--
        return i >= 0 && i < out.length - 1 && out[i] == '\\'
    }

    /** [text] as the base of a subscript or a superscript. */
    fun base(text: String): String = if (ONE_TOKEN.matches(text)) text else "{$text}"

    /** A subscript and a superscript, each when it is not empty. */
    fun scripts(sub: String, sup: String): String =
        (if (sub.isEmpty()) "" else "_{$sub}") + (if (sup.isEmpty()) "" else "^{$sup}")

    /** The sign [sign] as a delimiter after `\left` or `\right`; `.` for none. */
    fun delimiter(sign: String): String = if (sign.isEmpty()) "." else DELIMITERS[sign] ?: sign

    /** [text] written in LaTeX: its signs as commands, its spaces left to LaTeX. */
    fun symbolsOf(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // The letters of the block of mathematical italics, as some files have them
            val italic = if (c.isHighSurrogate() && i + 1 < text.length) mathItalic(c, text[i + 1]) else null
            when {
                italic != null -> {
                    append(out, italic.toString())
                    i += 2
                    continue
                }
                // The spaces of a formula are the business of LaTeX, and the applying of a function is not seen
                c.isWhitespace() || c == '\u00A0' || c in '\u2061'..'\u2064' || c == '\u200B' -> Unit
                else -> append(out, SYMBOLS[c] ?: c.toString())
            }
            i++
        }
        return out.toString()
    }

    /** The letter a surrogate pair of the mathematical italics stands for, or `null`. */
    private fun mathItalic(high: Char, low: Char): Char? {
        if (high != '\uD835') return null
        return when (low.code) {
            in 0xDC34..0xDC4D -> 'A' + (low.code - 0xDC34)
            in 0xDC4E..0xDC67 -> 'a' + (low.code - 0xDC4E)
            else -> null
        }
    }
}
