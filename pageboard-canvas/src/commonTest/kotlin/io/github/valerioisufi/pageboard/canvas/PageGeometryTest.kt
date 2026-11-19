package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.PdfElement
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.TextMarkupElement
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokeOutline
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.TextMarkupKind
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.triangleOutline
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageGeometryTest {

    @Test
    fun aRuledPageHasALineEverySpacingAfterTheTop() {
        val pattern = PageBackground.Ruled(lineColor = 0x330000FF, spacingMm = 8f, thicknessMm = 0.5f).pattern(210f, 40f)!!
        assertContentEquals(floatArrayOf(8f, 16f, 24f, 32f), pattern.rows)
        assertEquals(0, pattern.columns.size)
        assertEquals(false, pattern.dots)
        assertEquals(0.5f, pattern.sizeMm)
        assertEquals(0x330000FF, pattern.color)
    }

    @Test
    fun aGridHasRowsAndColumnsAndTheDotsAreAtTheirCrossings() {
        val grid = PageBackground.Grid(spacingMm = 5f).pattern(12f, 11f)!!
        assertContentEquals(floatArrayOf(5f, 10f), grid.rows)
        assertContentEquals(floatArrayOf(5f, 10f), grid.columns)
        val dotted = PageBackground.Dotted(spacingMm = 5f, dotRadiusMm = 0.4f).pattern(12f, 11f)!!
        assertTrue(dotted.dots)
        assertEquals(0.4f, dotted.sizeMm)
        assertContentEquals(floatArrayOf(5f, 10f), dotted.columns)
    }

    @Test
    fun aPatternOfAnAreaStartsWithTheLineBeforeIt() {
        val pattern = PageBackground.Ruled(spacingMm = 10f).pattern(100f, 100f, Rect(0f, 34f, 100f, 61f))!!
        assertContentEquals(floatArrayOf(30f, 40f, 50f, 60f), pattern.rows)
        assertNull(PageBackground.Solid().pattern(100f, 100f))
    }

    @Test
    fun theLayersArePaintedFromThePdfPagesToTheStrokes() {
        val page = Page()
        val stroke = StrokeElement(mesh = triangle(), brushConfig = BrushConfig())
        val text = TextElement()
        val image = ImageElement()
        val pdf = PdfElement()
        val markup = TextMarkupElement(
            resourceId = "r", pdfPageIndex = 0, pdfWidthPt = 100f, pdfHeightPt = 100f, startIndex = 0, endIndex = 1,
            text = "a", rects = listOf(Rect(0f, 0f, 1f, 1f)), kind = TextMarkupKind.HIGHLIGHT, color = 0
        )
        listOf(stroke, text, image, markup, pdf).forEach(page::add)
        assertEquals(listOf(pdf, markup, image, text, stroke), page.snapshotContent().paintOrder)
    }

    @Test
    fun theTrianglesOfAMeshTurnTheSameWay() {
        // The second triangle is given clockwise, the third one is flat
        val mesh = StrokeMesh(
            positions = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f, 20f, 20f),
            indices = shortArrayOf(0, 1, 2, 1, 2, 3, 0, 3, 4),
            color = Color.Black.toArgb(),
            tool = StrokeTool.PRESSURE_PEN,
            size = 1f
        )
        val outline = mesh.triangleOutline()
        assertEquals(2, outline.polygons.size)
        for (p in outline.polygons) {
            val cross = (p[2] - p[0]) * (p[5] - p[1]) - (p[3] - p[1]) * (p[4] - p[0])
            assertTrue(cross > 0f)
        }
    }

    @Test
    fun thePolygonsOfAnOutlineTurnTheSameWay() {
        val clockwise = floatArrayOf(0f, 0f, 0f, 10f, 10f, 10f, 10f, 0f)
        val counterClockwise = floatArrayOf(20f, 0f, 30f, 0f, 30f, 10f, 20f, 10f)
        val outline = StrokeOutline(listOf(clockwise, counterClockwise))
        assertContentEquals(counterClockwise, outline.polygons[1])
        assertContentEquals(floatArrayOf(10f, 0f, 10f, 10f, 0f, 10f, 0f, 0f), outline.polygons[0])
        // Mirrored, they turn the same way again
        val mirrored = outline.transformed(Matrix2D(-1f, 0f, 0f, 1f, 0f, 0f))
        assertContentEquals(floatArrayOf(-20f, 10f, -30f, 10f, -30f, 0f, -20f, 0f), mirrored.polygons[1])
    }

    private fun triangle() = StrokeMesh(
        positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
        indices = shortArrayOf(0, 1, 2),
        color = Color.Black.toArgb(),
        tool = StrokeTool.PRESSURE_PEN,
        size = 1f
    )
}
