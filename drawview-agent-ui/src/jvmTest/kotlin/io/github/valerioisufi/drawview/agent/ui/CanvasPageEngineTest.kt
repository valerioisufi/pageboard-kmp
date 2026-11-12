package io.github.valerioisufi.drawview.agent.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import io.github.valerioisufi.drawview.canvas.render.tile.PdfTileAdapter
import io.github.valerioisufi.drawview.model.PdfInk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class CanvasPageEngineTest {
    /** A PDF of one page of [widthMm] x [heightMm], which draws what [draw] does in the unit square. */
    private class FakePdf(
        private val widthMm: Float,
        private val heightMm: Float,
        private val paper: Color = Color.White,
        private val draw: (fill: (left: Float, top: Float, right: Float, bottom: Float, Color) -> Unit) -> Unit
    ) : PdfTileAdapter {
        var rendered = 0

        override fun renderPdfPage(canvas: Canvas, resourcePath: String, pdfPageIndex: Int, pageRect: Rect, clipRect: Rect) {
            rendered++
            canvas.drawRect(pageRect, Paint().apply { color = paper })
            draw { left, top, right, bottom, color ->
                canvas.drawRect(
                    Rect(pageRect.left + left * pageRect.width, pageRect.top + top * pageRect.height, pageRect.left + right * pageRect.width, pageRect.top + bottom * pageRect.height),
                    Paint().apply { this.color = color }
                )
            }
        }

        override fun getPageSizesMm(resourcePath: String): List<Pair<Float, Float>> = listOf(widthMm to heightMm)
    }

    private fun PdfInk.takenCells(): Int = (0 until rows).sumOf { row -> (0 until columns).count { isTaken(it, row) } }

    @Test
    fun whatAPdfPageDrawsIsTakenAndItsPaperIsNot() {
        val pdf = FakePdf(200f, 100f) { fill ->
            fill(0.5f, 0.2f, 0.9f, 0.8f, Color(0xFF2060A0))
            // A hairline: an arrow or a rule counts too
            fill(0.1f, 0.5f, 0.3f, 0.505f, Color.Black)
        }
        val engine = CanvasPageEngine(null, pdfTiles = { pdf })
        val ink = assertNotNull(engine.pdfInk("slides.pdf", 0))
        assertEquals(100 to 50, ink.columns to ink.rows)
        assertTrue(ink.isTaken(70, 25))
        assertTrue(ink.isTaken(10, 25))
        assertFalse(ink.isTaken(10, 10))
        assertFalse(ink.isTaken(45, 25))
        // The figure is 40 x 30 cells, the line 10 cells: little more is taken around them
        assertTrue(ink.takenCells() in 1210..1400, "${ink.takenCells()} cells taken")
        // A PDF does not change: its page is drawn once
        engine.pdfInk("slides.pdf", 0)
        assertEquals(1, pdf.rendered)
    }

    @Test
    fun aPageOnColoredPaperIsFreeWhereItIsItsPaper() {
        val pdf = FakePdf(100f, 100f, paper = Color(0xFF1B2A41)) { fill -> fill(0.2f, 0.2f, 0.4f, 0.4f, Color.White) }
        val ink = assertNotNull(CanvasPageEngine(null, pdfTiles = { pdf }).pdfInk("dark.pdf", 0))
        assertTrue(ink.isTaken(15, 15))
        assertFalse(ink.isTaken(40, 40))
    }

    @Test
    fun anEmptyPageIsFreeAndAPageThatIsAPictureIsTaken() {
        val empty = assertNotNull(CanvasPageEngine(null, pdfTiles = { FakePdf(210f, 297f) { } }).pdfInk("empty.pdf", 0))
        assertTrue(empty.isEmpty)
        // A photograph: no two neighbouring pixels the same
        val photo = FakePdf(60f, 40f) { fill ->
            for (y in 0 until 60) for (x in 0 until 90) fill(x / 90f, y / 60f, (x + 1) / 90f, (y + 1) / 60f, Color((x * 37 + y * 91) % 256, (x * 53) % 256, (y * 71) % 256))
        }
        val ink = assertNotNull(CanvasPageEngine(null, pdfTiles = { photo }).pdfInk("photo.pdf", 0))
        assertTrue(ink.takenCells() > ink.columns * ink.rows * 9 / 10, "${ink.takenCells()} of ${ink.columns * ink.rows}")
    }

    @Test
    fun withoutAPdfEngineOrAPageNothingIsTold() {
        assertNull(CanvasPageEngine(null).pdfInk("slides.pdf", 0))
        assertNull(CanvasPageEngine(null, pdfTiles = { FakePdf(210f, 297f) { } }).pdfInk("slides.pdf", 3))
    }

    @Test
    fun readingWhatAPageDrawsIsFast() {
        val pdf = FakePdf(210f, 297f) { fill -> fill(0.2f, 0.2f, 0.8f, 0.5f, Color.Red) }
        val engine = CanvasPageEngine(null, pdfTiles = { pdf })
        engine.pdfInk("warm.pdf", 0)
        val time = measureTime { repeat(10) { engine.pdfInk("page-$it.pdf", 0) } } / 10
        println("pdfInk of an A4 page, its drawing apart: $time")
        assertTrue(time.inWholeMilliseconds < 200, "$time")
    }
}
