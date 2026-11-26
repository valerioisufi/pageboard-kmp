package io.github.valerioisufi.pageboard.canvas.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.text.RichTextParser
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Span
import io.github.valerioisufi.pageboard.model.text.TableShape
import kotlin.math.max
import kotlin.math.min

/**
 * The editing of the tables of a text being written, as changes of its notation (GitHub's, see
 * [TableShape]): inserting one, moving the caret from cell to cell, adding and deleting rows and
 * columns, aligning a column. The caret of a table is always in the content of one of its cells;
 * a change of the shape of a table writes it again, one row a line, its cells between `|`.
 */
public object TableEditing {

    /** What [TableEditing.apply] does to the table of the caret. */
    public enum class Action {
        ADD_ROW_ABOVE, ADD_ROW_BELOW, ADD_COLUMN_LEFT, ADD_COLUMN_RIGHT,
        DELETE_ROW, DELETE_COLUMN, DELETE_TABLE,
        ALIGN_START, ALIGN_CENTER, ALIGN_END,

        /** The cell of the caret merged with the cell after it in its row (its content after its own). */
        MERGE_RIGHT,

        /** The cells merged into the cell of the caret apart again, empty. */
        SPLIT,

        /** The column of the caret wider than the others, or narrower (the widths set by hand, see [TableShape.widths]). */
        WIDER, NARROWER
    }

    /** A table of a text: its [span], its [shape], the [row] and [column] of the caret in it. */
    public class Caret(public val span: Span, public val shape: TableShape, public val row: Int, public val column: Int)

    /** The table of the caret (the start of the selection) of [value], with its cell, or `null`. */
    public fun caretIn(value: TextFieldValue): Caret? = caretIn(value.text, min(value.selection.start, value.selection.end))

    /**
     * The table of [text] with [offset] in it, with its cell (on a `|`, the cell before it), or
     * `null`; the end of the last line of a table is after it, not in it.
     */
    public fun caretIn(text: String, offset: Int): Caret? {
        val span = RichTextParser.parse(text).firstOrNull { it.kind == Kind.TABLE && offset >= it.start && offset <= it.end } ?: return null
        val shape = TableShape.of(text, span)
        val cell = shape.cellAt(offset)
        if (cell == null && offset == span.end) return null
        val (row, column) = cell ?: nearestCell(shape, offset)
        return Caret(span, shape, row, column)
    }

    /** The cell of [shape] nearest before [offset] (on a `|` or a space around a cell). */
    private fun nearestCell(shape: TableShape, offset: Int): Pair<Int, Int> {
        var best = 0 to 0
        for ((row, cells) in shape.rows.withIndex()) for ((column, cell) in cells.withIndex()) {
            if (cell.first <= offset) best = row to column
        }
        return best
    }

    /** Where the caret goes in the cell [row], [column] of [shape]: at the end of its content. */
    private fun endOf(shape: TableShape, row: Int, column: Int): Int = shape.rows[row][column].last + 1

    /**
     * Inserts a table of [rows] rows (the header included) and [columns] columns, its cells empty,
     * on lines of its own after the line of the caret (in place of an empty line); the caret in its
     * first cell.
     */
    public fun insert(value: TextFieldValue, rows: Int, columns: Int): TextFieldValue {
        val rowCount = rows.coerceIn(2, MAX_SIZE)
        val columnCount = columns.coerceIn(1, MAX_SIZE)
        val start = min(value.selection.start, value.selection.end)
        val text = value.text
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val table = write(List(rowCount) { List(columnCount) { "" } }, List(columnCount) { null })
        val emptyLine = text.substring(lineStart, lineEnd).isBlank()
        val before = if (emptyLine) text.substring(0, lineStart) else text.substring(0, lineEnd) + "\n"
        // A line after the table, for the caret to leave it
        val after = text.substring(lineEnd).ifEmpty { "\n" }
        val newText = before + table + after
        val shape = TableShape.of(newText, tableAt(newText, before.length))
        return TextFieldValue(newText, TextRange(endOf(shape, 0, 0)))
    }

    /**
     * Tab (or Shift+Tab, [backwards]) in a table: the caret to the end of the next cell (the
     * previous one), the next row when the row ends; after the last cell a new row is added. `null`
     * out of the tables.
     */
    public fun nextCell(value: TextFieldValue, backwards: Boolean): TextFieldValue? {
        val caret = caretIn(value) ?: return null
        val columns = caret.shape.columns
        val step = if (backwards) -1 else 1
        var index = caret.row * columns + caret.shape.ownerOf(caret.row, caret.column) + step
        // The cells merged into another are passed over
        while (index in 0 until caret.shape.rows.size * columns && caret.shape.merged[index / columns][index % columns]) index += step
        if (index < 0) return value.copy(selection = TextRange(endOf(caret.shape, 0, 0)))
        if (index >= caret.shape.rows.size * columns) return addRow(value, caret, below = true, column = 0)
        return value.copy(selection = TextRange(endOf(caret.shape, index / columns, index % columns)))
    }

    /** Enter in a table: the caret to the cell below, a new row added under the last one. `null` out of the tables. */
    public fun cellBelow(value: TextFieldValue): TextFieldValue? {
        val caret = caretIn(value) ?: return null
        if (caret.row + 1 >= caret.shape.rows.size) return addRow(value, caret, below = true)
        return value.copy(selection = TextRange(endOf(caret.shape, caret.row + 1, caret.column)))
    }

    /**
     * An arrow up or down ([up]) in a table: the caret to the cell above or below, out of the table
     * from its first or last row (to the line before it or after it). `null` out of the tables.
     */
    public fun moveVertically(value: TextFieldValue, up: Boolean): TextFieldValue? {
        val caret = caretIn(value) ?: return null
        val row = caret.row + if (up) -1 else 1
        val offset = when {
            row < 0 -> max(caret.span.start - 1, 0)
            row >= caret.shape.rows.size -> if (caret.span.end < value.text.length) caret.span.end + 1 else caret.span.end
            else -> endOf(caret.shape, row, caret.column)
        }
        return value.copy(selection = TextRange(offset))
    }

    /**
     * An arrow left or right ([left]) in a table: the caret by a character in its cell, to the end of
     * the previous cell or the start of the next one at its ends, out of the table from its first or
     * last cell. `null` out of the tables.
     */
    public fun moveHorizontally(value: TextFieldValue, left: Boolean): TextFieldValue? {
        val caret = caretIn(value) ?: return null
        val offset = min(value.selection.start, value.selection.end).let { if (left) it else max(value.selection.start, value.selection.end) }
        val cell = caret.shape.rows[caret.row][caret.column]
        val columns = caret.shape.columns
        val index = caret.row * columns + caret.column
        val target = when {
            !value.selection.collapsed -> offset
            left && offset > cell.first -> offset - 1
            !left && offset <= cell.last -> offset + 1
            left && index == 0 -> max(caret.span.start - 1, 0)
            !left && index == caret.shape.rows.size * columns - 1 -> caret.span.end
            left -> endOf(caret.shape, (index - 1) / columns, (index - 1) % columns)
            else -> caret.shape.rows[(index + 1) / columns][(index + 1) % columns].first
        }
        return value.copy(selection = TextRange(target))
    }

    /** [action] on the table of the caret of [value]; `null` out of the tables. */
    public fun apply(value: TextFieldValue, action: Action): TextFieldValue? {
        val caret = caretIn(value) ?: return null
        val text = value.text
        val cells = cellsOf(text, caret.shape).map { it.toMutableList() }.toMutableList()
        val alignments = caret.shape.alignments.toMutableList()
        var widths = caret.shape.widths?.toMutableList()
        var row = caret.row
        // A cell merged into another: the cell that shows it
        var column = caret.shape.ownerOf(caret.row, caret.column)
        when (action) {
            Action.ADD_ROW_ABOVE, Action.ADD_ROW_BELOW -> return addRow(value, caret, below = action == Action.ADD_ROW_BELOW)
            Action.ADD_COLUMN_LEFT, Action.ADD_COLUMN_RIGHT -> {
                if (alignments.size >= MAX_SIZE) return value
                val at = if (action == Action.ADD_COLUMN_RIGHT) column + 1 else column
                for (cellsOfRow in cells) cellsOfRow.add(at, "")
                alignments.add(at, null)
                widths?.add(at, widths.average().toInt())
                column = at
            }
            Action.DELETE_ROW -> {
                // The header stays: deleting it deletes the table
                if (row == 0 || cells.size <= 2) return deleteTable(value, caret)
                cells.removeAt(row)
                row = min(row, cells.size - 1)
            }
            Action.DELETE_COLUMN -> {
                if (alignments.size <= 1) return deleteTable(value, caret)
                for (cellsOfRow in cells) {
                    // A cell merged into the one deleted takes its place, the content of that one
                    if (column + 1 < cellsOfRow.size && caret.shape.merged[cells.indexOf(cellsOfRow)][column + 1]) cellsOfRow[column + 1] = cellsOfRow[column]
                    cellsOfRow.removeAt(column)
                }
                alignments.removeAt(column)
                widths?.removeAt(column)
                column = min(column, alignments.size - 1)
            }
            Action.DELETE_TABLE -> return deleteTable(value, caret)
            Action.ALIGN_START -> alignments[column] = TextAlignment.START
            Action.ALIGN_CENTER -> alignments[column] = TextAlignment.CENTER
            Action.ALIGN_END -> alignments[column] = TextAlignment.END
            Action.MERGE_RIGHT -> {
                val next = column + caret.shape.spanOf(row, column)
                if (next >= alignments.size) return value
                val moved = cells[row][next].trim()
                if (moved.isNotEmpty() && moved != TableShape.MERGED.toString()) cells[row][column] = (cells[row][column].trim() + " " + moved).trim()
                cells[row][next] = TableShape.MERGED.toString()
            }
            Action.SPLIT -> {
                val span = caret.shape.spanOf(row, column)
                if (span <= 1) return value
                for (k in column + 1 until column + span) cells[row][k] = ""
            }
            Action.WIDER, Action.NARROWER -> {
                val current = widths ?: MutableList(alignments.size) { DEFAULT_WIDTH }
                val step = if (action == Action.WIDER) WIDTH_STEP else -WIDTH_STEP
                current[column] = (current[column] + step).coerceIn(MIN_WIDTH, MAX_WIDTH)
                // All alike again: as wide as their content, as before
                widths = current.takeIf { it.distinct().size > 1 }
            }
        }
        return rewrite(value, caret, cells, alignments, row, column, widths)
    }

    /**
     * Keeps a change of [oldValue] into [newValue] inside the cells of a table: a `|` typed or pasted
     * in a cell is escaped (`\|`), a line break pasted is a space and one typed goes to the cell
     * below, a deletion of a `|` or of a space around a cell (Backspace at the start of
     * a cell, Delete at its end) moves the caret instead, a longer one stops at the ends of the cell.
     * A selection replaced or deleted keeps the shape of the tables it touches (see
     * [replaceSelection]). `null` when the change needs nothing.
     */
    public fun keepInCells(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        val old = oldValue.text
        val new = newValue.text
        if (old == new) return null
        if (!oldValue.selection.collapsed) return replaceSelection(oldValue, newValue)
        val caret = oldValue.selection.start
        val inTable = caretIn(old, caret) ?: return null
        val cell = inTable.shape.rows[inTable.row][inTable.column]
        // Text typed or pasted in the cell: its pipes escaped, its line breaks spaces (a line break
        // alone is the cell below)
        val inserted = new.length - old.length
        if (inserted > 0 && new.startsWith(old.substring(0, caret)) && new.endsWith(old.substring(caret))) {
            val typed = new.substring(caret, caret + inserted)
            if (typed == "\n") return cellBelow(oldValue)
            if ('|' !in typed && '\n' !in typed && '\r' !in typed) return null
            val kept = keptInCell(typed)
            return TextFieldValue(old.substring(0, caret) + kept + old.substring(caret), TextRange(caret + kept.length))
        }
        // A deletion out of the content of the cell: the caret moves to the cell before or after
        if (new.length == old.length - 1) {
            val backspace = caret > 0 && new == old.removeRange(caret - 1, caret) && caret - 1 < cell.first
            val delete = caret < old.length && new == old.removeRange(caret, caret + 1) && caret > cell.last
            if (backspace) return moveHorizontally(oldValue, left = true)
            if (delete) return moveHorizontally(oldValue, left = false)
        }
        // A deletion of several characters (a word): up to the ends of the cell
        if (new.length < old.length - 1) {
            val removed = old.length - new.length
            if (caret - removed >= 0 && new == old.removeRange(caret - removed, caret) && caret - removed < cell.first) {
                val start = minOf(cell.first, caret)
                return TextFieldValue(old.removeRange(start, caret), TextRange(start))
            }
            if (caret + removed <= old.length && new == old.removeRange(caret, caret + removed) && caret + removed > cell.last + 1) {
                return TextFieldValue(old.removeRange(caret, maxOf(cell.last + 1, caret)), TextRange(caret))
            }
        }
        return null
    }

    /** [typed], text going in a cell: its pipes escaped, its line breaks spaces. */
    private fun keptInCell(typed: String): String = buildString {
        val text = typed.replace("\r\n", "\n").replace('\r', '\n')
        for ((k, c) in text.withIndex()) {
            when {
                c == '|' && (k == 0 || text[k - 1] != '\\') -> append("\\|")
                c == '\n' -> append(' ')
                else -> append(c)
            }
        }
    }

    /**
     * A selection of [oldValue] replaced by what [newValue] has in its place, the tables it touches
     * only in part keeping their shape: in a table, the text goes in the cell of the start of the
     * selection, kept in it, and the cells the selection covers are emptied. `null` when it touches
     * no table, or only whole ones.
     */
    private fun replaceSelection(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        val old = oldValue.text
        val new = newValue.text
        val start = oldValue.selection.min
        val end = oldValue.selection.max
        val rest = old.substring(end)
        if (!new.startsWith(old.substring(0, start)) || !new.endsWith(rest) || new.length < start + rest.length) return null
        fun whole(span: Span) = start <= span.start && end >= span.end
        val first = caretIn(old, start)?.takeIf { !whole(it.span) }
        val last = caretIn(old, end)?.takeIf { end > it.span.start && !whole(it.span) }
        if (first == null && last == null) return null
        val inserted = new.substring(start, new.length - rest.length)
        val kept = if (first != null) keptInCell(inserted) else inserted
        fun cellOf(caret: Caret) = caret.shape.rows[caret.row][caret.column]
        fun indexOf(caret: Caret) = caret.row * caret.shape.columns + caret.column
        val prefix = first?.let { cellOf(it).let { cell -> old.substring(cell.first, start.coerceIn(cell.first, cell.last + 1)) } }.orEmpty()
        val suffix = last?.let { cellOf(it).let { cell -> old.substring(end.coerceIn(cell.first, cell.last + 1), cell.last + 1) } }.orEmpty()
        // The cells of a table written again from its cells, those from [from] to [to] (in the order of reading) emptied
        fun written(caret: Caret, from: Int, to: Int, edit: (MutableList<MutableList<String>>) -> Unit): String {
            val columns = caret.shape.columns
            val cells = cellsOf(old, caret.shape).map { it.toMutableList() }.toMutableList()
            for (index in from..to) cells[index / columns][index % columns] = ""
            edit(cells)
            return write(cells, caret.shape.alignments, caret.shape.widths)
        }
        val sameTable = first != null && last != null && first.span.start == last.span.start
        val text: String
        val tableStart: Int
        if (sameTable) {
            val a = first!!
            val b = last!!
            if (indexOf(a) == indexOf(b)) {
                // In one cell: nothing else of the table changes
                val cell = cellOf(a)
                val at = start.coerceIn(cell.first, cell.last + 1)
                val to = end.coerceIn(cell.first, cell.last + 1)
                return TextFieldValue(old.substring(0, at) + kept + old.substring(to), TextRange(at + kept.length))
            }
            val table = written(a, indexOf(a), indexOf(b)) { cells ->
                cells[a.row][a.column] = prefix + kept
                cells[b.row][b.column] = suffix
            }
            tableStart = a.span.start
            text = old.substring(0, tableStart) + table + old.substring(a.span.end)
        } else {
            val head = if (first != null) {
                old.substring(0, first.span.start) + written(first, indexOf(first), first.shape.rows.size * first.shape.columns - 1) { cells ->
                    cells[first.row][first.column] = prefix + kept
                }
            } else {
                old.substring(0, start) + kept
            }
            val tail = if (last != null) {
                written(last, 0, indexOf(last)) { cells -> cells[last.row][last.column] = suffix } + old.substring(last.span.end)
            } else {
                rest
            }
            val glue = if ((first != null && !tail.startsWith("\n")) || (last != null && !head.endsWith("\n"))) "\n" else ""
            text = head + glue + tail
            if (first == null) return TextFieldValue(text, TextRange(start + kept.length))
            tableStart = first.span.start
        }
        val caret = first!!
        val shape = TableShape.of(text, tableAt(text, tableStart))
        val cell = shape.rows[caret.row][caret.column]
        return TextFieldValue(text, TextRange((cell.first + prefix.length + kept.length).coerceAtMost(cell.last + 1)))
    }

    /** Adds an empty row above or [below] the row of [caret] (below the header at least), the caret in its [column]. */
    private fun addRow(value: TextFieldValue, caret: Caret, below: Boolean, column: Int = caret.column): TextFieldValue {
        val cells = cellsOf(value.text, caret.shape).toMutableList()
        val at = if (below) caret.row + 1 else max(caret.row, 1)
        cells.add(at, List(caret.shape.columns) { "" })
        return rewrite(value, caret, cells, caret.shape.alignments, at, column, caret.shape.widths)
    }

    private fun deleteTable(value: TextFieldValue, caret: Caret): TextFieldValue {
        val text = value.text
        val end = if (caret.span.end < text.length) caret.span.end + 1 else caret.span.end
        val start = if (end == caret.span.end && caret.span.start > 0) caret.span.start - 1 else caret.span.start
        return TextFieldValue(text.removeRange(start, end), TextRange(start.coerceAtMost(text.length - (end - start))))
    }

    /** The table of [caret] written again from [cells] and [alignments], the caret at the end of the cell [row], [column]. */
    private fun rewrite(
        value: TextFieldValue,
        caret: Caret,
        cells: List<List<String>>,
        alignments: List<TextAlignment?>,
        row: Int,
        column: Int,
        widths: List<Int>? = caret.shape.widths
    ): TextFieldValue {
        val text = value.text
        val table = write(cells, alignments, widths)
        val newText = text.substring(0, caret.span.start) + table + text.substring(caret.span.end)
        val shape = TableShape.of(newText, tableAt(newText, caret.span.start))
        return TextFieldValue(newText, TextRange(endOf(shape, row.coerceIn(0, shape.rows.size - 1), column.coerceIn(0, shape.columns - 1))))
    }

    /** The content of the cells of [shape] in [text]. */
    private fun cellsOf(text: String, shape: TableShape): List<List<String>> =
        shape.rows.map { row -> row.map { text.substring(it.first, it.last + 1) } }

    /**
     * A table of [cells] (the header first), [alignments] and [widths] (the dashes of the delimiter
     * row, `null` for three each: the columns as wide as their content), one row a line, without a
     * line break at its end.
     */
    internal fun write(cells: List<List<String>>, alignments: List<TextAlignment?>, widths: List<Int>? = null): String = buildString {
        fun row(contents: List<String>) {
            append('|')
            for (content in contents) append(' ').append(content.ifEmpty { " " }).append(" |")
        }
        row(cells[0])
        append("\n|")
        for ((column, alignment) in alignments.withIndex()) {
            val dashes = widths?.getOrNull(column)?.coerceAtLeast(MIN_WIDTH) ?: 3
            append(
                when (alignment) {
                    null -> " " + "-".repeat(dashes) + " |"
                    TextAlignment.START -> " :" + "-".repeat(dashes - 1) + " |"
                    TextAlignment.CENTER -> " :" + "-".repeat(dashes - 2) + ": |"
                    TextAlignment.END -> " " + "-".repeat(dashes - 1) + ": |"
                }
            )
        }
        for (contents in cells.drop(1)) {
            append('\n')
            row(contents)
        }
    }

    private fun tableAt(text: String, start: Int): Span =
        RichTextParser.parse(text).first { it.kind == Kind.TABLE && it.start == start }

    /** The most rows and columns of a table made by the editor. */
    public const val MAX_SIZE: Int = 20

    /** The dashes of a column when the widths are first set by hand, and how many a step adds or takes. */
    private const val DEFAULT_WIDTH = 6
    private const val WIDTH_STEP = 2

    /** The fewest and the most dashes of a column set by hand. */
    private const val MIN_WIDTH = 3
    private const val MAX_WIDTH = 30
}
