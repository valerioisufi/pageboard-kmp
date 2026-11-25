package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import io.github.valerioisufi.pageboard.ink.InkStrokeEngine
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.KotlinStrokeEngineDelegate
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.remodelingBrush
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ink drawn from the outline of its strokes, antialiased (see `docs/plans/ink-antialiasing.md`).
 */
class StrokeAntialiasingTest {

    /** A stroke modeled by Ink through [points] (page millimeters), drawn with a mouse. */
    private fun stroke(points: List<Pair<Float, Float>>, tool: StrokeTool, size: Float, color: Int): StrokeMesh {
        val inputs = points.mapIndexed { i, (x, y) -> StrokePoint(x, y, -1f, 1_000L + i * 8L) }
        return assertNotNull(InkStrokeEngine.buildMesh(inputs, remodelingBrush(tool, size, color)))
    }

    /** [mesh] drawn on white paper at 4 pixels per millimeter. */
    private fun draw(mesh: StrokeMesh, painter: StrokeMeshPainter = StrokeMeshPainter()): PixelMap {
        val bitmap = ImageBitmap(400, 400)
        val canvas = Canvas(bitmap)
        canvas.drawRect(Rect(0f, 0f, 400f, 400f), Paint().apply { color = Color.White })
        painter.draw(canvas, mesh, Matrix2D().postScale(4f, 4f))
        return bitmap.toPixelMap()
    }

    /** The pixels partly covered by the ink: neither the paper nor the ink. */
    private fun PixelMap.partlyCovered(): Int {
        var count = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (this[x, y].red in 0.08f..0.92f) count++
        }
        return count
    }

    /** Triangles modeled without outline by the Kotlin fallback modeller for comparison. */
    private fun kotlinTrianglesFor(points: List<Pair<Float, Float>>, tool: StrokeTool, size: Float, color: Int): StrokeMesh {
        val brush = remodelingBrush(tool, size, color)
        val delegate = KotlinStrokeEngineDelegate()
        delegate.start(brush, 0.1f)
        val inputs = points.mapIndexed { i, (x, y) -> StrokePoint(x, y, -1f, 1_000L + i * 8L) }
        inputs.forEach { delegate.addInput(it.x, it.y, it.pressure, it.timestampMs, InputToolType.MOUSE) }
        delegate.finishInputs()
        return assertNotNull(delegate.currentMesh(brush, inputs, InputToolType.MOUSE))
    }

    @Test
    fun theEdgesOfTheInkAreAntialiased() {
        // A slanted line: its edges cross the pixels
        val linePoints = (0..40).map { 10f + it * 2f to 10f + it * 1.3f }
        val line = stroke(linePoints, StrokeTool.PRESSURE_PEN, 0.6f, 0xFF000000.toInt())
        assertNotNull(line.outline)

        val outline = draw(line).partlyCovered()
        val triangles = draw(kotlinTrianglesFor(linePoints, StrokeTool.PRESSURE_PEN, 0.6f, 0xFF000000.toInt())).partlyCovered()
        assertTrue(outline > 100, "$outline pixels partly covered by the outline")
        assertTrue(triangles < outline / 10, "$triangles pixels partly covered by the triangles, $outline by the outline")
    }

    @Test
    fun aHighlighterIsPaintedOnceWhereItCrossesItself() {
        // A figure of eight crossing itself at (50, 50)
        val eight = (0..120).map { i ->
            val t = 0.15f + i / 120f * (2f * PI.toFloat() - 0.3f)
            50f + 25f * sin(2f * t) to 50f + 25f * sin(t)
        }
        val highlighter = stroke(eight, StrokeTool.HIGHLIGHTER, 6f, 0x80FF0000.toInt())
        assertNotNull(highlighter.outline)

        val pixels = draw(highlighter)
        // At the crossing as on the stroke alone (at t = π / 2: (50, 75))
        val alone = pixels[200, 300]
        val crossing = pixels[200, 200]
        assertTrue(alone.green < 0.9f, "the highlighter covers (50, 75): $alone")
        assertEquals(alone.green, crossing.green, 0.02f, "crossing $crossing, alone $alone")
    }

    @Test
    fun repeatedDrawsReuseCachedPathsAndEvictLruCleanly() {
        val painter = StrokeMeshPainter()
        val line = stroke((0..20).map { 10f + it * 3f to 20f + it * 1.5f }, StrokeTool.PRESSURE_PEN, 1.0f, 0xFF000000.toInt())
        val first = draw(line, painter).partlyCovered()
        val second = draw(line, painter).partlyCovered()
        assertEquals(first, second)

        // Draw >256 distinct meshes through the same painter to force LRU eviction and Path.close(),
        // then redraw the original mesh and confirm identical pixels.
        repeat(270) { idx ->
            val translated = line.transformed(io.github.valerioisufi.pageboard.model.geometry.AffineTransform(tx = (idx % 5) * 0.2f).toMatrix())
            draw(translated, painter)
        }
        val afterEviction = draw(line, painter).partlyCovered()
        assertEquals(first, afterEviction)
    }
}
