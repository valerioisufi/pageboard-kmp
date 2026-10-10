package io.github.valerioisufi.drawview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.core.engine.InkStrokeEngine
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.StrokeTool
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedLogicDesktopTest {

    @Test
    fun testAllBrushToolsGenerateValidMeshes() {
        val engine = InkStrokeEngine()

        val toolsToTest = listOf(
            StrokeTool.PRESSURE_PEN,
            StrokeTool.MARKER,
            StrokeTool.HIGHLIGHTER,
            StrokeTool.DASHED_LINE
        )

        for (tool in toolsToTest) {
            val brush = BrushConfig(
                tool = tool,
                size = 16f,
                color = Color.Red.toArgb(),
                epsilon = 0.1f
            )

            engine.startStroke(brush)

            // Feed 15 points
            for (i in 0..15) {
                val x = 50f + i * 20f
                val y = 50f + (i % 3) * 10f
                val pressure = 0.5f
                engine.addPoint(x, y, pressure, i * 16L)
            }

            val stroke = engine.finishStroke()
            assertNotNull(stroke, "Tool $tool should produce a completed stroke")
            assertTrue(
                stroke.vertexCount > 0,
                "Tool $tool should produce vertices, got: ${stroke.vertexCount}"
            )
            assertTrue(
                stroke.triangleCount > 0,
                "Tool $tool should produce triangles, got: ${stroke.triangleCount}"
            )
            assertTrue(
                stroke.positions.size == stroke.vertexCount * 2,
                "Positions size must be 2 * vertexCount"
            )
            assertTrue(
                stroke.indices.size == stroke.triangleCount * 3,
                "Indices size must be 3 * triangleCount"
            )

            println("Tool $tool PASSED: ${stroke.vertexCount} vertices, ${stroke.triangleCount} triangles")
        }

        engine.release()
    }
}