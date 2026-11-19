package io.github.valerioisufi.pageboard.html

import com.fleeksoft.ksoup.nodes.Element

/**
 * The formatting of a piece of text that the notation can write, as the elements around it ask for
 * it.
 *
 * @property link the address of the link around the text, one of the [safe ones][safeLink].
 */
internal data class HtmlFormat(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null
) {
    /**
     * This format inside [element]: its tag, then its `style`, which wins as in a browser (Google
     * Docs wraps its text in `<b style="font-weight:normal">`, and a browser copies the computed
     * styles, on any tag).
     */
    fun inside(element: Element): HtmlFormat {
        var format = when (element.normalName()) {
            "b", "strong" -> copy(bold = true)
            "i", "em", "cite", "dfn", "var" -> copy(italic = true)
            "s", "strike", "del" -> copy(strike = true)
            "code", "kbd", "samp", "tt" -> copy(code = true)
            "a" -> copy(link = safeLink(element.attr("href")) ?: link)
            else -> this
        }
        val style = styleOf(element)
        style["font-weight"]?.let { weight -> isBold(weight)?.let { format = format.copy(bold = it) } }
        when (style["font-style"]) {
            "italic", "oblique" -> format = format.copy(italic = true)
            "normal" -> format = format.copy(italic = false)
        }
        if (LINE_THROUGH in style["text-decoration"].orEmpty() || LINE_THROUGH in style["text-decoration-line"].orEmpty()) {
            format = format.copy(strike = true)
        }
        if (style["font-family"]?.let(::isMonospace) == true) format = format.copy(code = true)
        return format
    }

    companion object {
        private const val LINE_THROUGH = "line-through"
        private const val BOLD_FROM = 600
        private val SAFE_SCHEMES = listOf("http://", "https://", "mailto:")
        private val MONOSPACE = Regex("""mono|courier|consolas|menlo|fira code|source code""", RegexOption.IGNORE_CASE)

        /** Whether [weight] of a `font-weight` is bold, `null` for a value that says nothing absolute. */
        private fun isBold(weight: String): Boolean? = when (weight) {
            "bold", "bolder" -> true
            "normal", "lighter" -> false
            else -> weight.toIntOrNull()?.let { it >= BOLD_FROM }
        }

        /** Whether [family] of a `font-family` is a monospaced font. */
        fun isMonospace(family: String): Boolean = MONOSPACE.containsMatchIn(family)

        /**
         * [href] when it can be kept in a note: only addresses that leave the application for a
         * known place, never a `javascript:` one, nor a relative one, which has no meaning here.
         */
        fun safeLink(href: String): String? {
            val address = href.trim()
            if (SAFE_SCHEMES.none { address.startsWith(it, ignoreCase = true) }) return null
            // The notation takes an address without spaces and with balanced parentheses
            return address.replace(" ", "%20").replace("(", "%28").replace(")", "%29").takeIf { '\n' !in it }
        }

        /** The declarations of the `style` of [element], with their names and values in lower case. */
        fun styleOf(element: Element): Map<String, String> {
            val style = element.attr("style")
            if (style.isEmpty()) return emptyMap()
            val declarations = HashMap<String, String>()
            for (declaration in style.split(';')) {
                val colon = declaration.indexOf(':')
                if (colon <= 0) continue
                declarations[declaration.substring(0, colon).trim().lowercase()] =
                    declaration.substring(colon + 1).lowercase().replace("!important", "").trim()
            }
            return declarations
        }
    }
}
