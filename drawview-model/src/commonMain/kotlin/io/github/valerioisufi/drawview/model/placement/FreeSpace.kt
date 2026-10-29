package io.github.valerioisufi.drawview.model.placement

import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The free space of a page: a grid of square cells of [cellMm], a cell taken when a box of the
 * occupancy, grown by [clearanceMm], covers it, or when it is within [pageMarginMm] of an edge.
 * Whether a rectangle is free is answered in constant time (a table of sums of the cells taken).
 *
 * The same occupancy gives the same free space on every device.
 */
public class FreeSpace(
    occupancy: PageOccupancy,
    public val cellMm: Float = DEFAULT_CELL_MM,
    public val clearanceMm: Float = DEFAULT_CLEARANCE_MM,
    public val pageMarginMm: Float = DEFAULT_PAGE_MARGIN_MM
) {
    /** Columns of the grid. */
    public val columns: Int = max(1, floor(occupancy.widthMm / cellMm).toInt())

    /** Rows of the grid. */
    public val rows: Int = max(1, floor(occupancy.heightMm / cellMm).toInt())

    private val taken = BooleanArray(columns * rows)

    /** Cells taken in the rectangle from (0, 0) to (column, row) excluded, (columns + 1) × (rows + 1). */
    private val sums = IntArray((columns + 1) * (rows + 1))

    init {
        val margin = ceil(pageMarginMm / cellMm).toInt()
        for (row in 0 until rows) for (column in 0 until columns) {
            if (row < margin || column < margin || row >= rows - margin || column >= columns - margin) taken[row * columns + column] = true
        }
        for (box in occupancy.boxes) {
            val grown = box.inflate(clearanceMm)
            val fromColumn = max(0, floor(grown.left / cellMm).toInt())
            val toColumn = min(columns, ceil(grown.right / cellMm).toInt())
            val fromRow = max(0, floor(grown.top / cellMm).toInt())
            val toRow = min(rows, ceil(grown.bottom / cellMm).toInt())
            for (row in fromRow until toRow) for (column in fromColumn until toColumn) taken[row * columns + column] = true
        }
        for (row in 0 until rows) {
            var inRow = 0
            for (column in 0 until columns) {
                if (taken[row * columns + column]) inRow++
                sums[(row + 1) * (columns + 1) + column + 1] = sums[row * (columns + 1) + column + 1] + inRow
            }
        }
    }

    /** Whether the cell at [column], [row] is taken (outside the grid: taken). */
    public fun isTaken(column: Int, row: Int): Boolean =
        column !in 0 until columns || row !in 0 until rows || taken[row * columns + column]

    /** Whether every cell [rect] (page millimeters) touches is free, and it is inside the grid. */
    public fun isFree(rect: Rect): Boolean {
        if (rect.isEmpty) return false
        val fromColumn = floor(rect.left / cellMm + EPSILON).toInt()
        val toColumn = ceil(rect.right / cellMm - EPSILON).toInt()
        val fromRow = floor(rect.top / cellMm + EPSILON).toInt()
        val toRow = ceil(rect.bottom / cellMm - EPSILON).toInt()
        if (fromColumn < 0 || fromRow < 0 || toColumn > columns || toRow > rows) return false
        return takenIn(fromColumn, fromRow, toColumn, toRow) == 0
    }

    /** The free cells as rectangles (page millimeters), the runs of each row: to show the free space. */
    public fun freeRuns(): List<Rect> = buildList {
        for (row in 0 until rows) {
            var column = 0
            while (column < columns) {
                if (taken[row * columns + column]) {
                    column++
                    continue
                }
                val start = column
                while (column < columns && !taken[row * columns + column]) column++
                add(Rect(start * cellMm, row * cellMm, column * cellMm, (row + 1) * cellMm))
            }
        }
    }

    private fun takenIn(fromColumn: Int, fromRow: Int, toColumn: Int, toRow: Int): Int {
        val w = columns + 1
        return sums[toRow * w + toColumn] - sums[fromRow * w + toColumn] - sums[toRow * w + fromColumn] + sums[fromRow * w + fromColumn]
    }

    public companion object {
        public const val DEFAULT_CELL_MM: Float = 2f
        public const val DEFAULT_CLEARANCE_MM: Float = 3f
        public const val DEFAULT_PAGE_MARGIN_MM: Float = 10f
        private const val EPSILON = 1e-3f
    }
}
