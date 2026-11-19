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
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.ink.InkStrokeEngine
import io.github.valerioisufi.pageboard.model.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The stroke in progress drawn a frame ahead of the pen (see `docs/plans/ink-latency.md`): the prediction
 * is shown while the pen moves, and only the inputs of the pen are committed and sent.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class InkPredictionGestureTest {

    @Test
    fun theStrokeInProgressReachesBeyondThePen() = withCanvas { scene, controller, engine ->
        var time = drawTo(scene, steps = 30)
        val lastInput = engine.inputs.last()
        val shape = assertNotNull(engine.currentMesh()).computeBounds()
        assertTrue(shape.right > lastInput.x + 1f, "the tip at ${shape.right} mm, the pen at ${lastInput.x} mm")

        // Sent to the others: the inputs of the pen only
        val sent = assertNotNull(controller.presence.activity.value).ink
        assertTrue(sent.all { it.x <= lastInput.x + 0.001f }, "nothing predicted is sent")

        time += 8L
        scene.send(PointerEventType.Release, at(30), time)
        val stroke = controller.document.pages.flatMap { it.strokes }.single()
        assertEquals(0, engine.inputs.size, "the engine is done")
        assertTrue(stroke.baseMesh.points.all { it.x <= lastInput.x + 0.001f }, "only the inputs of the pen are committed")
        val committed = stroke.baseMesh.computeBounds()
        assertTrue(committed.right < shape.right - 0.5f, "the tail predicted is gone: ${committed.right} against ${shape.right}")
    }

    @Test
    fun theLatencyMeasuredCountsThePrediction() = withCanvas { scene, controller, _ ->
        controller.isInkLatencyMeasured = true
        // The clock of the pointer events on the desktop
        val start = System.currentTimeMillis()
        var time = start
        scene.send(PointerEventType.Press, at(0), time)
        for (i in 1..30) {
            time = start + i * 8L
            scene.send(PointerEventType.Move, at(i), time)
            scene.render()
        }
        scene.send(PointerEventType.Release, at(30), time + 8L)
        val report = assertNotNull(controller.inkLatency.value)
        assertTrue(report.predictionMedianMs > 0f, "$report")
    }

    /** Presses and draws a fast line, an event every 8 ms; the time reached. */
    private fun drawTo(scene: ImageComposeScene, steps: Int): Long {
        var time = 1_000L
        scene.send(PointerEventType.Press, at(0), time)
        for (i in 1..steps) {
            time += 8L
            scene.send(PointerEventType.Move, at(i), time)
        }
        return time
    }

    private fun at(i: Int) = Offset(200f + i * 12f, 400f)

    private fun ImageComposeScene.send(type: PointerEventType, position: Offset, timeMillis: Long) {
        sendPointerEvent(
            eventType = type,
            pointers = listOf(ComposeScenePointer(PointerId(0), position, pressed = type != PointerEventType.Release, type = PointerType.Mouse)),
            timeMillis = timeMillis
        )
    }

    private fun withCanvas(block: (ImageComposeScene, PageboardController, InkStrokeEngine) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = PageboardController(document = Document(), coroutineScope = scope)
        controller.selectTool(Tool.INK_PEN)
        val engine = InkStrokeEngine()
        val scene = ImageComposeScene(width = 800, height = 1200, density = Density(1f)) { PageCanvas(controller, engine = engine) }
        try {
            scene.render()
            block(scene, controller, engine)
        } finally {
            scene.close()
            scope.cancel()
        }
    }
}
