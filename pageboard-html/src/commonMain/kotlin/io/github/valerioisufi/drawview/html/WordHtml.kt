package io.github.valerioisufi.drawview.html

import com.fleeksoft.ksoup.nodes.Comment
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode

/**
 * What Microsoft Word copies rewritten as plain HTML, before the conversion. Word writes valid HTML
 * that says little by its tags:
 * - a list is a run of paragraphs with a `mso-list` style, each with its marker written as text in a
 *   span marked `mso-list:Ignore`;
 * - a title is a paragraph of the class `MsoTitle`;
 * - an equation is an image, with its OMML in a comment: it becomes a `<math>` with its LaTeX, which
 *   [HtmlFormula] reads, or the placeholder when it cannot be written.
 */
internal class WordHtml(private val equationPlaceholder: String) {

    /** A list being rebuilt: at its Word [level], of the list [id] of Word. */
    private class OpenList(val level: Int, val id: String, val ordered: Boolean, val element: Element)

    /** A paragraph of a list of Word: its [level], the [id] of its list, its [marker] as written, `null` when it goes on the item before it. */
    private class ListParagraph(val element: Element, val level: Int, val id: String, val marker: String?)

    /** Rewrites what is in [body], which is left as it is when it did not come from Word. */
    fun normalize(body: Element) {
        val elements = body.getAllElements().toList()
        for (element in elements) {
            for (node in element.childNodes().toList()) {
                if (node is Comment && node.getData().startsWith(EQUATION)) node.replaceWith(equationOf(node.getData()))
            }
            headingLevelOf(element)?.let { element.tagName("h$it") }
        }
        val parents = elements.filter { isListParagraph(it) }.mapNotNull { it.parent() as? Element }.distinct()
        for (parent in parents) rebuildLists(parent)
    }

    /** What stands in the tree for the equation in the comment [data]. */
    private fun equationOf(data: String): Node {
        val equation = OmmlToLatex.convert(data) ?: return TextNode(equationPlaceholder)
        return Element("math").attr("alttext", equation.tex).also { if (equation.display) it.attr("display", "block") }
    }

    /** The level of the heading a paragraph of Word stands for, by its class. */
    private fun headingLevelOf(element: Element): Int? {
        if (element.normalName() != "p") return null
        for (name in element.classNames()) {
            when {
                name == "MsoTitle" -> return 1
                name == "MsoSubtitle" -> return 2
            }
            HEADING_CLASS.matchEntire(name)?.let { return it.groupValues[1].toInt() }
        }
        return null
    }

    private fun isListParagraph(element: Element): Boolean =
        element.normalName() == "p" && LIST_STYLE.containsMatchIn(HtmlFormat.styleOf(element)["mso-list"].orEmpty())

    /**
     * The runs of paragraphs of a list among the children of [parent] made lists. A paragraph without a
     * marker between two items, such as an equation on its own line, belongs to the item before it.
     */
    private fun rebuildLists(parent: Element) {
        val run = mutableListOf<ListParagraph>()
        val nodes = parent.childNodes().toList()
        for ((index, node) in nodes.withIndex()) {
            when {
                node is Element && isListParagraph(node) -> run += read(node)
                isBetweenParagraphs(node) -> Unit
                node is Element && run.isNotEmpty() && isInItem(node, nodes, index) -> run += ListParagraph(node, run.last().level, run.last().id, null)
                else -> {
                    rebuild(run)
                    run.clear()
                }
            }
        }
        rebuild(run)
    }

    /** Whether [element], at [index] of its siblings [nodes], is a paragraph that goes on the item before it. */
    private fun isInItem(element: Element, nodes: List<Node>, index: Int): Boolean {
        if (element.normalName() != "p") return false
        if (element.classNames().any { it.startsWith(LIST_PARAGRAPH_CLASS) }) return true
        val aloneEquation = element.text().isBlank() && element.getElementsByTag("math").isNotEmpty()
        val next = nodes.drop(index + 1).firstOrNull { !isBetweenParagraphs(it) }
        return aloneEquation && next is Element && isListParagraph(next)
    }

    private fun isBetweenParagraphs(node: Node): Boolean = node is Comment || (node is TextNode && node.isBlank())

    /** The paragraph [element] read, its marker taken off. */
    private fun read(element: Element): ListParagraph {
        val style = LIST_STYLE.find(HtmlFormat.styleOf(element)["mso-list"].orEmpty())!!
        val markers = element.getAllElements().filter { HtmlFormat.styleOf(it)["mso-list"] == "ignore" }
        val marker = markers.joinToString("") { it.text() }.replace('\u00A0', ' ').trim()
        markers.forEach { it.remove() }
        return ListParagraph(element, style.groupValues[2].toInt(), style.groupValues[1] + "/" + style.groupValues[3], marker)
    }

    private fun rebuild(run: List<ListParagraph>) {
        val open = ArrayDeque<OpenList>()
        for (paragraph in run) {
            val marker = paragraph.marker
            if (marker == null) {
                open.last().element.children().toList().last().appendChild(paragraph.element)
                continue
            }
            val ordered = ORDERED_MARKER.matches(marker)
            while (open.isNotEmpty() && open.last().level > paragraph.level) open.removeLast()
            if (open.lastOrNull()?.let { it.level == paragraph.level && (it.id != paragraph.id || it.ordered != ordered) } == true) open.removeLast()
            if (open.lastOrNull()?.level != paragraph.level) {
                val list = newList(ordered, marker)
                val outer = open.lastOrNull()?.element?.children()?.lastOrNull()
                if (outer != null) outer.appendChild(list) else paragraph.element.before(list)
                open.addLast(OpenList(paragraph.level, paragraph.id, ordered, list))
            }
            val item = Element("li")
            for (child in paragraph.element.childNodes().toList()) item.appendChild(child)
            open.last().element.appendChild(item)
            paragraph.element.remove()
        }
    }

    /** A new list, whose first marker is [marker]. */
    private fun newList(ordered: Boolean, marker: String): Element {
        if (!ordered) return Element("ul")
        val list = Element("ol")
        val word = marker.dropLast(1).substringAfterLast('.')
        when {
            word.all { it.isDigit() } -> list.attr("start", word)
            word.all { it in ROMAN_LOWER } -> list.attr("type", "i")
            word.all { it in ROMAN_UPPER } -> list.attr("type", "I")
            word.all { it.isLowerCase() } -> list.attr("type", "a")
            else -> list.attr("type", "A")
        }
        return list
    }

    private companion object {
        /** The start of the comment holding an equation; the one with its image is `[if !msEquation]`. */
        const val EQUATION = "[if gte msEquation"
        const val LIST_PARAGRAPH_CLASS = "MsoListParagraph"
        const val ROMAN_LOWER = "ivxlcdm"
        const val ROMAN_UPPER = "IVXLCDM"

        /** The list, the level and the numbering of a `mso-list` style, in lower case. */
        val LIST_STYLE = Regex("""l(\d+)\s+level(\d+)\s+lfo(\d+)""")
        val HEADING_CLASS = Regex("""^MsoHeading([1-6])$""")
        val ORDERED_MARKER = Regex("""^(\d+(\.\d+)*|[A-Za-z]{1,7})[.)]$""")
    }
}
