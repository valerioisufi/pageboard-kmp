package io.github.valerioisufi.pageboard.ink

import io.github.valerioisufi.pageboard.ink.InkStrokeEngine
import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.StrokeTool
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosInkEngineTest {

    @Test
    fun googleInkIsLinkedThroughCinterop() {
        val engine = InkStrokeEngine()
        assertTrue(engine.isNative, "The iOS targets must use the Google Ink C++ engine")
        engine.startStroke(BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 0.8f, color = 0xFF0000FF.toInt(), epsilon = 0.01f))
        for (i in 0..30) {
            engine.addInput(10f + i * 1.5f, 20f + (i * i) * 0.05f, 0.4f + i * 0.01f, 1000L + i * 8L, InputToolType.STYLUS)
        }
        val mesh = assertNotNull(engine.finishStroke())
        assertFalse(mesh.isEmpty)
        assertTrue(mesh.positions.isEmpty())
        val outline = assertNotNull(mesh.outline)
        assertTrue(outline.vertexCount > 0)
        assertTrue(mesh.points.size == 31)
        engine.release()
    }
}
