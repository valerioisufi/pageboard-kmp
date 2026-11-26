package io.github.valerioisufi.pageboard.html

import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.text.BlockQuote
import io.github.valerioisufi.pageboard.model.text.RichTextParser
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Span
import io.github.valerioisufi.pageboard.model.text.TableShape

/**
 * A text in the notation written as HTML, for the other applications a text is copied to (see
 * `docs/plans/import-and-clipboard.md`): headings, paragraphs, lists (checklists with their boxes),
 * quotes, blocks of code, tables, bold, italic, strikethrough, code, links and images; a formula is
 * its LaTeX between its dollars, which the applications show as it is.
 *
 * It reads the text as the editor does ([RichTextParser]), so that what is copied is what is seen.
 */
public object MarkdownToHtml {

    /** [markdown] as a fragment of HTML. */
    public fun convert(markdown: String): String {
        val spans = RichTextParser.parse(markdown)
        val out = StringBuilder()
        Writer(markdown, spans, out).blocks()
        return out.toString()
    }

    private class Writer(private val text: String, private val spans: List<Span>, private val out: StringBuilder) {

        private val inline = spans.filter { it.kind in INLINE }
        private val blockStarts = spans.filter { it.kind in BLOCKS }.groupBy { it.start }

        /** The lists open: the level and the tag of each. */
        private val lists = ArrayDeque<Pair<Int, String>>()

        fun blocks() {
            var line = 0
            val paragraph = mutableListOf<IntRange>()
            fun endParagraph() {
                if (paragraph.isEmpty()) return
                out.append("<p>")
                paragraph.forEachIndexed { index, range ->
                    if (index > 0) out.append("<br>")
                    inlineOf(range.first, range.last + 1)
                }
                out.append("</p>")
                paragraph.clear()
            }
            while (line <= text.length) {
                val lineEnd = text.indexOf('\n', line).let { if (it < 0) text.length else it }
                val block = blockStarts[line]?.let { starting -> starting.firstOrNull { it.kind != Kind.LIST_ITEM } ?: starting.first() }
                when {
                    block != null -> {
                        endParagraph()
                        if (block.kind != Kind.LIST_ITEM) closeLists(-1)
                        line = block(block)
                        continue
                    }
                    text.substring(line, lineEnd).isBlank() -> {
                        endParagraph()
                        closeLists(-1)
                    }
                    else -> {
                        closeLists(-1)
                        paragraph += line until lineEnd
                    }
                }
                line = lineEnd + 1
            }
            endParagraph()
            closeLists(-1)
        }

        /** Writes the block [span]; where the text goes on after it (a list item stops at an item nested in it). */
        private fun block(span: Span): Int {
            when (span.kind) {
                Kind.LIST_ITEM -> return item(span)
                Kind.HEADING -> {
                    val level = span.level.coerceIn(1, 6)
                    out.append("<h$level>")
                    inlineOf(span.contentStart, span.contentEnd)
                    out.append("</h$level>")
                }
                Kind.CODE_BLOCK -> {
                    out.append("<pre><code")
                    if (span.language.isNotEmpty()) out.append(" class=\"language-").append(escape(span.language)).append('"')
                    out.append('>').append(escape(codeOf(span))).append("</code></pre>")
                }
                Kind.TABLE -> table(span)
                Kind.QUOTE -> quote(span)
                else -> inlineOf(span.start, span.end)
            }
            return span.end + 1
        }

        /** The code of a block, its lines without the spaces of its fence. */
        private fun codeOf(span: Span): String {
            val code = text.substring(span.contentStart, span.contentEnd)
            if (span.level == 0) return code
            return code.split('\n').joinToString("\n") { it.drop(it.take(span.level).takeWhile { c -> c == ' ' }.length) }
        }

        private fun table(span: Span) {
            val shape = TableShape.of(text, span)
            out.append("<table>")
            shape.rows.forEachIndexed { row, cells ->
                out.append("<tr>")
                val tag = if (row == 0) "th" else "td"
                cells.forEachIndexed { column, cell ->
                    if (shape.merged.getOrNull(row)?.getOrNull(column) == true) return@forEachIndexed
                    out.append('<').append(tag)
                    val span = shape.spanOf(row, column)
                    if (span > 1) out.append(" colspan=\"").append(span).append('"')
                    when (shape.alignments.getOrNull(column)) {
                        TextAlignment.CENTER -> out.append(" style=\"text-align:center\"")
                        TextAlignment.END -> out.append(" style=\"text-align:right\"")
                        else -> {}
                    }
                    out.append('>')
                    inlineOf(cell.first, cell.last + 1)
                    out.append("</").append(tag).append('>')
                }
                out.append("</tr>")
            }
            out.append("</table>")
        }

        private fun quote(span: Span) {
            out.append("<blockquote>")
            var line = span.start
            var first = true
            while (line < span.end) {
                val lineEnd = text.indexOf('\n', line).let { if (it < 0 || it > span.end) span.end else it }
                val start = line + BlockQuote.markerLength(text, line)
                if (start < lineEnd) {
                    out.append(if (first) "<p>" else "<br>")
                    first = false
                    inlineOf(start, lineEnd)
                }
                line = lineEnd + 1
            }
            if (!first) out.append("</p>")
            out.append("</blockquote>")
        }

        private fun item(span: Span): Int {
            val tag = if (span.ordered) "ol" else "ul"
            closeLists(span.level)
            val open = lists.lastOrNull()
            if (open == null || open.first < span.level || open.second != tag) {
                if (open != null && open.first == span.level) closeLists(span.level - 1)
                out.append('<').append(tag)
                if (span.ordered && span.number != 1) out.append(" start=\"").append(span.number).append('"')
                out.append('>')
                lists.addLast(span.level to tag)
            }
            out.append("<li>")
            span.checked?.let { out.append(if (it) "☑ " else "☐ ") }
            // The text of the item and its continuation lines
            var line = span.contentStart
            var first = true
            while (line < span.end) {
                val lineEnd = text.indexOf('\n', line).let { if (it < 0 || it > span.end) span.end else it }
                val start = if (first) line else (line until lineEnd).firstOrNull { text[it] != ' ' && text[it] != '\t' } ?: lineEnd
                // A nested item, a block of the item: written after it, as blocks of their own
                if (!first && blockStarts.containsKey(line)) break
                if (!first) out.append("<br>")
                inlineOf(start, lineEnd)
                first = false
                line = lineEnd + 1
            }
            out.append("</li>")
            return line
        }

        /** Closes the lists deeper than [level]. */
        private fun closeLists(level: Int) {
            while (lists.isNotEmpty() && lists.last().first > level) out.append("</").append(lists.removeLast().second).append('>')
        }

        /** The text from [from] to [to] with its formats, its delimiters hidden. */
        private fun inlineOf(from: Int, to: Int, depth: Int = 0) {
            var at = from
            val inside = inline.filter { it.start >= from && it.end <= to }.sortedBy { it.start }
            for (span in inside) {
                if (span.start < at) continue
                out.append(escape(text.substring(at, span.start)))
                inlineSpan(span, depth)
                at = span.end
            }
            if (at < to) out.append(escape(text.substring(at, to)))
        }

        private fun inlineSpan(span: Span, depth: Int) {
            val content: () -> Unit = {
                if (depth < MAX_DEPTH) inlineOf(span.contentStart, span.contentEnd, depth + 1) else out.append(escape(text.substring(span.contentStart, span.contentEnd)))
            }
            when (span.kind) {
                Kind.BOLD -> wrap("strong", content)
                Kind.ITALIC -> wrap("em", content)
                Kind.STRIKE -> wrap("s", content)
                Kind.CODE -> out.append("<code>").append(escape(text.substring(span.contentStart, span.contentEnd))).append("</code>")
                // Its LaTeX between its dollars
                Kind.FORMULA -> out.append(escape(text.substring(span.start, span.end)))
                Kind.ESCAPE -> out.append(escape(text.substring(span.contentStart, span.contentEnd)))
                Kind.LINK -> {
                    val address = text.substring((span.contentEnd + 2).coerceAtMost(span.end), (span.end - 1).coerceAtLeast(span.contentEnd)).trim()
                    out.append("<a href=\"").append(escape(address)).append("\">")
                    content()
                    out.append("</a>")
                }
                Kind.IMAGE -> {
                    val address = text.substring((span.contentEnd + 2).coerceAtMost(span.end), (span.end - 1).coerceAtLeast(span.contentEnd)).trim()
                    out.append("<img src=\"").append(escape(address)).append("\" alt=\"").append(escape(text.substring(span.contentStart, span.contentEnd))).append("\">")
                }
                else -> out.append(escape(text.substring(span.start, span.end)))
            }
        }

        private fun wrap(tag: String, content: () -> Unit) {
            out.append('<').append(tag).append('>')
            content()
            out.append("</").append(tag).append('>')
        }
    }

    private fun escape(text: String): String {
        if (text.none { it == '&' || it == '<' || it == '>' || it == '"' }) return text
        val out = StringBuilder(text.length + 8)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private val INLINE = setOf(Kind.BOLD, Kind.ITALIC, Kind.STRIKE, Kind.CODE, Kind.FORMULA, Kind.ESCAPE, Kind.LINK, Kind.IMAGE)
    private val BLOCKS = setOf(Kind.HEADING, Kind.CODE_BLOCK, Kind.TABLE, Kind.QUOTE, Kind.LIST_ITEM)

    /** How deep the formats may nest in what is written. */
    private const val MAX_DEPTH = 32
}
