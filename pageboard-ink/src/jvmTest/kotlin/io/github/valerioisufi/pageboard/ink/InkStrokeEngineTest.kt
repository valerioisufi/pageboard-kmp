package io.github.valerioisufi.pageboard.ink

import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.geometry.AffineTransform
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InkStrokeEngineTest {

    @Test
    fun testStrokeGenerationWithGoogleInk() {
        val engine = InkStrokeEngine()

        val brush = BrushConfig(
            tool = StrokeTool.PRESSURE_PEN,
            size = 14f,
            color = BLUE,
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
        assertFalse(latestMesh.isEmpty, "Active stroke mesh should not be empty")
        val latestOutline = assertNotNull(latestMesh.outline, "Native mesh carries stroke outline")
        assertTrue(latestOutline.vertexCount >= 3, "Outline should have vertices, got: ${latestOutline.vertexCount}")
        assertTrue(latestMesh.positions.isEmpty(), "Outline-only mesh skips duplicate triangle positions")
        assertTrue(latestMesh.indices.isEmpty(), "Outline-only mesh skips duplicate triangle indices")

        val completedStroke = engine.finishStroke()
        assertNotNull(completedStroke, "Completed stroke should not be null")
        assertFalse(completedStroke.isEmpty, "Completed stroke should not be empty")
        assertNotNull(completedStroke.outline, "Completed stroke should carry outline")
        assertTrue(completedStroke.positions.isEmpty())
        assertTrue(completedStroke.color == BLUE)

        println("Unit Test Verified: Google Ink generated ${completedStroke.outline?.vertexCount} outline vertices for stroke!")
        engine.release()
    }

    @Test
    fun theMeshCarriesTheOutlineOfTheStroke() {
        val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 2f, color = BLUE, epsilon = 0.05f)
        val points = (0..20).map { i -> StrokePoint(10f + i * 3f, 20f + (i * i) * 0.2f, 0.5f, 16L * (i + 1)) }
        val mesh = assertNotNull(InkStrokeEngine.buildMesh(points, brush))
        val outline = assertNotNull(mesh.outline, "Ink gives the outline of the stroke with its mesh")

        // Outline carries vertices and bounds without duplicate triangle arrays
        val vertices = outline.vertexCount
        assertTrue(vertices >= 3, "outline of $vertices vertices")
        assertTrue(mesh.positions.isEmpty() && mesh.indices.isEmpty())
        val meshBounds = mesh.computeBounds()
        val xs = outline.polygons.flatMap { p -> p.filterIndexed { i, _ -> i % 2 == 0 } }
        val ys = outline.polygons.flatMap { p -> p.filterIndexed { i, _ -> i % 2 == 1 } }
        assertTrue(abs(xs.min() - meshBounds.left) < 0.01f && abs(xs.max() - meshBounds.right) < 0.01f)
        assertTrue(abs(ys.min() - meshBounds.top) < 0.01f && abs(ys.max() - meshBounds.bottom) < 0.01f)

        // The stroke being drawn has it too
        val engine = InkStrokeEngine()
        engine.startStroke(brush)
        points.take(10).forEach { engine.addInput(it.x, it.y, it.pressure, it.timestampMs) }
        assertNotNull(engine.currentMesh()?.outline, "outline of the stroke in progress")
        engine.release()

        // Kept with the inputs, placed on a page with the vertices
        assertSame(outline, mesh.withPoints(emptyList()).outline)
        val placed = assertNotNull(mesh.transformed(AffineTransform(tx = 100f).toMatrix()).outline)
        assertEquals(outline.polygons.size, placed.polygons.size)
        assertEquals(outline.polygons[0][0] + 100f, placed.polygons[0][0], 0.001f)
        assertEquals(outline.polygons[0][1], placed.polygons[0][1], 0.001f)
    }
}

private const val BLUE = 0xFF0000FF.toInt()
