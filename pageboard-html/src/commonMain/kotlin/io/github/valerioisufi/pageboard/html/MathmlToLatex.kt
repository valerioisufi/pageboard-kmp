package io.github.valerioisufi.pageboard.html

import com.fleeksoft.ksoup.nodes.Element
import io.github.valerioisufi.pageboard.html.LatexWriting.Unconvertible

/**
 * MathML written in LaTeX. A browser copies a formula of KaTeX or MathJax without its source (it is in
 * a hidden `annotation`, which is left out of what is copied): its MathML is what remains.
 */
internal object MathmlToLatex {

    /** The accents over a base, by the sign MathML has for them. */
    private val ACCENTS_OVER = mapOf(
        "^" to "\\hat", "ˆ" to "\\hat", "\u0302" to "\\hat", "~" to "\\tilde", "˜" to "\\tilde", "\u0303" to "\\tilde",
        "ˉ" to "\\bar", "¯" to "\\bar", "\u0304" to "\\bar", "‾" to "\\overline", "\u0305" to "\\overline",
        "→" to "\\vec", "\u20D7" to "\\vec", "˙" to "\\dot", "\u0307" to "\\dot", "¨" to "\\ddot", "\u0308" to "\\ddot",
        "´" to "\\acute", "ˊ" to "\\acute", "\u0301" to "\\acute", "`" to "\\grave", "ˋ" to "\\grave", "\u0300" to "\\grave",
        "˘" to "\\breve", "\u0306" to "\\breve", "ˇ" to "\\check", "\u030C" to "\\check", "⏞" to "\\overbrace"
    )

    private val ACCENTS_UNDER = mapOf("_" to "\\underline", "‾" to "\\underline", "\u0332" to "\\underline", "⏟" to "\\underbrace")

    /** The formula in the `math` element [math], or `null` when it holds what cannot be written. */
    fun convert(math: Element): String? = try {
        contentOf(math, 0).trim().takeIf { it.isNotEmpty() }
    } catch (_: Unconvertible) {
        null
    }

    private fun contentOf(element: Element, depth: Int): String {
        if (depth > LatexWriting.MAX_DEPTH) throw Unconvertible()
        val out = StringBuilder()
        for (child in element.children()) LatexWriting.append(out, write(child, depth + 1))
        return out.toString()
    }

    private fun argument(element: Element, index: Int, depth: Int): String {
        val children = element.children()
        if (index >= children.size) throw Unconvertible()
        return write(children[index], depth + 1)
    }

    private fun write(element: Element, depth: Int): String {
        if (depth > LatexWriting.MAX_DEPTH) throw Unconvertible()
        return when (element.normalName()) {
            "mrow" -> row(element, depth)
            "math", "mstyle", "mpadded", "menclose", "mi-group" -> contentOf(element, depth)
            "semantics" -> element.children().firstOrNull()?.let { write(it, depth + 1) } ?: ""
            "annotation", "annotation-xml", "mphantom", "none", "mprescripts" -> ""
            "mi" -> identifier(element)
            "mn" -> LatexWriting.symbolsOf(element.text())
            "mo" -> LatexWriting.symbolsOf(element.text())
            "mtext" -> element.text().trim().let { if (it.isEmpty()) "" else "\\text{$it}" }
            "ms" -> "\\text{" + element.text() + "}"
            "mspace" -> if (isWide(element)) "\\quad" else ""
            "msup" -> LatexWriting.base(argument(element, 0, depth)) + LatexWriting.scripts("", argument(element, 1, depth))
            "msub" -> LatexWriting.base(argument(element, 0, depth)) + LatexWriting.scripts(argument(element, 1, depth), "")
            "msubsup" -> LatexWriting.base(argument(element, 0, depth)) + LatexWriting.scripts(argument(element, 1, depth), argument(element, 2, depth))
            "mfrac" -> fraction(element, depth)
            "msqrt" -> "\\sqrt{" + contentOf(element, depth) + "}"
            "mroot" -> "\\sqrt[" + argument(element, 1, depth) + "]{" + argument(element, 0, depth) + "}"
            "mover" -> over(element, depth)
            "munder" -> under(element, depth)
            "munderover" -> {
                val base = argument(element, 0, depth)
                val scripts = LatexWriting.scripts(argument(element, 1, depth), argument(element, 2, depth))
                if (base in LatexWriting.LIMIT_COMMANDS || base.startsWith("\\int")) base + scripts else "{$base}$scripts"
            }
            "mtable" -> table(element, depth)
            "mfenced" -> fenced(element, depth)
            // Not a part of a formula that can be guessed
            else -> throw Unconvertible()
        }
    }

    /** A row: when it starts and ends with fences they grow with what they hold, as `\left` and `\right`. */
    private fun row(element: Element, depth: Int): String {
        val children = element.children().toList()
        val first = children.firstOrNull()
        val last = children.lastOrNull()
        if (children.size < 2 || first == null || last == null || !isFence(first) || !isFence(last)) return contentOf(element, depth)
        val out = StringBuilder()
        for (child in children.subList(1, children.size - 1)) LatexWriting.append(out, write(child, depth + 1))
        return "\\left" + LatexWriting.delimiter(first.text()) + " " + out + " \\right" + LatexWriting.delimiter(last.text())
    }

    private fun isFence(element: Element): Boolean = element.normalName() == "mo" && element.attr("fence") == "true"

    private fun identifier(element: Element): String {
        val text = element.text().trim()
        val letters = text.all { it.isLetter() && it.code < 128 }
        return when {
            text.length > 1 && letters -> if (text in LatexWriting.FUNCTIONS) "\\$text" else "\\mathrm{$text}"
            element.attr("mathvariant") == "normal" && letters && text.isNotEmpty() -> "\\mathrm{$text}"
            else -> LatexWriting.symbolsOf(text)
        }
    }

    private fun isWide(space: Element): Boolean {
        val width = space.attr("width")
        return width.endsWith("em") && (width.removeSuffix("em").toFloatOrNull() ?: 0f) >= 1f
    }

    private fun fraction(element: Element, depth: Int): String {
        val numerator = argument(element, 0, depth)
        val denominator = argument(element, 1, depth)
        return if (element.attr("linethickness").removeSuffix("px") == "0") "{$numerator \\atop $denominator}" else "\\frac{$numerator}{$denominator}"
    }

    private fun over(element: Element, depth: Int): String {
        val base = argument(element, 0, depth)
        val over = element.children().getOrNull(1) ?: throw Unconvertible()
        val accent = ACCENTS_OVER[over.text().trim()]
        return when {
            accent != null -> "$accent{$base}"
            base in LatexWriting.LIMIT_COMMANDS -> "$base^{" + write(over, depth + 1) + "}"
            else -> "\\overset{" + write(over, depth + 1) + "}{$base}"
        }
    }

    private fun under(element: Element, depth: Int): String {
        val base = argument(element, 0, depth)
        val under = element.children().getOrNull(1) ?: throw Unconvertible()
        val accent = ACCENTS_UNDER[under.text().trim()]
        return when {
            accent != null -> "$accent{$base}"
            base in LatexWriting.LIMIT_COMMANDS -> base + "_{" + write(under, depth + 1) + "}"
            else -> "\\underset{" + write(under, depth + 1) + "}{$base}"
        }
    }

    private fun table(element: Element, depth: Int): String {
        val rows = element.children().filter { it.normalName() == "mtr" || it.normalName() == "mlabeledtr" }.joinToString(" \\\\ ") { row ->
            row.children().filter { it.normalName() == "mtd" }.joinToString(" & ") { contentOf(it, depth + 1) }
        }
        return "\\begin{matrix}$rows\\end{matrix}"
    }

    private fun fenced(element: Element, depth: Int): String {
        val open = if (element.hasAttr("open")) element.attr("open") else "("
        val close = if (element.hasAttr("close")) element.attr("close") else ")"
        val inner = element.children().joinToString(", ") { write(it, depth + 1) }
        return "\\left" + LatexWriting.delimiter(open) + " " + inner + " \\right" + LatexWriting.delimiter(close)
    }
}
