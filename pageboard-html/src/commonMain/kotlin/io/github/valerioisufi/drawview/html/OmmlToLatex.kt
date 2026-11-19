package io.github.valerioisufi.drawview.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.TextNode
import com.fleeksoft.ksoup.parser.Parser
import io.github.valerioisufi.drawview.html.LatexWriting.Unconvertible

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

    private const val DEFAULT_NARY = "∫"
    private const val DEFAULT_ACCENT = "\u0302"
    private const val VALUE = "m:val"

    private val NARY = mapOf(
        "∑" to "\\sum", "∏" to "\\prod", "∐" to "\\coprod", "∫" to "\\int", "∬" to "\\iint",
        "∭" to "\\iiint", "∮" to "\\oint", "⋃" to "\\bigcup", "⋂" to "\\bigcap",
        "⋁" to "\\bigvee", "⋀" to "\\bigwedge"
    )

    private val ACCENTS = mapOf(
        "\u0302" to "\\hat", "\u0303" to "\\tilde", "\u0304" to "\\bar", "\u0305" to "\\overline", "\u0307" to "\\dot",
        "\u0308" to "\\ddot", "\u0301" to "\\acute", "\u0300" to "\\grave", "\u0306" to "\\breve", "\u030C" to "\\check",
        "\u20D7" to "\\vec"
    )

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
        if (depth > LatexWriting.MAX_DEPTH) throw Unconvertible()
        val out = StringBuilder()
        for (child in element.children()) LatexWriting.append(out, write(child, depth + 1))
        return out.toString()
    }

    private fun child(element: Element, name: String): Element? = element.children().firstOrNull { it.normalName() == name }

    private fun argument(element: Element, name: String, depth: Int): String = contentOf(child(element, name), depth)

    /** The value of the property [name] of [element], kept in its element of properties [properties]. */
    private fun property(element: Element, properties: String, name: String): String? =
        child(element, properties)?.let { child(it, name) }?.attr(VALUE)

    private fun write(element: Element, depth: Int): String {
        val name = element.normalName()
        return when (name) {
            "m:r" -> run(element)
            "m:f" -> fraction(element, depth)
            "m:ssup" -> LatexWriting.base(argument(element, "m:e", depth)) + LatexWriting.scripts("", argument(element, "m:sup", depth))
            "m:ssub" -> LatexWriting.base(argument(element, "m:e", depth)) + LatexWriting.scripts(argument(element, "m:sub", depth), "")
            "m:ssubsup" -> LatexWriting.base(argument(element, "m:e", depth)) + LatexWriting.scripts(argument(element, "m:sub", depth), argument(element, "m:sup", depth))
            "m:spre" -> "{}" + LatexWriting.scripts(argument(element, "m:sub", depth), argument(element, "m:sup", depth)) + LatexWriting.base(argument(element, "m:e", depth))
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
        val out = StringBuilder(command).append(LatexWriting.scripts(argument(element, "m:sub", depth), argument(element, "m:sup", depth)))
        LatexWriting.append(out, argument(element, "m:e", depth))
        return out.toString()
    }

    private fun delimiters(element: Element, depth: Int): String {
        val open = property(element, "m:dpr", "m:begchr") ?: "("
        val close = property(element, "m:dpr", "m:endchr") ?: ")"
        val separator = LatexWriting.delimiter(property(element, "m:dpr", "m:sepchr") ?: "|")
        val inner = element.children().filter { it.normalName() == "m:e" }.joinToString(" $separator ") { contentOf(it, depth) }
        return "\\left" + LatexWriting.delimiter(open) + " " + inner + " \\right" + LatexWriting.delimiter(close)
    }

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
        return if (base in LatexWriting.LIMIT_COMMANDS) "${base}_{$limit}" else "$command{$limit}{$base}"
    }

    /** A run of text, in its style. */
    private fun run(element: Element): String {
        val properties = child(element, "m:rpr")
        val plainStyle = properties?.let { child(it, "m:sty") }?.attr(VALUE) == "p"
        val text = StringBuilder().also { textOf(element, it) }.toString()
        if (properties != null && child(properties, "m:nor") != null) return "\\text{" + text.trim() + "}"
        val letters = text.trim()
        if (plainStyle && LETTERS.matches(letters)) return if (letters in LatexWriting.FUNCTIONS) "\\$letters" else "\\mathrm{$letters}"
        return LatexWriting.symbolsOf(text)
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
}
