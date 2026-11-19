package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Span

/**
 * The cells of a table of a text (a [Span] of [Kind.TABLE], GitHub's notation): its [rows], the
 * header first, each with the source range of the content of each of its cells (its spaces taken
 * off: an empty cell is an empty range where its content would go); the [alignments] of its
 * columns (`null`: none written, at the start); the [lines] of its rows in the source, the
 * delimiter row (the second line) included.
 *
 * Every row has as many cells as the header: the cells missing at the end of a row are empty
 * ranges at its end, the cells beyond the header are left out (as GitHub does).
 */
public class TableShape(
    public val rows: List<List<IntRange>>,
    public val alignments: List<TextAlignment?>,
    public val lines: List<IntRange>
) {
    /** The number of columns: the cells of the header. */
    public val columns: Int get() = alignments.size

    /** The row and the column of the cell whose content has [offset] (its ends included), or `null`. */
    public fun cellAt(offset: Int): Pair<Int, Int>? {
        for ((row, cells) in rows.withIndex()) {
            for ((column, cell) in cells.withIndex()) {
                if (offset >= cell.first && offset <= cell.last + 1) return row to column
            }
        }
        return null
    }

    public companion object {

        /** The shape of the table [table] of [text]. */
        public fun of(text: String, table: Span): TableShape {
            val masked = maskInline(text, table.start, table.end)
            val lines = mutableListOf<IntRange>()
            var lineStart = table.start
            while (lineStart <= table.end) {
                val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0 || it > table.end) table.end else it }
                lines += lineStart until lineEnd
                lineStart = lineEnd + 1
            }
            val header = cellsOf(masked, lines[0].first, lines[0].last + 1, table.start)
            val alignments = cellsOf(masked, lines[1].first, lines[1].last + 1, table.start).map { cell ->
                val written = text.substring(cell.first, cell.last + 1).trim()
                val left = written.startsWith(':')
                val right = written.endsWith(':')
                when {
                    left && right -> TextAlignment.CENTER
                    right -> TextAlignment.END
                    left -> TextAlignment.START
                    else -> null
                }
            }
            val rows = mutableListOf(header.map { trimmed(text, it) })
            for (line in lines.drop(2)) {
                val cells = cellsOf(masked, line.first, line.last + 1, table.start).map { trimmed(text, it) }
                rows += List(header.size) { column -> cells.getOrElse(column) { (line.last + 1) until (line.last + 1) } }
            }
            return TableShape(rows, alignments.take(header.size) + List((header.size - alignments.size).coerceAtLeast(0)) { null }, lines)
        }

        /**
         * The cells of the line from [start] to [end] of [masked] (its `|` in code or escaped
         * already masked), as the ranges between its `|`: a `|` at the start or at the end of the
         * line opens or closes the row, it is no cell. [base] is where [masked] starts in its text.
         */
        public fun cellsOf(masked: String, start: Int, end: Int, base: Int = 0): List<IntRange> {
            val from = start - base
            val to = end - base
            var first = from
            while (first < to && (masked[first] == ' ' || masked[first] == '\t')) first++
            var last = to
            while (last > first && (masked[last - 1] == ' ' || masked[last - 1] == '\t')) last--
            if (first < last && masked[first] == '|') first++
            if (last > first && masked[last - 1] == '|' && (last - 2 < first || masked[last - 2] != '\\')) last--
            val cells = mutableListOf<IntRange>()
            var cellStart = first
            for (k in first until last) {
                if (masked[k] == '|') {
                    cells += (cellStart + base) until (k + base)
                    cellStart = k + 1
                }
            }
            cells += (cellStart + base) until (last + base)
            return cells
        }

        /** [cell] without the spaces around its content (empty: at its middle). */
        private fun trimmed(text: String, cell: IntRange): IntRange {
            var first = cell.first
            var last = cell.last
            while (first <= last && text[first].isWhitespace()) first++
            while (last >= first && text[last].isWhitespace()) last--
            return if (first > last) (cell.first + (cell.last - cell.first + 1) / 2).let { it until it } else first..last
        }

        /** [text] from [start] to [end] with the `|` in its code and its escaped `|` masked. */
        private fun maskInline(text: String, start: Int, end: Int): String {
            val out = StringBuilder(text.substring(start, end))
            var k = 0
            while (k < out.length) {
                val c = out[k]
                when {
                    c == '\\' && k + 1 < out.length -> {
                        if (out[k + 1] == '|') out[k + 1] = ' '
                        k += 2
                        continue
                    }
                    c == '`' -> {
                        val close = out.indexOf("`", k + 1)
                        if (close > k && out.substring(k + 1, close).none { it == '\n' }) {
                            for (m in k + 1 until close) if (out[m] == '|') out[m] = ' '
                            k = close + 1
                            continue
                        }
                    }
                }
                k++
            }
            return out.toString()
        }
    }
}
