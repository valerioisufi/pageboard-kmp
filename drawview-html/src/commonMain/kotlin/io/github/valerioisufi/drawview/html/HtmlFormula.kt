package io.github.valerioisufi.drawview.html

import com.fleeksoft.ksoup.nodes.Element

/**
 * A formula as a page shows it, read back as its source: KaTeX and MathJax draw a formula with
 * elements of their own, and keep its LaTeX in a MathML `annotation` or in a script; Wikipedia has
 * the same in a hidden `math`.
 */
internal object HtmlFormula {

    private const val TEX_SCRIPT = "math/tex"
    private const val MATHJAX = "MathJax"
    private const val PREVIEW = "MathJax_Preview"

    /** The classes of an element holding a whole formula, MathJax apart. */
    private val ROOTS = setOf("katex", "katex-display", "mwe-math-element")

    /** The formula [element] is, or `null`: its source was not kept, or it is not one. */
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

    private fun isRoot(element: Element): Boolean = when (element.normalName()) {
        "math", "mjx-container" -> true
        "script" -> element.attr("type").startsWith(TEX_SCRIPT)
        else -> element.classNames().any { it in ROOTS || it.startsWith(MATHJAX) }
    }

    private fun texOf(element: Element): String? {
        if (element.normalName() == "script") return element.data()
        element.getElementsByTag("annotation").firstOrNull { "tex" in it.attr("encoding").lowercase() }?.let { return it.text() }
        val math = if (element.normalName() == "math") element else element.getElementsByTag("math").firstOrNull()
        return math?.attr("alttext")?.takeIf { it.isNotBlank() }
    }

    private fun isDisplay(element: Element): Boolean {
        if (element.normalName() == "script") return "mode=display" in element.attr("type")
        val classes = element.classNames()
        if ("katex-display" in classes || "MathJax_Display" in classes || element.attr("display") == "true") return true
        val math = if (element.normalName() == "math") element else element.getElementsByTag("math").firstOrNull()
        return math?.attr("display") == "block"
    }
}
