package io.github.valerioisufi.drawview.canvas.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import io.github.valerioisufi.drawview.model.text.RichTextParser.Span
import io.github.valerioisufi.drawview.model.text.TableShape
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
        ALIGN_START, ALIGN_CENTER, ALIGN_END
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
        val index = caret.row * columns + caret.column + if (backwards) -1 else 1
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
        var row = caret.row
        var column = caret.column
        when (action) {
            Action.ADD_ROW_ABOVE, Action.ADD_ROW_BELOW -> return addRow(value, caret, below = action == Action.ADD_ROW_BELOW)
            Action.ADD_COLUMN_LEFT, Action.ADD_COLUMN_RIGHT -> {
                if (alignments.size >= MAX_SIZE) return value
                val at = if (action == Action.ADD_COLUMN_RIGHT) column + 1 else column
                for (cellsOfRow in cells) cellsOfRow.add(at, "")
                alignments.add(at, null)
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
                for (cellsOfRow in cells) cellsOfRow.removeAt(column)
                alignments.removeAt(column)
                column = min(column, alignments.size - 1)
            }
            Action.DELETE_TABLE -> return deleteTable(value, caret)
            Action.ALIGN_START -> alignments[column] = TextAlignment.START
            Action.ALIGN_CENTER -> alignments[column] = TextAlignment.CENTER
            Action.ALIGN_END -> alignments[column] = TextAlignment.END
        }
        return rewrite(value, caret, cells, alignments, row, column)
    }

    /**
     * Keeps a change of [oldValue] into [newValue] inside the cells of a table: a `|` typed or pasted
     * in a cell is escaped (`\|`), a line break pasted is a space and one typed goes to the cell
     * below, a deletion of a `|` or of a space around a cell (Backspace at the start of
     * a cell, Delete at its end) moves the caret instead. `null` when the change needs nothing.
     */
    public fun keepInCells(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        val old = oldValue.text
        val new = newValue.text
        if (old == new || !oldValue.selection.collapsed) return null
        val caret = oldValue.selection.start
        val inTable = caretIn(old, caret) ?: return null
        val cell = inTable.shape.rows[inTable.row][inTable.column]
        // Text typed or pasted in the cell: its pipes escaped, its line breaks spaces (a line break
        // alone is the cell below)
        val inserted = new.length - old.length
        if (inserted > 0 && new.startsWith(old.substring(0, caret)) && new.endsWith(old.substring(caret))) {
            val typed = new.substring(caret, caret + inserted)
            if (typed == "\n") return cellBelow(oldValue)
            if ('|' !in typed && '\n' !in typed) return null
            val kept = buildString {
                for ((k, c) in typed.withIndex()) {
                    when {
                        c == '|' && (k == 0 || typed[k - 1] != '\\') -> append("\\|")
                        c == '\n' -> append(' ')
                        else -> append(c)
                    }
                }
            }
            return TextFieldValue(old.substring(0, caret) + kept + old.substring(caret), TextRange(caret + kept.length))
        }
        // A deletion out of the content of the cell: the caret moves to the cell before or after
        if (new.length == old.length - 1) {
            val backspace = caret > 0 && new == old.removeRange(caret - 1, caret) && caret - 1 < cell.first
            val delete = caret < old.length && new == old.removeRange(caret, caret + 1) && caret > cell.last
            if (backspace) return moveHorizontally(oldValue, left = true)
            if (delete) return moveHorizontally(oldValue, left = false)
        }
        return null
    }

    /** Adds an empty row above or [below] the row of [caret] (below the header at least), the caret in its [column]. */
    private fun addRow(value: TextFieldValue, caret: Caret, below: Boolean, column: Int = caret.column): TextFieldValue {
        val cells = cellsOf(value.text, caret.shape).toMutableList()
        val at = if (below) caret.row + 1 else max(caret.row, 1)
        cells.add(at, List(caret.shape.columns) { "" })
        return rewrite(value, caret, cells, caret.shape.alignments, at, column)
    }

    private fun deleteTable(value: TextFieldValue, caret: Caret): TextFieldValue {
        val text = value.text
        val end = if (caret.span.end < text.length) caret.span.end + 1 else caret.span.end
        val start = if (end == caret.span.end && caret.span.start > 0) caret.span.start - 1 else caret.span.start
        return TextFieldValue(text.removeRange(start, end), TextRange(start.coerceAtMost(text.length - (end - start))))
    }

    /** The table of [caret] written again from [cells] and [alignments], the caret at the end of the cell [row], [column]. */
    private fun rewrite(value: TextFieldValue, caret: Caret, cells: List<List<String>>, alignments: List<TextAlignment?>, row: Int, column: Int): TextFieldValue {
        val text = value.text
        val table = write(cells, alignments)
        val newText = text.substring(0, caret.span.start) + table + text.substring(caret.span.end)
        val shape = TableShape.of(newText, tableAt(newText, caret.span.start))
        return TextFieldValue(newText, TextRange(endOf(shape, row.coerceIn(0, shape.rows.size - 1), column.coerceIn(0, shape.columns - 1))))
    }

    /** The content of the cells of [shape] in [text]. */
    private fun cellsOf(text: String, shape: TableShape): List<List<String>> =
        shape.rows.map { row -> row.map { text.substring(it.first, it.last + 1) } }

    /** A table of [cells] (the header first) and [alignments], one row a line, without a line break at its end. */
    internal fun write(cells: List<List<String>>, alignments: List<TextAlignment?>): String = buildString {
        fun row(contents: List<String>) {
            append('|')
            for (content in contents) append(' ').append(content.ifEmpty { " " }).append(" |")
        }
        row(cells[0])
        append("\n|")
        for (alignment in alignments) {
            append(
                when (alignment) {
                    null -> " --- |"
                    TextAlignment.START -> " :-- |"
                    TextAlignment.CENTER -> " :-: |"
                    TextAlignment.END -> " --: |"
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
}
