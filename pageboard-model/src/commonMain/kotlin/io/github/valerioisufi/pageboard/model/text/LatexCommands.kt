package io.github.valerioisufi.pageboard.model.text

/**
 * A LaTeX command offered by the autocomplete while writing a formula: [name] (without `\`),
 * the snippet [insert] pasted in place of `\prefix`, the caret offset [cursorOffset] inside
 * [insert], and an optional symbol or template [preview].
 */
public data class LatexCommand(
    public val name: String,
    public val insert: String = "\\$name",
    public val cursorOffset: Int = insert.length,
    public val preview: String = ""
)

/**
 * Matches and applies LaTeX command completions while typing inside a formula.
 */
public object LatexCommands {

    /**
     * An active completion match at `[tokenStart]..[tokenEnd)` (including the leading `\`) for
     * [prefix] (letters after `\`), offering up to 8 [candidates].
     */
    public data class Match(
        public val tokenStart: Int,
        public val tokenEnd: Int,
        public val prefix: String,
        public val candidates: List<LatexCommand>
    )

    public val ALL: List<LatexCommand> = listOf(
        // Structures & fractions
        LatexCommand("frac", "\\frac{}{}", cursorOffset = 6, preview = "a/b"),
        LatexCommand("dfrac", "\\dfrac{}{}", cursorOffset = 7, preview = "a/b"),
        LatexCommand("tfrac", "\\tfrac{}{}", cursorOffset = 7, preview = "a/b"),
        LatexCommand("sqrt", "\\sqrt{}", cursorOffset = 6, preview = "√"),
        LatexCommand("binom", "\\binom{}{}", cursorOffset = 7, preview = "(n k)"),
        LatexCommand("overline", "\\overline{}", cursorOffset = 10, preview = "x̄"),
        LatexCommand("underline", "\\underline{}", cursorOffset = 11, preview = "x̲"),
        LatexCommand("vec", "\\vec{}", cursorOffset = 5, preview = "x⃗"),
        LatexCommand("hat", "\\hat{}", cursorOffset = 5, preview = "x̂"),
        LatexCommand("bar", "\\bar{}", cursorOffset = 5, preview = "x̄"),
        LatexCommand("tilde", "\\tilde{}", cursorOffset = 7, preview = "x̃"),
        LatexCommand("dot", "\\dot{}", cursorOffset = 5, preview = "ẋ"),
        LatexCommand("text", "\\text{}", cursorOffset = 6, preview = "Abc"),
        LatexCommand("mathrm", "\\mathrm{}", cursorOffset = 8, preview = "A"),
        LatexCommand("mathbf", "\\mathbf{}", cursorOffset = 8, preview = "A"),
        LatexCommand("mathbb", "\\mathbb{}", cursorOffset = 8, preview = "ℝ"),
        LatexCommand("mathcal", "\\mathcal{}", cursorOffset = 9, preview = "𝒜"),
        LatexCommand("left", "\\left", preview = "("),
        LatexCommand("right", "\\right", preview = ")"),
        LatexCommand("begin", "\\begin{}\n\n\\end{}", cursorOffset = 7, preview = "env"),
        LatexCommand("end", "\\end{}", cursorOffset = 5, preview = "env"),

        // Big operators & functions
        LatexCommand("sum", "\\sum", preview = "∑"),
        LatexCommand("prod", "\\prod", preview = "∏"),
        LatexCommand("int", "\\int", preview = "∫"),
        LatexCommand("iint", "\\iint", preview = "∬"),
        LatexCommand("oint", "\\oint", preview = "∮"),
        LatexCommand("lim", "\\lim", preview = "lim"),
        LatexCommand("sin", "\\sin", preview = "sin"),
        LatexCommand("cos", "\\cos", preview = "cos"),
        LatexCommand("tan", "\\tan", preview = "tan"),
        LatexCommand("log", "\\log", preview = "log"),
        LatexCommand("ln", "\\ln", preview = "ln"),
        LatexCommand("exp", "\\exp", preview = "exp"),
        LatexCommand("max", "\\max", preview = "max"),
        LatexCommand("min", "\\min", preview = "min"),
        LatexCommand("det", "\\det", preview = "det"),

        // Greek letters
        LatexCommand("alpha", "\\alpha", preview = "α"),
        LatexCommand("beta", "\\beta", preview = "β"),
        LatexCommand("gamma", "\\gamma", preview = "γ"),
        LatexCommand("delta", "\\delta", preview = "δ"),
        LatexCommand("epsilon", "\\epsilon", preview = "ϵ"),
        LatexCommand("varepsilon", "\\varepsilon", preview = "ε"),
        LatexCommand("zeta", "\\zeta", preview = "ζ"),
        LatexCommand("eta", "\\eta", preview = "η"),
        LatexCommand("theta", "\\theta", preview = "θ"),
        LatexCommand("iota", "\\iota", preview = "ι"),
        LatexCommand("kappa", "\\kappa", preview = "κ"),
        LatexCommand("lambda", "\\lambda", preview = "λ"),
        LatexCommand("mu", "\\mu", preview = "μ"),
        LatexCommand("nu", "\\nu", preview = "ν"),
        LatexCommand("xi", "\\xi", preview = "ξ"),
        LatexCommand("pi", "\\pi", preview = "π"),
        LatexCommand("rho", "\\rho", preview = "ρ"),
        LatexCommand("sigma", "\\sigma", preview = "σ"),
        LatexCommand("tau", "\\tau", preview = "τ"),
        LatexCommand("upsilon", "\\upsilon", preview = "υ"),
        LatexCommand("phi", "\\phi", preview = "ϕ"),
        LatexCommand("varphi", "\\varphi", preview = "φ"),
        LatexCommand("chi", "\\chi", preview = "χ"),
        LatexCommand("psi", "\\psi", preview = "ψ"),
        LatexCommand("omega", "\\omega", preview = "ω"),
        LatexCommand("Gamma", "\\Gamma", preview = "Γ"),
        LatexCommand("Delta", "\\Delta", preview = "Δ"),
        LatexCommand("Theta", "\\Theta", preview = "Θ"),
        LatexCommand("Lambda", "\\Lambda", preview = "Λ"),
        LatexCommand("Pi", "\\Pi", preview = "Π"),
        LatexCommand("Sigma", "\\Sigma", preview = "Σ"),
        LatexCommand("Phi", "\\Phi", preview = "Φ"),
        LatexCommand("Psi", "\\Psi", preview = "Ψ"),
        LatexCommand("Omega", "\\Omega", preview = "Ω"),

        // Operators, relations, logic & arrows
        LatexCommand("infty", "\\infty", preview = "∞"),
        LatexCommand("partial", "\\partial", preview = "∂"),
        LatexCommand("nabla", "\\nabla", preview = "∇"),
        LatexCommand("cdot", "\\cdot", preview = "·"),
        LatexCommand("times", "\\times", preview = "×"),
        LatexCommand("div", "\\div", preview = "÷"),
        LatexCommand("pm", "\\pm", preview = "±"),
        LatexCommand("mp", "\\mp", preview = "∓"),
        LatexCommand("leq", "\\leq", preview = "≤"),
        LatexCommand("geq", "\\geq", preview = "≥"),
        LatexCommand("neq", "\\neq", preview = "≠"),
        LatexCommand("approx", "\\approx", preview = "≈"),
        LatexCommand("equiv", "\\equiv", preview = "≡"),
        LatexCommand("sim", "\\sim", preview = "∼"),
        LatexCommand("propto", "\\propto", preview = "∝"),
        LatexCommand("in", "\\in", preview = "∈"),
        LatexCommand("notin", "\\notin", preview = "∉"),
        LatexCommand("subset", "\\subset", preview = "⊂"),
        LatexCommand("subseteq", "\\subseteq", preview = "⊆"),
        LatexCommand("supset", "\\supset", preview = "⊃"),
        LatexCommand("cup", "\\cup", preview = "∪"),
        LatexCommand("cap", "\\cap", preview = "∩"),
        LatexCommand("emptyset", "\\emptyset", preview = "∅"),
        LatexCommand("to", "\\to", preview = "→"),
        LatexCommand("leftarrow", "\\leftarrow", preview = "←"),
        LatexCommand("rightarrow", "\\rightarrow", preview = "→"),
        LatexCommand("leftrightarrow", "\\leftrightarrow", preview = "↔"),
        LatexCommand("Leftarrow", "\\Leftarrow", preview = "⇐"),
        LatexCommand("Rightarrow", "\\Rightarrow", preview = "⇒"),
        LatexCommand("Leftrightarrow", "\\Leftrightarrow", preview = "⇔"),
        LatexCommand("forall", "\\forall", preview = "∀"),
        LatexCommand("exists", "\\exists", preview = "∃"),
        LatexCommand("neg", "\\neg", preview = "¬"),
        LatexCommand("land", "\\land", preview = "∧"),
        LatexCommand("lor", "\\lor", preview = "∨"),
        LatexCommand("dots", "\\dots", preview = "…"),
        LatexCommand("cdots", "\\cdots", preview = "⋯"),
        LatexCommand("ldots", "\\ldots", preview = "…")
    )

    private const val MAX_CANDIDATES = 8

    /**
     * Finds matching LaTeX commands right before [caret] inside [formulaRange] of [source], or
     * `null` when [caret] is outside [formulaRange], not after a single `\letters` token, no
     * command matches, or the sole candidate is already completely typed without braces.
     */
    public fun match(source: String, caret: Int, formulaRange: IntRange): Match? {
        if (formulaRange.isEmpty()) return null
        val rangeStart = formulaRange.first.coerceAtLeast(0)
        val rangeEnd = (formulaRange.last + 1).coerceAtMost(source.length)
        if (caret !in rangeStart..rangeEnd) return null

        var i = caret
        while (i > rangeStart && source[i - 1].isAsciiLetter()) i--
        // Require at least one letter after `\` so bare `\` or `\\` (line break) does not trigger
        if (i == caret || i <= rangeStart) return null
        val backslash = i - 1
        if (source[backslash] != '\\') return null
        // Preceded by another `\` (`\\command`): escaped backslash followed by literal command
        var backslashes = 0
        var b = backslash
        while (b >= rangeStart && source[b] == '\\') {
            backslashes++
            b--
        }
        if (backslashes % 2 == 0) return null

        val prefix = source.substring(i, caret)
        val exact = ALL.filter { it.name.startsWith(prefix, ignoreCase = false) }
        val ci = if (exact.isEmpty()) ALL.filter { it.name.startsWith(prefix, ignoreCase = true) } else {
            exact + ALL.filter { !it.name.startsWith(prefix, ignoreCase = false) && it.name.startsWith(prefix, ignoreCase = true) }
        }
        val candidates = ci.take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return null
        val sole = candidates.singleOrNull()
        if (sole != null && sole.name == prefix && sole.insert == "\\$prefix") return null
        return Match(backslash, caret, prefix, candidates)
    }

    /** Replaces `[match.tokenStart]..[match.tokenEnd)` in [source] with [command], returning the updated text and new caret offset. */
    public fun apply(source: String, match: Match, command: LatexCommand): Pair<String, Int> {
        val next = source.substring(0, match.tokenStart) + command.insert + source.substring(match.tokenEnd)
        return next to (match.tokenStart + command.cursorOffset)
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
