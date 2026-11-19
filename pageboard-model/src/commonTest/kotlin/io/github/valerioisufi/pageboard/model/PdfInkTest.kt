package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.placement.FreeSpace
import io.github.valerioisufi.pageboard.model.placement.PageOccupancy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfInkTest {
    /** A grid of [columns] x [rows], taken in [boxes] (column and row from, to). */
    private fun ink(columns: Int, rows: Int, vararg boxes: IntArray): PdfInk {
        val taken = BooleanArray(columns * rows)
        for ((fromColumn, fromRow, toColumn, toRow) in boxes) {
            for (row in fromRow..toRow) for (column in fromColumn..toColumn) taken[row * columns + column] = true
        }
        return PdfInk(columns.toFloat(), rows.toFloat(), columns, rows, taken)
    }

    @Test
    fun theCellsTakenAreBoxesOfTheRectangleThePageIsDrawnIn() {
        val ink = ink(10, 10, intArrayOf(2, 1, 5, 3), intArrayOf(8, 8, 9, 9))
        val boxes = ink.boxesIn(Rect(0f, 0f, 100f, 200f))
        assertEquals(setOf(Rect(20f, 20f, 60f, 80f), Rect(80f, 160f, 100f, 200f)), boxes.toSet())
        assertEquals(2, boxes.size)
        assertTrue(ink(4, 4).boxesIn(Rect(0f, 0f, 10f, 10f)).isEmpty())
        assertTrue(ink(4, 4).isEmpty)
    }

    @Test
    fun aShapeThatIsNotABoxIsCoveredByItsRows() {
        // An L: a column, then a row under it
        val ink = ink(6, 4, intArrayOf(0, 0, 0, 2), intArrayOf(0, 3, 4, 3))
        val boxes = ink.boxesIn(Rect(0f, 0f, 6f, 4f))
        assertEquals(setOf(Rect(0f, 0f, 1f, 3f), Rect(0f, 3f, 5f, 4f)), boxes.toSet())
    }

    @Test
    fun whatAPdfPageDrawsIsTakenOnItsPage() {
        val page = DrawPage(widthMm = 200f, heightMm = 100f).apply { add(DrawPdf(0)) }
        // A figure in the right half of a page of the same proportions
        val figure = ink(100, 50, intArrayOf(60, 10, 89, 39))
        val occupancy = PageOccupancy.of(page, pdfText = { null }, pdfInk = { figure })
        assertEquals(listOf(Rect(120f, 20f, 180f, 80f)), occupancy.boxes)
        val free = FreeSpace(occupancy)
        assertTrue(free.isFree(Rect(20f, 20f, 100f, 80f)))
        assertTrue(!free.isFree(Rect(100f, 30f, 150f, 60f)))
        // Without it, a PDF page whose text is not known is taken whole
        assertEquals(listOf(page.rect()), PageOccupancy.of(page).boxes)
    }

    @Test
    fun aPdfPageOfOtherProportionsIsTakenWhereItIsDrawn() {
        // A wide slide on a tall page: drawn in a band in its middle
        val page = DrawPage(widthMm = 100f, heightMm = 200f).apply { add(DrawPdf(0)) }
        val all = ink(4, 2, intArrayOf(0, 0, 3, 1))
        assertEquals(listOf(Rect(0f, 75f, 100f, 125f)), PageOccupancy.of(page, pdfText = { null }, pdfInk = { all }).boxes)
    }
}
