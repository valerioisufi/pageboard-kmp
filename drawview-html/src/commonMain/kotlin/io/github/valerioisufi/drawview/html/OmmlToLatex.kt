package io.github.valerioisufi.drawview.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode
import com.fleeksoft.ksoup.parser.Parser

/**
 * An equation of Word (OMML, the XML Word keeps in a comment of what it copies) written in LaTeX, for
 * the formulas of the notation.
 *
 * An element it does not know makes the whole equation fail: a formula with a piece missing would
 * say something else, and a placeholder tells that something is missing.
 */
internal object OmmlToLatex {

    /** An equation written in LaTeX: its [tex], and whether it is on a line of its own ([display]). */
    data class Equation(val tex: String, val display: Boolean)

    /** Thrown inside the conversion by what cannot be written. */
    private class Unconvertible : Exception()

    /** How deep an equation may nest. */
    private const val MAX_DEPTH = 64

    private const val DEFAULT_NARY = "∫"
    private const val DEFAULT_ACCENT = "̂"
    private const val VALUE = "m:val"

    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "coth",
        "ln", "log", "exp", "lim", "max", "min", "sup", "inf", "det", "dim", "gcd", "arg", "deg", "hom", "ker"
    )

    /** The functions whose limits go under them: those of [LimLow][limLow] written as subscripts. */
    private val LIMIT_FUNCTIONS = setOf("\\lim", "\\max", "\\min", "\\sup", "\\inf")

    private val NARY = mapOf(
        "∑" to "\\sum", "∏" to "\\prod", "∐" to "\\coprod", "∫" to "\\int", "∬" to "\\iint",
        "∭" to "\\iiint", "∮" to "\\oint", "⋃" to "\\bigcup", "⋂" to "\\bigcap",
        "⋁" to "\\bigvee", "⋀" to "\\bigwedge"
    )

    private val ACCENTS = mapOf(
        "̂" to "\\hat", "̃" to "\\tilde", "̄" to "\\bar", "̅" to "\\overline", "̇" to "\\dot",
        "̈" to "\\ddot", "́" to "\\acute", "̀" to "\\grave", "̆" to "\\breve", "̌" to "\\check",
        "⃗" to "\\vec"
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
        '≡' to "\\equiv", '∼' to "\\sim", '∝' to "\\propto", '×' to "\\times", '⋅' to "\\cdot",
        '·' to "\\cdot", '÷' to "\\div", '±' to "\\pm", '∓' to "\\mp", '→' to "\\to",
        '←' to "\\leftarrow", '↔' to "\\leftrightarrow", '⇒' to "\\Rightarrow", '⇐' to "\\Leftarrow",
        '⇔' to "\\Leftrightarrow", '⟶' to "\\longrightarrow", '⟵' to "\\longleftarrow", '∂' to "\\partial",
        '∇' to "\\nabla", '∈' to "\\in", '∉' to "\\notin", '⊂' to "\\subset", '⊃' to "\\supset",
        '⊆' to "\\subseteq", '⊇' to "\\supseteq", '∪' to "\\cup", '∩' to "\\cap", '∅' to "\\emptyset",
        '∀' to "\\forall", '∃' to "\\exists", '¬' to "\\neg", '∧' to "\\wedge", '∨' to "\\vee",
        '…' to "\\ldots", '⋯' to "\\cdots", '∘' to "\\circ", '⊕' to "\\oplus", '⊗' to "\\otimes",
        'ℓ' to "\\ell", '⟨' to "\\langle", '⟩' to "\\rangle", '‖' to "\\|", '∑' to "\\sum",
        '∏' to "\\prod", '∫' to "\\int", '√' to "\\surd", 'ℝ' to "\\mathbb{R}", 'ℕ' to "\\mathbb{N}",
        'ℤ' to "\\mathbb{Z}", 'ℚ' to "\\mathbb{Q}", 'ℂ' to "\\mathbb{C}", '′' to "'", '−' to "-",
        '{' to "\\{", '}' to "\\}", '%' to "\\%", '#' to "\\#", '&' to "\\&", '_' to "\\_", '^' to "\\wedge", '~' to "\\sim",
        '\\' to "\\backslash"
    )

    private val DELIMITERS = mapOf(
        "{" to "\\{", "}" to "\\}", "‖" to "\\|", "⟨" to "\\langle", "⟩" to "\\rangle",
        "⌈" to "\\lceil", "⌉" to "\\rceil", "⌊" to "\\lfloor", "⌋" to "\\rfloor"
    )

    private val ONE_TOKEN = Regex("""^(\\[A-Za-z]+|.)$""")
    private val LETTERS = Regex("""^[A-Za-z]+$""")

    /** The equation in [comment], the text of a comment of Word, or `null` when it cannot be written. */
    fun convert(comment: String): Equation? {
        val start = comment.indexOf('<')
        val end = comment.lastIndexOf("<![endif]").takeIf { it > start } ?: comment.length
        if (start < 0) return null
        return try {
            val root = Ksoup.parse(comment.substring(start, end), parser = Parser.xmlParser())
            val display = root.getAllElements().any { it.normalName() == "m:omathpara" }
            val equations = root.getAllElements().filter { it.normalName() == "m:omath" }
            val tex = equations.joinToString(" \\\\ ") { contentOf(it, 0) }.trim()
            if (tex.isEmpty()) null else Equation(tex, display)
        } catch (_: Unconvertible) {
            null
        }
    }

    /** What the children of [element] write, joined. */
    private fun contentOf(element: Element?, depth: Int): String {
        if (element == null) return ""
        if (depth > MAX_DEPTH) throw Unconvertible()
        val out = StringBuilder()
        for (child in element.children()) append(out, write(child, depth + 1))
        return out.toString()
    }

    /** [text] after [out], with the space a command needs before a letter. */
    private fun append(out: StringBuilder, text: String) {
        if (text.isEmpty()) return
        if (text[0].isLetter() && endsWithCommand(out)) out.append(' ')
        out.append(text)
    }

    private fun endsWithCommand(out: CharSequence): Boolean {
        var i = out.length - 1
        while (i >= 0 && out[i].isLetter()) i--
        return i >= 0 && i < out.length - 1 && out[i] == '\\'
    }

    private fun child(element: Element, name: String): Element? = element.children().firstOrNull { it.normalName() == name }

    private fun argument(element: Element, name: String, depth: Int): String = contentOf(child(element, name), depth)

    /** The value of the property [name] of [element], kept in its element of properties [properties]. */
    private fun property(element: Element, properties: String, name: String): String? =
        child(element, properties)?.let { child(it, name) }?.attr(VALUE)

    /** [text] as the base of a subscript or a superscript. */
    private fun base(text: String): String = if (ONE_TOKEN.matches(text)) text else "{$text}"

    private fun scripts(sub: String, sup: String): String =
        (if (sub.isEmpty()) "" else "_{$sub}") + (if (sup.isEmpty()) "" else "^{$sup}")

    private fun write(element: Element, depth: Int): String {
        val name = element.normalName()
        return when (name) {
            "m:r" -> run(element)
            "m:f" -> fraction(element, depth)
            "m:ssup" -> base(argument(element, "m:e", depth)) + scripts("", argument(element, "m:sup", depth))
            "m:ssub" -> base(argument(element, "m:e", depth)) + scripts(argument(element, "m:sub", depth), "")
            "m:ssubsup" -> base(argument(element, "m:e", depth)) + scripts(argument(element, "m:sub", depth), argument(element, "m:sup", depth))
            "m:spre" -> "{}" + scripts(argument(element, "m:sub", depth), argument(element, "m:sup", depth)) + base(argument(element, "m:e", depth))
            "m:rad" -> radical(element, depth)
            "m:nary" -> nary(element, depth)
            "m:d" -> delimiters(element, depth)
            "m:m" -> matrix(element, depth)
            "m:eqarr" -> element.children().filter { it.normalName() == "m:e" }.joinToString(" \\\\ ", "\\begin{matrix}", "\\end{matrix}") { contentOf(it, depth) }
            "m:acc" -> accent(element, depth)
            "m:bar" -> (if (property(element, "m:barpr", "m:pos") == "bot") "\\underline" else "\\overline") + "{" + argument(element, "m:e", depth) + "}"
            "m:limlow" -> limit(element, "\\underset", depth)
            "m:limupp" -> "\\overset{" + argument(element, "m:lim", depth) + "}{" + argument(element, "m:e", depth) + "}"
            "m:func" -> argument(element, "m:fname", depth).let { function -> function + "{" + argument(element, "m:e", depth) + "}" }
            "m:groupchr" -> (if (property(element, "m:groupchrpr", "m:pos") == "top") "\\overbrace" else "\\underbrace") + "{" + argument(element, "m:e", depth) + "}"
            "m:box", "m:borderbox", "m:phant" -> argument(element, "m:e", depth)
            else -> when {
                // The properties of an element are read by it, and those of Word have no meaning here
                name.startsWith("w:") || (name.startsWith("m:") && name.endsWith("pr")) -> ""
                name.startsWith("m:") -> throw Unconvertible()
                // The spans and the italics Word writes around its runs
                else -> contentOf(element, depth)
            }
        }
    }

    private fun fraction(element: Element, depth: Int): String {
        val numerator = argument(element, "m:num", depth)
        val denominator = argument(element, "m:den", depth)
        return when (property(element, "m:fpr", "m:type")) {
            "noBar" -> "{$numerator \\atop $denominator}"
            "lin" -> "{$numerator}/{$denominator}"
            else -> "\\frac{$numerator}{$denominator}"
        }
    }

    private fun radical(element: Element, depth: Int): String {
        val degree = argument(element, "m:deg", depth)
        return (if (degree.isEmpty()) "\\sqrt" else "\\sqrt[$degree]") + "{" + argument(element, "m:e", depth) + "}"
    }

    private fun nary(element: Element, depth: Int): String {
        val sign = property(element, "m:narypr", "m:chr")?.takeIf { it.isNotEmpty() } ?: DEFAULT_NARY
        val command = NARY[sign] ?: throw Unconvertible()
        val out = StringBuilder(command).append(scripts(argument(element, "m:sub", depth), argument(element, "m:sup", depth)))
        append(out, argument(element, "m:e", depth))
        return out.toString()
    }

    private fun delimiters(element: Element, depth: Int): String {
        val open = property(element, "m:dpr", "m:begchr") ?: "("
        val close = property(element, "m:dpr", "m:endchr") ?: ")"
        val separator = delimiter(property(element, "m:dpr", "m:sepchr") ?: "|")
        val inner = element.children().filter { it.normalName() == "m:e" }.joinToString(" $separator ") { contentOf(it, depth) }
        return "\\left" + delimiter(open) + " " + inner + " \\right" + delimiter(close)
    }

    private fun delimiter(sign: String): String = if (sign.isEmpty()) "." else DELIMITERS[sign] ?: sign

    private fun matrix(element: Element, depth: Int): String {
        val rows = element.children().filter { it.normalName() == "m:mr" }.joinToString(" \\\\ ") { row ->
            row.children().filter { it.normalName() == "m:e" }.joinToString(" & ") { contentOf(it, depth) }
        }
        return "\\begin{matrix}$rows\\end{matrix}"
    }

    private fun accent(element: Element, depth: Int): String {
        val sign = property(element, "m:accpr", "m:chr")?.takeIf { it.isNotEmpty() } ?: DEFAULT_ACCENT
        val command = ACCENTS[sign] ?: throw Unconvertible()
        return command + "{" + argument(element, "m:e", depth) + "}"
    }

    private fun limit(element: Element, command: String, depth: Int): String {
        val base = argument(element, "m:e", depth)
        val limit = argument(element, "m:lim", depth)
        return if (base in LIMIT_FUNCTIONS) "${base}_{$limit}" else "$command{$limit}{$base}"
    }

    /** A run of text, in its style. */
    private fun run(element: Element): String {
        val properties = child(element, "m:rpr")
        val plainStyle = properties?.let { child(it, "m:sty") }?.attr(VALUE) == "p"
        val text = StringBuilder().also { textOf(element, it) }.toString()
        if (properties != null && child(properties, "m:nor") != null) return "\\text{" + text.trim() + "}"
        val letters = text.trim()
        if (plainStyle && LETTERS.matches(letters)) return if (letters in FUNCTIONS) "\\$letters" else "\\mathrm{$letters}"
        return symbolsOf(text)
    }

    /** The text of a run: directly in it in what Word copies, in a `m:t` in a file. */
    private fun textOf(element: Element, out: StringBuilder) {
        for (node in element.childNodes()) {
            when (node) {
                is TextNode -> out.append(node.getWholeText())
                is Element -> if (!node.normalName().endsWith("pr")) textOf(node, out)
            }
        }
    }

    private fun symbolsOf(text: String): String {
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
                c.isWhitespace() || c == ' ' || c == '⁡' || c == '​' -> Unit
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
