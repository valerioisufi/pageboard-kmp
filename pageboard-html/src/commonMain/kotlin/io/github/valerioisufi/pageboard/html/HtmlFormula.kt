package io.github.valerioisufi.pageboard.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

/**
 * A formula as a page shows it, read back as its source. What the page wrote is not what a browser
 * copies, which leaves out what is hidden; so, in this order:
 * - the LaTeX in a MathML `annotation` (KaTeX, MathJax), when it was kept;
 * - the `alttext` of a `math`;
 * - the attribute `data-mw` of Wikipedia, with its source;
 * - a script of MathJax 2, or its attribute `data-mathml`;
 * - the MathML itself, written in LaTeX ([MathmlToLatex]).
 */
internal object HtmlFormula {

    private const val TEX_SCRIPT = "math/tex"
    private const val MATHJAX = "MathJax"
    private const val PREVIEW = "MathJax_Preview"

    /** The classes of an element holding a whole formula, MathJax apart. */
    private val ROOTS = setOf("katex", "katex-display", "mwe-math-element", "mwe-math-mathml-a11y")

    /** The classes of what holds the MathML of a formula for those who cannot see it, besides its drawing. */
    private val ACCESSIBLE_COPIES = setOf("katex-mathml", "MJX_Assistive_MathML")

    /** The source and the kind of display in the attribute `data-mw` of Wikipedia. */
    private val WIKI_SOURCE = Regex(""""extsrc"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val WIKI_DISPLAY = Regex(""""display"\s*:\s*"block"""")

    /** The commands of the sets Wikipedia has and LaTeX has not, with what they stand for. */
    private val WIKI_COMMAND = Regex("""\\(R|N|Z|Q|C|reals|natnums|Complex)(?![A-Za-z])""")
    private val WIKI_SETS = mapOf("R" to "R", "reals" to "R", "N" to "N", "natnums" to "N", "Z" to "Z", "Q" to "Q", "C" to "C", "Complex" to "C")

    private const val MATHML_ATTRIBUTE = "data-mathml"

    /** The formula [element] is, or `null`: its source cannot be read, or it is not one. */
    fun of(element: Element): InlineRun.Formula? {
        if (!isRoot(element)) return null
        val tex = texOf(element)?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() && '$' !in it } ?: return null
        return InlineRun.Formula(tex, isDisplay(element))
    }

    /** Whether [element] only draws what a script after it holds (MathJax 2), or draws a preview of it. */
    fun isDrawingOfScript(element: Element): Boolean {
        val classes = element.classNames()
        if (PREVIEW in classes) return true
        if (classes.none { it.startsWith(MATHJAX) }) return false
        val next = element.nextElementSibling() ?: return false
        return next.normalName() == "script" && next.attr("type").startsWith(TEX_SCRIPT)
    }

    /** Whether [element] is the copy of a formula for screen readers, next to its drawing: only one of them is read. */
    fun isAccessibleCopy(element: Element): Boolean =
        element.normalName() == "mjx-assistive-mml" || element.classNames().any { it in ACCESSIBLE_COPIES }

    private fun isRoot(element: Element): Boolean = when (element.normalName()) {
        "math", "mjx-container" -> true
        "script" -> element.attr("type").startsWith(TEX_SCRIPT)
        else -> element.classNames().any { it in ROOTS || it.startsWith(MATHJAX) }
    }

    private fun texOf(element: Element): String? {
        if (element.normalName() == "script") return element.data()
        element.getElementsByTag("annotation").firstOrNull { "tex" in it.attr("encoding").lowercase() }?.let { return it.text() }
        val math = mathOf(element) ?: return null
        math.attr("alttext").takeIf { it.isNotBlank() }?.let { return it }
        WIKI_SOURCE.find(math.attr("data-mw"))?.let { source ->
            return WIKI_COMMAND.replace(unescaped(source.groupValues[1])) { "\\mathbb{" + WIKI_SETS.getValue(it.groupValues[1]) + "}" }
        }
        return MathmlToLatex.convert(math)
    }

    /** The `math` of [element]: in it, or in the attribute where MathJax 2 keeps it. */
    private fun mathOf(element: Element): Element? {
        if (element.normalName() == "math") return element
        return element.getElementsByTag("math").firstOrNull()
            ?: element.getElementsByAttribute(MATHML_ATTRIBUTE).firstOrNull()?.let { Ksoup.parse(it.attr(MATHML_ATTRIBUTE)).getElementsByTag("math").firstOrNull() }
    }

    /** A string of JSON without its escapes. */
    private fun unescaped(json: String): String {
        val out = StringBuilder(json.length)
        var i = 0
        while (i < json.length) {
            val c = json[i++]
            if (c != '\\' || i >= json.length) {
                out.append(c)
                continue
            }
            when (val escaped = json[i++]) {
                'n', 't', 'r' -> out.append(' ')
                'u' -> {
                    json.substring(i, minOf(i + 4, json.length)).toIntOrNull(16)?.let { out.append(it.toChar()) }
                    i += 4
                }
                else -> out.append(escaped)
            }
        }
        return out.toString()
    }

    private fun isDisplay(element: Element): Boolean {
        if (element.normalName() == "script") return "mode=display" in element.attr("type")
        val classes = element.classNames()
        if ("katex-display" in classes || "MathJax_Display" in classes || element.attr("display") == "true") return true
        val math = mathOf(element) ?: return false
        return math.attr("display") == "block" || WIKI_DISPLAY.containsMatchIn(math.attr("data-mw"))
    }
}
