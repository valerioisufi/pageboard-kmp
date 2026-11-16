package io.github.valerioisufi.drawview.ink

import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stroke in progress predicted beyond its last input (see `docs/plans/ink-latency.md`): the prediction
 * is drawn, replaced by the next inputs, and never kept.
 */
class PredictionTest {
    private val points = List(40) { i -> StrokePoint(10f + i * 0.5f, 20f + sin(i * 0.3f), 0.5f, i * 4L + 1L) }
    private val prediction = List(4) { i -> StrokePoint(30f + (i + 1) * 0.5f, 20f, 0.5f, 160L + (i + 1) * 4L) }

    @Test
    fun thePredictionExtendsTheStrokeInProgress() {
        // The dashed line may end its prediction in a gap
        for (tool in listOf(StrokeTool.PRESSURE_PEN, StrokeTool.MARKER, StrokeTool.HIGHLIGHTER)) {
            val engine = engineWith(tool)
            if (!engine.isNative) return
            val before = assertNotNull(engine.currentMesh()).computeBounds()
            engine.predict(prediction)
            val predicted = assertNotNull(engine.currentMesh()).computeBounds()
            assertTrue(predicted.right > before.right + 1f, "$tool: the tail drawn, ${before.right} -> ${predicted.right}")
            assertEquals(points.size, engine.inputs.size, "$tool: the prediction is no input")
            // Taken away by an empty prediction (Ink may model the last real inputs a little differently)
            engine.predict(emptyList())
            assertEquals(before.right, assertNotNull(engine.currentMesh()).computeBounds().right, 0.1f, tool.name)
            engine.release()
        }
    }

    @Test
    fun theNextInputReplacesThePrediction() {
        val engine = engineWith(StrokeTool.MARKER)
        if (!engine.isNative) return
        engine.predict(List(4) { i -> StrokePoint(30f + (i + 1) * 3f, 20f, 0.5f, 160L + (i + 1) * 4L) })
        engine.addInput(30.2f, 20f, 0.5f, 164L, InputToolType.STYLUS)
        val bounds = assertNotNull(engine.currentMesh()).computeBounds()
        assertTrue(bounds.right < 32f, "the far prediction gone, ${bounds.right}")
        engine.release()
    }

    @Test
    fun theStrokeFinishedHasNoPrediction() {
        for (tool in StrokeTool.entries) {
            val plain = engineWith(tool)
            if (!plain.isNative) return
            val expected = assertNotNull(plain.finishStroke())
            val predicted = engineWith(tool)
            predicted.predict(prediction)
            val finished = assertNotNull(predicted.finishStroke())
            assertSameMesh(expected, finished, tool.name)
            assertEquals(points, finished.points, tool.name)
            plain.release()
            predicted.release()
        }
    }

    @Test
    fun theRegionUpdatedCoversWhatChanged() {
        val engine = engineWith(StrokeTool.MARKER)
        if (!engine.isNative) return
        assertNotNull(engine.takeUpdatedRegion(), "the stroke so far")
        assertNull(engine.takeUpdatedRegion(), "nothing changed since")

        val far = List(4) { i -> StrokePoint(30f + (i + 1) * 3f, 20f, 0.5f, 160L + (i + 1) * 4L) }
        engine.predict(far)
        val predicted = assertNotNull(engine.takeUpdatedRegion())
        assertTrue(predicted.right > 40f, "the tail, up to ${predicted.right}")
        assertTrue(predicted.left > 15f, "not the whole stroke, from ${predicted.left}")

        // The tail taken away is part of what changed
        engine.addInput(30.2f, 20f, 0.5f, 164L, InputToolType.STYLUS)
        val replaced = assertNotNull(engine.takeUpdatedRegion())
        assertTrue(replaced.right > 40f, "up to ${replaced.right}")
        engine.release()
    }

    private fun engineWith(tool: StrokeTool): InkStrokeEngine = InkStrokeEngine().apply {
        startStroke(BrushConfig(tool = tool, size = if (tool == StrokeTool.HIGHLIGHTER) 4f else 0.6f))
        for (p in points) addInput(p.x, p.y, p.pressure, p.timestampMs, InputToolType.STYLUS)
    }

    /**
     * The same stroke: Ink models the last real inputs of a stroke that was predicted a little
     * differently (up to a few hundredths of a millimeter, and their triangles, for the pressure pen), so
     * the bounds match, without any tail beyond them.
     */
    private fun assertSameMesh(expected: StrokeMesh, actual: StrokeMesh, label: String) {
        val e = expected.computeBounds()
        val a = actual.computeBounds()
        for ((side, gap) in listOf("left" to e.left - a.left, "top" to e.top - a.top, "right" to e.right - a.right, "bottom" to e.bottom - a.bottom)) {
            assertTrue(abs(gap) <= 0.05f, "$label: the $side side moved by $gap mm")
        }
    }
}
