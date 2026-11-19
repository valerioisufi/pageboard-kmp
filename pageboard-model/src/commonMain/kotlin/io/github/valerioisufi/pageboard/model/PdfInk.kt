package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.geometry.Rect

/**
 * What a PDF page draws, text or not, as a grid laid over the page as it is displayed: a cell is
 * taken where the page is not its paper (a figure, an arrow, a box, a line of text). For the
 * placement of new content around what the text of the page does not tell (see `PageOccupancy`).
 *
 * @param width the width of the page, in any unit: with [height], its proportions.
 * @param taken the cells taken, row by row from the top: [columns] x [rows] of them.
 */
public class PdfInk(
    public val width: Float,
    public val height: Float,
    public val columns: Int,
    public val rows: Int,
    private val taken: BooleanArray
) {
    init {
        require(columns > 0 && rows > 0 && taken.size == columns * rows) { "A cell for each column of each row" }
    }

    /** Whether the cell at [column], [row] is taken. */
    public fun isTaken(column: Int, row: Int): Boolean = taken[row * columns + column]

    /** Whether nothing is drawn on the page. */
    public val isEmpty: Boolean get() = taken.none { it }

    /**
     * The cells taken as boxes of [rect], the rectangle the page is drawn in: the runs of each row,
     * those of the rows below joined to them while they span the same columns.
     */
    public fun boxesIn(rect: Rect): List<Rect> {
        val cellWidth = rect.width / columns
        val cellHeight = rect.height / rows
        val boxes = mutableListOf<Rect>()
        // The runs still growing down: from their first to their last column, since their first row
        var open = mutableMapOf<Pair<Int, Int>, Int>()
        fun close(run: Pair<Int, Int>, fromRow: Int, toRow: Int) {
            boxes += Rect(
                rect.left + run.first * cellWidth, rect.top + fromRow * cellHeight,
                rect.left + (run.second + 1) * cellWidth, rect.top + toRow * cellHeight
            )
        }
        for (row in 0..rows) {
            val runs = mutableListOf<Pair<Int, Int>>()
            if (row < rows) {
                var column = 0
                while (column < columns) {
                    if (!isTaken(column, row)) {
                        column++
                        continue
                    }
                    val from = column
                    while (column < columns && isTaken(column, row)) column++
                    runs += from to column - 1
                }
            }
            val next = mutableMapOf<Pair<Int, Int>, Int>()
            for (run in runs) next[run] = open.remove(run) ?: row
            for ((run, fromRow) in open) close(run, fromRow, row)
            open = next
        }
        return boxes
    }
}
