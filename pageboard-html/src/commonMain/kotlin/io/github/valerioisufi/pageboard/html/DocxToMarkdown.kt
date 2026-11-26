package io.github.valerioisufi.pageboard.html

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser

/**
 * A Word document (`.docx`) in the notation of the texts (see `docs/plans/import-and-clipboard.md`):
 * its headings, paragraphs, lists, tables, bold, italic, strikethrough, links and equations (as
 * LaTeX, or [equationPlaceholder] when one cannot be written). Its body is read into the tree of
 * HTML the paste writes ([MarkdownWriter]), so that a document and what Word copies read the same.
 *
 * A document is a file of the user, maybe from anywhere: its archive is read within bounds
 * ([ZipEntries]), its nesting is bounded ([MAX_DEPTH]), and a document that cannot be read gives
 * `null`, never a failure of the application.
 */
public class DocxToMarkdown(private val equationPlaceholder: String = "[equation]") {

    /** [docx], the bytes of a Word document, in the notation; `null` when it cannot be read or holds no text. */
    public fun convert(docx: ByteArray): String? {
        if (docx.size > MAX_FILE) return null
        return try {
            val archive = ZipEntries.of(docx)
            val document = archive.read(DOCUMENT)?.decodeToString() ?: return null
            val styles = archive.read(STYLES)?.decodeToString()?.let(::headingStyles).orEmpty()
            val numbering = archive.read(NUMBERING)?.decodeToString()?.let(::orderedLists).orEmpty()
            val links = archive.read(RELATIONS)?.decodeToString()?.let(::linkTargets).orEmpty()
            val body = Ksoup.parse(document, parser = Parser.xmlParser()).getElementsByTag("w:body").firstOrNull() ?: return null
            val html = Ksoup.parse("").body()
            Reader(styles, numbering, links).blocks(body, html, 0)
            val writer = MarkdownWriter()
            val markdown = writer.write(html)
            markdown.takeIf { it.isNotBlank() && MarkdownWriter.keepsTextOf(html, it) }
                ?: html.wholeText().trim().takeIf { it.isNotEmpty() }
        } catch (_: ZipEntries.UnreadableArchive) {
            null
        } catch (_: TooDeep) {
            null
        } catch (_: Exception) {
            // A parser failing on a document from anywhere must never fail the import
            null
        }
    }

    /** The paragraphs, lists and tables of a part of the body, as the HTML the paste writes. */
    private inner class Reader(
        private val headings: Map<String, Int>,
        private val ordered: Map<String, Set<Int>>,
        private val links: Map<String, String>
    ) {
        /** The lists open while consecutive items are read: the level, the element and the list (`numId`) of each. */
        private val openLists = ArrayDeque<Triple<Int, Element, String>>()

        /** Writes the blocks of [from] into [into]. */
        fun blocks(from: Element, into: Element, depth: Int) {
            if (depth > MAX_DEPTH) throw TooDeep()
            for (child in from.children()) {
                when (child.normalName()) {
                    "w:p" -> paragraph(child, into, depth)
                    "w:tbl" -> {
                        openLists.clear()
                        into.appendChild(table(child, depth + 1))
                    }
                    // A content control, a change tracked as inserted: their content
                    "w:sdt", "w:sdtcontent", "w:ins", "w:customxml", "w:smarttag" -> blocks(child, into, depth + 1)
                }
            }
            openLists.clear()
        }

        private fun paragraph(p: Element, into: Element, depth: Int) {
            val properties = p.children().firstOrNull { it.normalName() == "w:ppr" }
            val style = properties?.children()?.firstOrNull { it.normalName() == "w:pstyle" }?.attr("w:val")
            val level = style?.let { headings[it] } ?: properties?.let { outlineLevel(it) }
            val list = properties?.children()?.firstOrNull { it.normalName() == "w:numpr" }
            // An equation on a line of its own
            p.children().firstOrNull { it.normalName() == "m:omathpara" }?.let { para ->
                openLists.clear()
                into.appendChild(equation(para, display = true))
                return
            }
            val element = when {
                level != null -> Element("h${level.coerceIn(1, 6)}")
                list != null -> Element("li")
                else -> Element("p")
            }
            runs(p, element, depth + 1)
            if (list != null && level == null) {
                val ilvl = list.children().firstOrNull { it.normalName() == "w:ilvl" }?.attr("w:val")?.toIntOrNull()?.coerceIn(0, MAX_LIST_LEVEL) ?: 0
                val numId = list.children().firstOrNull { it.normalName() == "w:numid" }?.attr("w:val").orEmpty()
                // numId 0 takes the paragraph out of its list
                if (numId != "0") {
                    item(element, ilvl, numId, ordered[numId]?.contains(ilvl) == true, into)
                    return
                }
                element.tagName("p")
            }
            openLists.clear()
            into.appendChild(element)
        }

        /**
         * [li] put in the list [numId] of [level] (a list in the item before it when deeper), opened
         * when needed: another list at the same level is a list of its own.
         */
        private fun item(li: Element, level: Int, numId: String, numbered: Boolean, into: Element) {
            while (openLists.isNotEmpty() && openLists.last().first > level) openLists.removeLast()
            if (openLists.lastOrNull()?.let { it.first == level && it.third != numId } == true) openLists.removeLast()
            val open = openLists.lastOrNull()
            if (open == null || open.first < level) {
                val list = Element(if (numbered) "ol" else "ul")
                val parent = open?.second?.children()?.lastOrNull() ?: into
                parent.appendChild(list)
                openLists.addLast(Triple(level, list, numId))
            }
            openLists.last().second.appendChild(li)
        }

        /** The runs of [from] (text, breaks, links, equations) in [into], with their formats. */
        private fun runs(from: Element, into: Element, depth: Int) {
            if (depth > MAX_DEPTH) throw TooDeep()
            for (child in from.children()) {
                when (child.normalName()) {
                    "w:r" -> run(child, into)
                    "w:hyperlink" -> {
                        val target = links[child.attr("r:id")]
                        val link = if (target != null) Element("a").attr("href", target) else Element("span")
                        runs(child, link, depth + 1)
                        into.appendChild(link)
                    }
                    "m:omath" -> into.appendChild(equation(child, display = false))
                    "w:ins", "w:smarttag", "w:sdt", "w:sdtcontent", "w:customxml", "w:fldsimple" -> runs(child, into, depth + 1)
                }
            }
        }

        private fun run(r: Element, into: Element) {
            val properties = r.children().firstOrNull { it.normalName() == "w:rpr" }
            var target = into
            fun wrap(tag: String) {
                val element = Element(tag)
                target.appendChild(element)
                target = element
            }
            if (properties != null) {
                if (on(properties, "w:b")) wrap("strong")
                if (on(properties, "w:i")) wrap("em")
                if (on(properties, "w:strike") || on(properties, "w:dstrike")) wrap("s")
                when (properties.children().firstOrNull { it.normalName() == "w:vertalign" }?.attr("w:val")) {
                    "superscript" -> wrap("sup")
                    "subscript" -> wrap("sub")
                }
            }
            for (child in r.children()) {
                when (child.normalName()) {
                    "w:t" -> target.appendText(child.wholeText())
                    "w:tab" -> target.appendText("\t")
                    "w:br", "w:cr" -> target.appendChild(Element("br"))
                    "w:nobreakhyphen" -> target.appendText("-")
                }
            }
        }

        private fun table(tbl: Element, depth: Int): Element {
            if (depth > MAX_DEPTH) throw TooDeep()
            val table = Element("table")
            for (tr in tbl.children().filter { it.normalName() == "w:tr" }) {
                val row = Element("tr")
                for (tc in tr.children().filter { it.normalName() == "w:tc" }) {
                    val cell = Element("td")
                    // The paragraphs of a cell on one line of it
                    val inner = Element("div")
                    Reader(headings, ordered, links).blocks(tc, inner, depth + 1)
                    inner.children().forEachIndexed { index, block ->
                        if (index > 0) cell.appendChild(Element("br"))
                        block.childNodes().toList().forEach { cell.appendChild(it) }
                    }
                    row.appendChild(cell)
                }
                table.appendChild(row)
            }
            return table
        }

        private fun equation(omml: Element, display: Boolean): Element {
            val tex = OmmlToLatex.convert(omml.outerHtml())?.tex
            if (tex == null) return Element("span").appendText(equationPlaceholder)
            return Element("math").attr("alttext", tex).also { if (display) it.attr("display", "block") }
        }
    }

    /** Whether the property [name] of [properties] is on: there, and not `false`, `0` or `off`. */
    private fun on(properties: Element, name: String): Boolean {
        val element = properties.children().firstOrNull { it.normalName() == name } ?: return false
        return element.attr("w:val").lowercase() !in setOf("false", "0", "off", "none")
    }

    /** The level of the heading of a paragraph by its outline level (0 is a heading 1), or `null`. */
    private fun outlineLevel(properties: Element): Int? =
        properties.children().firstOrNull { it.normalName() == "w:outlinelvl" }?.attr("w:val")?.toIntOrNull()?.takeIf { it in 0..5 }?.plus(1)

    /** The levels of the headings of the paragraph styles of `styles.xml`: by their outline level, or their name. */
    private fun headingStyles(xml: String): Map<String, Int> {
        val styles = HashMap<String, Int>()
        val root = Ksoup.parse(xml, parser = Parser.xmlParser())
        for (style in root.getElementsByTag("w:style")) {
            if (style.attr("w:type") != "paragraph") continue
            val id = style.attr("w:styleId").ifEmpty { continue }
            val outline = style.children().firstOrNull { it.normalName() == "w:ppr" }?.let(::outlineLevel)
            val name = style.children().firstOrNull { it.normalName() == "w:name" }?.attr("w:val")?.lowercase().orEmpty()
            val byName = when {
                name == "title" -> 1
                name.startsWith("heading ") -> name.removePrefix("heading ").toIntOrNull()?.takeIf { it in 1..6 }
                else -> null
            }
            (outline ?: byName)?.let { styles[id] = it }
        }
        return styles
    }

    /** The levels numbered (not bulleted) of each list of `numbering.xml`, by its `numId`. */
    private fun orderedLists(xml: String): Map<String, Set<Int>> {
        val root = Ksoup.parse(xml, parser = Parser.xmlParser())
        val abstracts = HashMap<String, Set<Int>>()
        for (abstract in root.getElementsByTag("w:abstractNum")) {
            abstracts[abstract.attr("w:abstractNumId")] = abstract.children().filter { it.normalName() == "w:lvl" }.mapNotNullTo(HashSet()) { level ->
                val format = level.children().firstOrNull { it.normalName() == "w:numfmt" }?.attr("w:val")
                level.attr("w:ilvl").toIntOrNull()?.takeIf { format != null && format != "bullet" && format != "none" }
            }
        }
        return root.getElementsByTag("w:num").associate { num ->
            num.attr("w:numId") to abstracts[num.children().firstOrNull { it.normalName() == "w:abstractnumid" }?.attr("w:val")].orEmpty()
        }
    }

    /** The external targets of the links of the document, by their relationship. */
    private fun linkTargets(xml: String): Map<String, String> =
        Ksoup.parse(xml, parser = Parser.xmlParser()).getElementsByTag("Relationship")
            .filter { it.attr("Type").endsWith("/hyperlink") }
            .associate { it.attr("Id") to it.attr("Target") }

    /** A document nested deeper than [MAX_DEPTH]. */
    private class TooDeep : Exception()

    private companion object {
        const val DOCUMENT = "word/document.xml"
        const val STYLES = "word/styles.xml"
        const val NUMBERING = "word/numbering.xml"
        const val RELATIONS = "word/_rels/document.xml.rels"

        /** The largest document read, in bytes. */
        const val MAX_FILE = 64 * 1024 * 1024

        /** How deep the tables and the controls of a document may nest. */
        const val MAX_DEPTH = 64

        /** The deepest level of a list. */
        const val MAX_LIST_LEVEL = 8
    }
}
