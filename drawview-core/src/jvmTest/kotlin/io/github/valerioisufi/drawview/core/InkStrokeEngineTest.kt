package io.github.valerioisufi.drawview.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.core.engine.InkStrokeEngine
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.geometry.AffineTransform
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InkStrokeEngineTest {

    @Test
    fun testStrokeGenerationWithGoogleInk() {
        val engine = InkStrokeEngine()

        val brush = BrushConfig(
            tool = StrokeTool.PRESSURE_PEN,
            size = 14f,
            color = Color.Blue.toArgb(),
            epsilon = 0.1f
        )

        engine.startStroke(brush)

        var latestMesh = engine.addPoint(100f, 100f, 0.5f, 0L)
        // Add successive points along a curve
        for (i in 1..10) {
            val x = 100f + i * 15f
            val y = 100f + (i * i) * 0.8f
            val pressure = 0.4f + i * 0.05f
            val t = i * 16L
            latestMesh = engine.addPoint(x, y, pressure, t)
        }

        assertNotNull(latestMesh, "Active stroke mesh should be generated during drag")
        assertTrue(latestMesh.vertexCount > 0, "Mesh should have vertices, got: ${latestMesh.vertexCount}")
        assertTrue(latestMesh.triangleCount > 0, "Mesh should have triangles, got: ${latestMesh.triangleCount}")
        assertTrue(latestMesh.positions.size == latestMesh.vertexCount * 2)
        assertTrue(latestMesh.indices.size == latestMesh.triangleCount * 3)

        val completedStroke = engine.finishStroke()
        assertNotNull(completedStroke, "Completed stroke should not be null")
        assertTrue(completedStroke.vertexCount > 0, "Completed stroke should have vertices")
        assertTrue(completedStroke.triangleCount > 0, "Completed stroke should have triangles")
        assertTrue(completedStroke.color == Color.Blue.toArgb())

        println("Unit Test Verified: Google Ink generated ${completedStroke.vertexCount} vertices and ${completedStroke.triangleCount} triangles for stroke!")
        engine.release()
    }

    @Test
    fun theOutlineOfAStrokeFollowsItsMesh() {
        val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 2f, color = Color.Blue.toArgb(), epsilon = 0.05f)
        val points = (0..20).map { i -> StrokePoint(10f + i * 3f, 20f + (i * i) * 0.2f, 0.5f, 16L * (i + 1)) }
        val mesh = assertNotNull(InkStrokeEngine.buildMesh(points, brush))
        val outline = assertNotNull(InkStrokeEngine.buildOutline(points, brush), "Ink gives the outline of the stroke")

        // Fewer points than the triangles, around the same shape
        val vertices = outline.polygons.sumOf { it.size / 2 }
        assertTrue(vertices in 3 until mesh.triangleCount * 3, "outline of $vertices vertices for ${mesh.triangleCount} triangles")
        val meshBounds = mesh.computeBounds()
        val xs = outline.polygons.flatMap { p -> p.filterIndexed { i, _ -> i % 2 == 0 } }
        val ys = outline.polygons.flatMap { p -> p.filterIndexed { i, _ -> i % 2 == 1 } }
        assertTrue(abs(xs.min() - meshBounds.left) < 0.01f && abs(xs.max() - meshBounds.right) < 0.01f)
        assertTrue(abs(ys.min() - meshBounds.top) < 0.01f && abs(ys.max() - meshBounds.bottom) < 0.01f)

        // The outline of a stroke placed on its page is placed with it
        val stroke = DrawStroke(mesh = mesh.withPoints(points), brushConfig = brush, transform = AffineTransform(tx = 100f))
        val placed = InkStrokeEngine.outline(stroke)
        assertEquals(outline.polygons.size, placed.polygons.size)
        assertEquals(outline.polygons[0][0] + 100f, placed.polygons[0][0], 0.001f)
        assertEquals(outline.polygons[0][1], placed.polygons[0][1], 0.001f)
    }
}
