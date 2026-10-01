package io.github.valerioisufi.drawview.core

import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.core.engine.InkStrokeEngine
import io.github.valerioisufi.drawview.core.model.BrushConfig
import io.github.valerioisufi.drawview.core.model.InputToolType
import io.github.valerioisufi.drawview.core.model.StrokeTool
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosInkEngineTest {

    @Test
    fun googleInkIsLinkedThroughCinterop() {
        val engine = InkStrokeEngine()
        assertTrue(engine.isNative, "The iOS targets must use the Google Ink C++ engine")
        engine.startStroke(BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 0.8f, color = Color.Blue, epsilon = 0.01f))
        for (i in 0..30) {
            engine.addInput(10f + i * 1.5f, 20f + (i * i) * 0.05f, 0.4f + i * 0.01f, 1000L + i * 8L, InputToolType.STYLUS)
        }
        val mesh = assertNotNull(engine.finishStroke())
        assertTrue(mesh.vertexCount > 0 && mesh.triangleCount > 0)
        assertTrue(mesh.points.size == 31)
        engine.release()
    }
}
