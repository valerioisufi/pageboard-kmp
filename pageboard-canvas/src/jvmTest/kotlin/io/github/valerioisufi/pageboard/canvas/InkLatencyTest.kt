package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.canvas.controller.InkLatencyMeter
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.model.DrawDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The latency of the ink measured by the editor (see `docs/plans/ink-latency.md`). */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class InkLatencyTest {

    @Test
    fun aFrameMeasuresTheInputsItDrawsFirst() {
        var now = 0L
        val meter = InkLatencyMeter { now }.apply { isEnabled = true }
        meter.strokeStarted()
        meter.inputAdded(100L)
        meter.inputAdded(104L)
        meter.inputAdded(108L)
        now = 116L
        meter.frameDrawn()
        // Drawn again with nothing new: not counted
        now = 132L
        meter.frameDrawn()
        meter.inputAdded(120L)
        meter.predicted(136L)
        now = 140L
        meter.frameDrawn()

        val report = assertNotNull(meter.strokeEnded())
        assertEquals(4, report.inputs)
        assertEquals(2, report.frames)
        // 16, 12, 8 then 20
        assertEquals(12f, report.inputToFrameMedianMs)
        assertEquals(16f, report.inputToFrameP95Ms)
        // The tips stood for 108 and 136
        assertEquals(4f, report.tipAgeMedianMs)
        assertEquals(0f, report.predictionMedianMs)
        assertNull(meter.strokeEnded(), "one report per stroke")
    }

    @Test
    fun nothingIsMeasuredWhileSwitchedOff() {
        val meter = InkLatencyMeter { 116L }
        meter.strokeStarted()
        meter.inputAdded(100L)
        meter.frameDrawn()
        assertNull(meter.strokeEnded())
    }

    @Test
    fun aStrokeDrawnOnTheCanvasIsReported() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = scope)
        controller.selectTool(Tool.INK_PEN)
        val scene = ImageComposeScene(width = 800, height = 1200, density = Density(1f)) { DrawCanvas(controller) }
        try {
            scene.render()
            assertNull(controller.inkLatency.value)
            controller.isInkLatencyMeasured = true
            // The pointer events of the desktop carry the wall-clock time of their AWT events
            var time = System.currentTimeMillis()
            scene.send(PointerEventType.Press, Offset(200f, 400f), time)
            for (i in 1..20) {
                time = System.currentTimeMillis()
                scene.send(PointerEventType.Move, Offset(200f + i * 8f, 400f), time)
                scene.render()
            }
            scene.send(PointerEventType.Release, Offset(360f, 400f), System.currentTimeMillis())

            val report = assertNotNull(controller.inkLatency.value)
            assertTrue(report.frames in 1..21, "frames ${report.frames}")
            assertTrue(report.inputs >= report.frames, "inputs ${report.inputs}")
            assertTrue(report.inputToFrameMedianMs in 0f..1_000f, "$report")
            controller.isInkLatencyMeasured = false
            assertNull(controller.inkLatency.value)
        } finally {
            scene.close()
            scope.cancel()
        }
    }

    private fun ImageComposeScene.send(type: PointerEventType, position: Offset, timeMillis: Long) {
        sendPointerEvent(
            eventType = type,
            pointers = listOf(ComposeScenePointer(PointerId(0), position, pressed = type != PointerEventType.Release, type = PointerType.Stylus)),
            timeMillis = timeMillis
        )
    }
}
