package io.github.valerioisufi.drawview.model

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinStrokeEngineTest {

    @Test
    fun strokeEndsOnTheLastInput() {
        val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 1f, color = 0xFF000000.toInt(), epsilon = 0.01f)
        val delegate = KotlinStrokeEngineDelegate()
        delegate.start(brush, 1f)
        // Few, far apart samples (e.g. a fast mouse drag): the smoothing lags behind them
        val samples = listOf(0f to 0f, 20f to 5f, 40f to 10f, 70f to 18f, 100f to 25f)
        samples.forEachIndexed { i, (x, y) -> delegate.addInput(x, y, -1f, i * 16L, InputToolType.MOUSE) }
        delegate.finishInputs()
        val mesh = assertNotNull(delegate.currentMesh(brush, emptyList(), InputToolType.MOUSE))
        val bounds = mesh.computeBounds()
        // The round cap of radius 0.5 mm surrounds the last input
        assertTrue(bounds.right >= 100f + 0.4f, "right = ${bounds.right}")
        assertTrue(bounds.bottom >= 25f, "bottom = ${bounds.bottom}")
    }
}
