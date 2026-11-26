package io.github.valerioisufi.pageboard.html

import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import io.github.valerioisufi.pageboard.model.text.ListMarker
import io.github.valerioisufi.pageboard.model.text.ListNumbering.Numbering
import io.github.valerioisufi.pageboard.model.text.NumberingStyle

/**
 * A block of the notation: its [lines], and whether it [isList].
 *
 * @property codeLine the line, when the block is a line of code alone: what code editors copy, one
 *   element per line.
 */
private class Block(val lines: List<String>, val isList: Boolean = false, val codeLine: String? = null)

/**
 * Walks a tree of HTML once and writes its blocks in the notation. One instance per conversion.
 *
 * An element it does not know is transparent: its text is kept, only its meaning is lost.
 */
internal class MarkdownWriter {

    /** Whether anything was written that plain text could not have said. */
    var formatted: Boolean = false
        private set

    /** The elements asked whether they hold a block ([holdsBlock]): walked once however deep they are. */
    private val holdingBlock = HashMap<Element, Boolean>()

    /** The blocks of [root] in the notation. */
    fun write(root: Element): String =
        blocksIn(root, HtmlFormat(), quoted = false, depth = 0).joinToString("\n\n") { it.lines.joinToString("\n") }

    private fun blocksIn(parent: Element, format: HtmlFormat, quoted: Boolean, depth: Int): List<Block> {
        val blocks = mutableListOf<Block>()
        val runs = mutableListOf<InlineRun>()
        fun flush() {
            if (runs.isEmpty()) return
            paragraph(runs)?.let { blocks += it }
            runs.clear()
        }
        val children = parent.childNodes()
        var index = 0
        while (index < children.size) {
            val child = children[index++]
            if (child !is Element) {
                collect(child, format, runs, depth + 1)
                continue
            }
            val isFormula = HtmlFormula.of(child) != null
            if (!isFormula && isIgnored(child)) continue
            if (isFormula || (!isBlock(child) && !holdsBlock(child, depth + 1))) {
                collect(child, format, runs, depth + 1)
                continue
            }
            flush()
            when (child.normalName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> heading(child, format, depth + 1)?.let { blocks += it }
                "ul", "ol", "dir", "menu" -> list(child, format, quoted, depth + 1)?.let { blocks += it }
                "li" -> {
                    // Items with no list around them, as a selection may have them
                    val orphans = mutableListOf(child)
                    while (index < children.size) {
                        val next = children[index]
                        if (next is Element && next.normalName() == "li") {
                            orphans.add(next)
                        } else if (next !is TextNode || !next.isBlank()) {
                            break
                        }
                        index++
                    }
                    items(orphans, Numbers.None, 1, format, quoted, depth + 1).takeIf { it.isNotEmpty() }?.let { blocks += Block(it, isList = true) }
                }
                "blockquote" -> blocks += quote(child, format, quoted, depth + 1)
                "pre" -> codeBlock(child)?.let { blocks += it }
                "table" -> blocks += table(child, format, quoted, depth + 1)
                "hr" -> Unit
                else ->
                    if (isCodeDiv(child)) {
                        codeBlock(child)?.let { blocks += it }
                    } else if (depth >= MAX_DEPTH) {
                        collect(child, format, runs, depth + 1)
                    } else {
                        blocks += blocksIn(child, format.inside(child), quoted, depth + 1)
                    }
            }
        }
        flush()
        return withCodeLinesJoined(blocks)
    }

    /** The lines of code in a row of [blocks] as one block of code. */
    private fun withCodeLinesJoined(blocks: List<Block>): List<Block> {
        if (blocks.count { it.codeLine != null } < 2) return blocks
        val joined = mutableListOf<Block>()
        var index = 0
        while (index < blocks.size) {
            val start = index
            while (index < blocks.size && blocks[index].codeLine != null) index++
            when (index - start) {
                0 -> joined += blocks[index++]
                1 -> joined += blocks[start]
                else -> joined += fenced(blocks.subList(start, index).map { it.codeLine!! }.joinToString("\n"), null, codeLine = false)
            }
        }
        return joined
    }

    /** The fenced block of [source]. */
    private fun fenced(source: String, language: String?, codeLine: Boolean): Block {
        formatted = true
        val longest = BACKTICKS.findAll(source).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        return Block(listOf(fence + (language ?: "")) + source.lines() + fence, codeLine = source.takeIf { codeLine && '\n' !in it })
    }

    private fun paragraph(runs: List<InlineRun>): Block? {
        val written = InlineText.write(runs)
        if (written.text.isBlank()) return null
        formatted = formatted || written.formatted
        // A paragraph all in a code of one line: `like this`
        val code = written.text.takeIf { it.length > 2 && it.first() == '`' && it.last() == '`' && it.count { c -> c == '`' } == 2 && '\n' !in it }
        return Block(written.text.lines(), codeLine = code?.let { it.substring(1, it.length - 1) })
    }

    /**
     * The text of [node] and of what it holds, in [format], added to [runs]. A block found here (in
     * a heading, in a cell) is only a line break.
     */
    private fun collect(node: Node, format: HtmlFormat, runs: MutableList<InlineRun>, depth: Int) {
        when (node) {
            is TextNode -> runs += InlineRun.Text(node.getWholeText(), format)
            is Element -> {
                val formula = HtmlFormula.of(node)
                if (formula != null) {
                    runs += formula
                    return
                }
                if (isIgnored(node)) return
                when (node.normalName()) {
                    "br" -> {
                        runs += InlineRun.Break
                        return
                    }
                    "img" -> {
                        node.attr("alt").takeIf(::isSign)?.let { runs += InlineRun.Text(it, format) }
                        return
                    }
                    // Shifted: in Unicode, else in a formula (see ScriptShift)
                    "sup", "sub" -> {
                        ScriptShift.of(node.text(), superscript = node.normalName() == "sup", format.inside(node))?.let {
                            runs += it
                            formatted = true
                        }
                        return
                    }
                }
                val inner = format.inside(node)
                if (depth >= MAX_DEPTH) {
                    runs += InlineRun.Text(node.text(), inner)
                    return
                }
                val block = isBlock(node)
                if (block) runs += InlineRun.Break
                for (child in node.childNodes()) collect(child, inner, runs, depth + 1)
                if (block) runs += InlineRun.Break
            }
        }
    }

    private fun heading(element: Element, format: HtmlFormat, depth: Int): Block? {
        val runs = mutableListOf<InlineRun>()
        for (child in element.childNodes()) collect(child, format.inside(element), runs, depth + 1)
        // A heading is drawn in bold already
        val plain = runs.map { if (it is InlineRun.Text) it.copy(format = it.format.copy(bold = false)) else it }
        val text = InlineText.write(plain, oneLine = true).text
        if (text.isBlank()) return null
        formatted = true
        return Block(listOf("#".repeat(element.normalName()[1].digitToInt()) + " " + text))
    }

    private fun quote(element: Element, format: HtmlFormat, quoted: Boolean, depth: Int): List<Block> {
        val blocks = blocksIn(element, format.inside(element), quoted = true, depth)
        // The notation has one level of quotes: one inside another is part of it
        if (quoted || blocks.isEmpty()) return blocks
        formatted = true
        val lines = blocks.flatMapIndexed { i, block ->
            (if (i > 0) listOf(">") else emptyList()) + block.lines.map { if (it.isEmpty()) ">" else "> $it" }
        }
        return listOf(Block(lines))
    }

    /** How the items of a list are marked. */
    private sealed interface Numbers {
        /** Bullets. */
        data object None : Numbers

        /** Numbers in a [style]; `null` for those of the level, as the notation writes them by default. */
        data class Styled(val style: NumberingStyle?) : Numbers
    }

    private fun list(element: Element, format: HtmlFormat, quoted: Boolean, depth: Int): Block? {
        val style = numberingOf(element)
        val numbers = if (element.normalName() == "ol") Numbers.Styled(style) else Numbers.None
        val start = element.attr("start").toIntOrNull()?.takeIf { ListMarker.canStart(style, it) } ?: 1
        val lines = items(element.children().toList(), numbers, start, format.inside(element), quoted, depth)
        return if (lines.isEmpty()) null else Block(lines, isList = true)
    }

    /** The lines of [items], at the first level, marked by [numbers] from [start]. */
    private fun items(items: List<Element>, numbers: Numbers, start: Int, format: HtmlFormat, quoted: Boolean, depth: Int): List<String> {
        val lines = mutableListOf<String>()
        var number = start
        for (item in items) {
            if (isIgnored(item)) continue
            val name = item.normalName()
            // A list directly in a list, as some pages have it: it belongs to the item before it
            if (name == "ul" || name == "ol") {
                list(item, format, quoted, depth + 1)?.let { nested -> lines += nested.lines.map { "$NESTED_INDENT$it" } }
                continue
            }
            val blocks = if (depth >= MAX_DEPTH) {
                listOfNotNull(paragraph(mutableListOf<InlineRun>().also { collect(item, format, it, depth + 1) }))
            } else {
                blocksIn(item, format.inside(item), quoted, depth + 1)
            }
            if (blocks.isEmpty()) continue
            val marker = when (numbers) {
                Numbers.None -> "- "
                is Numbers.Styled -> {
                    number = item.attr("value").toIntOrNull()?.takeIf { ListMarker.canStart(numbers.style, it) } ?: number
                    ListMarker.write(numbers.style, number)
                }
            }
            val indent = " ".repeat(marker.length)
            var first = true
            for (block in blocks) {
                if (block.isList) {
                    lines += block.lines.map { "$NESTED_INDENT$it" }
                    continue
                }
                for (line in block.lines) {
                    lines += when {
                        first -> marker + line
                        line.isEmpty() -> line
                        else -> indent + line
                    }
                    first = false
                }
            }
            number++
            formatted = true
        }
        return lines
    }

    /** The numbering [list] asks for, by its `type` or its `list-style-type`; `null` for plain numbers. */
    private fun numberingOf(list: Element): NumberingStyle? {
        val numbering = when (list.attr("type")) {
            "a" -> Numbering.LOWER_ALPHA
            "A" -> Numbering.UPPER_ALPHA
            "i" -> Numbering.LOWER_ROMAN
            "I" -> Numbering.UPPER_ROMAN
            else -> when (HtmlFormat.styleOf(list)["list-style-type"]) {
                "lower-alpha", "lower-latin" -> Numbering.LOWER_ALPHA
                "upper-alpha", "upper-latin" -> Numbering.UPPER_ALPHA
                "lower-roman" -> Numbering.LOWER_ROMAN
                "upper-roman" -> Numbering.UPPER_ROMAN
                else -> null
            }
        }
        return numbering?.let { NumberingStyle(it) }
    }

    /** A `<div>` of code as editors copy it: monospaced, its lines kept. */
    private fun isCodeDiv(element: Element): Boolean {
        if (element.normalName() != "div") return false
        val style = HtmlFormat.styleOf(element)
        return style["white-space"].orEmpty().startsWith("pre") && style["font-family"]?.let(HtmlFormat::isMonospace) == true
    }

    private fun codeBlock(element: Element): Block? {
        val source = StringBuilder().also { sourceOf(codeOf(element), it, 0) }.toString().trim('\n')
        if (source.isBlank()) return null
        return fenced(source, languageOf(element), codeLine = languageOf(element) == null)
    }

    /** The source of the code of [node], its line breaks kept. */
    private fun sourceOf(node: Node, out: StringBuilder, depth: Int) {
        when (node) {
            is TextNode -> out.append(node.getWholeText())
            is Element -> {
                if (isIgnored(node)) return
                if (node.normalName() == "br") {
                    out.append('\n')
                    return
                }
                if (depth >= MAX_DEPTH) {
                    out.append(node.wholeText())
                    return
                }
                val block = isBlock(node)
                if (block && out.isNotEmpty() && out.last() != '\n') out.append('\n')
                for (child in node.childNodes()) sourceOf(child, out, depth + 1)
                if (block && out.isNotEmpty() && out.last() != '\n') out.append('\n')
            }
        }
    }

    /** The language of a block of code, on [element], on its `<code>` or on the element around it. */
    private fun languageOf(element: Element): String? {
        val candidates = listOfNotNull(element, element.getElementsByTag("code").firstOrNull(), element.parent() as? Element)
        for (candidate in candidates) {
            val named = candidate.attr("data-language").ifEmpty { candidate.attr("data-lang") }
            if (LANGUAGE.matches(named)) return named
            for (name in candidate.classNames()) {
                val language = LANGUAGE_CLASS.find(name)?.groupValues?.get(1) ?: continue
                if (LANGUAGE.matches(language)) return language
            }
        }
        return null
    }

    private fun table(element: Element, format: HtmlFormat, quoted: Boolean, depth: Int): List<Block> {
        val inner = format.inside(element)
        val rows = rowsOf(element)
        val columns = rows.maxOfOrNull { row -> row.sumOf { spanOf(it) } } ?: 0
        val captions = element.children().filter { it.normalName() == "caption" }.flatMap { blocksIn(it, inner, quoted, depth + 1) }
        // A table with a row or a column only is the layout of a page, not a table of data
        if (rows.size < 2 || columns < 2) {
            return captions + rows.flatten().flatMap { blocksIn(it, inner.inside(it), quoted, depth + 1) }
        }
        val cells = rows.map { row ->
            val written = row.flatMap { cell -> listOf(cellText(cell, inner, depth)) + List(spanOf(cell) - 1) { "" } }
            written + List(columns - written.size) { "" }
        }
        // The columns a span of a cell made, with nothing of their own
        val kept = (0 until columns).filter { column -> cells.any { it[column].isNotEmpty() } }
        if (kept.size < 2) return captions + rows.flatten().flatMap { blocksIn(it, inner.inside(it), quoted, depth + 1) }
        formatted = true
        val lines = mutableListOf<String>()
        for ((index, row) in cells.withIndex()) {
            lines += "| " + kept.joinToString(" | ") { row[it] } + " |"
            if (index == 0) lines += "| " + kept.joinToString(" | ") { alignmentOf(cellAt(rows[0], it)) } + " |"
        }
        return captions + Block(lines)
    }

    /** The rows of a [table], those of a table inside it left out (a cell holds its text). */
    private fun rowsOf(table: Element): List<List<Element>> {
        val rows = mutableListOf<List<Element>>()
        for (child in table.children()) {
            when (child.normalName()) {
                "tr" -> rows += cellsOf(child)
                "thead", "tbody", "tfoot" -> for (row in child.children()) if (row.normalName() == "tr") rows += cellsOf(row)
            }
        }
        return rows.filter { it.isNotEmpty() }
    }

    private fun cellsOf(row: Element): List<Element> = row.children().filter { it.normalName() == "td" || it.normalName() == "th" }

    private fun spanOf(cell: Element): Int = (cell.attr("colspan").toIntOrNull() ?: 1).coerceIn(1, MAX_COLUMNS)

    private fun cellAt(row: List<Element>, column: Int): Element? {
        var at = 0
        for (cell in row) {
            at += spanOf(cell)
            if (column < at) return cell
        }
        return null
    }

    private fun cellText(cell: Element, format: HtmlFormat, depth: Int): String {
        val runs = mutableListOf<InlineRun>()
        for (child in cell.childNodes()) collect(child, format.inside(cell), runs, depth + 1)
        val written = InlineText.write(runs, oneLine = true, inTable = true)
        return written.text
    }

    private fun alignmentOf(cell: Element?): String {
        val align = cell?.let { HtmlFormat.styleOf(it)["text-align"] ?: it.attr("align").lowercase() }
        return when (align) {
            "center" -> ":---:"
            "right", "end" -> "---:"
            else -> "---"
        }
    }

    /** Whether [element] holds a block somewhere, as a `<b>` of Google Docs or an `<a>` around a `<div>`. */
    private fun holdsBlock(element: Element, depth: Int): Boolean {
        if (depth >= MAX_DEPTH) return false
        return holdingBlock.getOrPut(element) {
            element.children().any { !isIgnored(it) && (isBlock(it) || holdsBlock(it, depth + 1)) }
        }
    }

    internal companion object {
        /** How deep a tree is walked; deeper, an element gives its text only. */
        const val MAX_DEPTH = 256

        /** The most columns a cell may span. */
        private const val MAX_COLUMNS = 64

        private const val NESTED_INDENT = "  "

        private val BACKTICKS = Regex("`+")

        /** The classes of the numbers of the lines beside the code of a page. */
        private val GUTTER = Regex("""gutter|line-?numbers?$|^lineno""", RegexOption.IGNORE_CASE)

        /** The longest description of an image kept: an emoji or a sign, as chats and GitHub draw them. */
        private const val MOST_SIGN_CHARS = 8

        /** Whether the description [alt] of an image stands for a sign: any other description is not text that was on the page. */
        private fun isSign(alt: String): Boolean = alt.isNotBlank() && alt.length <= MOST_SIGN_CHARS && alt.none { it.isLetterOrDigit() }
        private val LANGUAGE = Regex("""[A-Za-z0-9+#._-]{1,32}""")
        private val LANGUAGE_CLASS = Regex("""^(?:language-|lang-|highlight-source-)(.+)$""")

        private val BLOCKS = setOf(
            "address", "article", "aside", "blockquote", "center", "dd", "details", "dialog", "dir", "div", "dl", "dt",
            "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup",
            "hr", "li", "main", "menu", "nav", "ol", "p", "pre", "section", "summary", "table", "ul"
        )

        /** The elements with nothing to read: their content is code, interface or another document. */
        private val IGNORED = setOf(
            "script", "style", "head", "title", "meta", "link", "template", "noscript", "iframe", "object", "embed",
            "svg", "canvas", "audio", "video", "button", "select", "option", "input", "textarea", "clipboard-copy"
        )

        fun isBlock(element: Element): Boolean = element.normalName() in BLOCKS

        /**
         * Whether [markdown] holds all the words of the tree of [root] that are meant to be read, in
         * their order: the letters and digits of the one are found in the other, which has the
         * addresses of its links and the languages of its code besides.
         */
        fun keepsTextOf(root: Element, markdown: String): Boolean {
            val source = StringBuilder().also { readableTextOf(root, it, 0) }
            var at = 0
            for (c in markdown) {
                if (at < source.length && c == source[at]) at++
            }
            return at == source.length
        }

        /** The letters and digits of what [node] holds that the conversion is meant to keep. */
        private fun readableTextOf(node: Node, out: StringBuilder, depth: Int) {
            when (node) {
                is TextNode -> node.getWholeText().filterTo(out) { it.isLetterOrDigit() }
                is Element -> when {
                    // A formula, and a script shifted (in Unicode or a formula, see ScriptShift), are written their own way
                    HtmlFormula.of(node) != null || isIgnored(node) || node.normalName() == "sup" || node.normalName() == "sub" -> Unit
                    depth >= MAX_DEPTH -> node.text().filterTo(out) { it.isLetterOrDigit() }
                    node.normalName() == "pre" -> for (child in codeOf(node).childNodes()) readableTextOf(child, out, depth + 1)
                    else -> for (child in node.childNodes()) readableTextOf(child, out, depth + 1)
                }
            }
        }

        /** Whether what [element] holds is not read: not meant to be, or not shown. */
        fun isIgnored(element: Element): Boolean {
            if (element.normalName() in IGNORED || element.hasAttr("hidden") || HtmlFormula.isDrawingOfScript(element) || HtmlFormula.isAccessibleCopy(element)) return true
            if (element.classNames().any { GUTTER.containsMatchIn(it) }) return true
            val style = HtmlFormat.styleOf(element)
            return style["display"] == "none" || style["visibility"] == "hidden"
        }

        /** The element holding the source of a block of code: its `<code>`, when it is the only one, and not the bar of a chat. */
        fun codeOf(block: Element): Element = block.getElementsByTag("code").singleOrNull() ?: block
    }
}
