package io.github.valerioisufi.drawview.core

import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.core.engine.InkStrokeEngine
import io.github.valerioisufi.drawview.core.model.BrushConfig
import io.github.valerioisufi.drawview.core.model.StrokeTool
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InkStrokeEngineTest {

    @Test
    fun testStrokeGenerationWithGoogleInk() {
        val engine = InkStrokeEngine()

        val brush = BrushConfig(
            tool = StrokeTool.PRESSURE_PEN,
            size = 14f,
            color = Color.Blue,
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
        assertTrue(completedStroke.color == Color.Blue)

        println("Unit Test Verified: Google Ink generated ${completedStroke.vertexCount} vertices and ${completedStroke.triangleCount} triangles for stroke!")
        engine.release()
    }
}
