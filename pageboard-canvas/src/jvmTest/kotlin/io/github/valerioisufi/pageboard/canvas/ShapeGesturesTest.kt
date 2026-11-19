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
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.StrokeElement
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A stroke held still at its end becomes its shape, sent to a [PageCanvas] as pointer events: a
 * stroke lifted at once, or looking like no shape, stays as drawn.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ShapeGesturesTest {

    @Test
    fun aLineHeldStillBecomesAStraightLine() = withCanvas { scene, controller ->
        val time = scene.draw(wavyLine(), holdMs = 600L)
        assertEquals(0, strokes(controller).size, "nothing added while the pen is down")
        scene.release(wavyLine().last(), time)

        val stroke = strokes(controller).single()
        val ys = stroke.baseMesh.points.map { it.y }
        assertTrue(ys.max() - ys.min() < 0.001f, "a level line, y from ${ys.min()} to ${ys.max()}")

        // Undone in one step
        controller.undo()
        assertEquals(0, strokes(controller).size)
    }

    @Test
    fun aLineHeldStillWithoutEventsBecomesAStraightLine() = withCanvas { scene, controller ->
        // A mouse held down sends nothing: the timer of the gesture fires
        val time = scene.draw(wavyLine(), holdMs = 0L)
        Thread.sleep(900L)
        scene.render()
        scene.release(wavyLine().last(), time)
        val ys = strokes(controller).single().baseMesh.points.map { it.y }
        assertTrue(ys.max() - ys.min() < 0.001f, "a level line, y from ${ys.min()} to ${ys.max()}")
    }

    @Test
    fun aStrokeLiftedAtOnceStaysAsDrawn() = withCanvas { scene, controller ->
        val time = scene.draw(wavyLine(), holdMs = 0L)
        scene.release(wavyLine().last(), time)
        val ys = strokes(controller).single().baseMesh.points.map { it.y }
        assertTrue(ys.max() - ys.min() > 1f, "the wave drawn")
    }

    @Test
    fun aScribbleHeldStillStaysAsDrawn() = withCanvas { scene, controller ->
        val scribble = listOf(200f to 300f, 260f to 380f, 240f to 290f, 330f to 400f, 300f to 280f, 390f to 390f, 350f to 300f, 420f to 360f)
            .map { (x, y) -> Offset(x, y) }
        val path = scribble.zipWithNext().flatMap { (a, b) -> (0 until 10).map { a + (b - a) * (it / 10f) } } + scribble.last()
        val time = scene.draw(path, holdMs = 600L)
        scene.release(path.last(), time)
        val stroke = strokes(controller).single()
        assertTrue(stroke.baseMesh.points.size >= path.size, "the inputs drawn, ${stroke.baseMesh.points.size}")
    }

    @Test
    fun noShapeWhenTheUserSwitchedThemOff() = withCanvas { scene, controller ->
        controller.shapeSnapper.updateEnabled(false)
        assertEquals(false, controller.preferences.value.isShapeRecognitionEnabled)
        val time = scene.draw(wavyLine(), holdMs = 600L)
        scene.release(wavyLine().last(), time)
        val ys = strokes(controller).single().baseMesh.points.map { it.y }
        assertTrue(ys.max() - ys.min() > 1f, "the wave drawn")
    }

    @Test
    fun aLineThatSnappedFollowsThePenStillDown() = withCanvas { scene, controller ->
        var time = scene.draw(wavyLine(), holdMs = 600L)
        // From where it was held (560, 400) up and further: the line turns and grows from its start
        val end = Offset(560f, 200f)
        for (i in 1..20) {
            time += 16L
            scene.send(PointerEventType.Move, wavyLine().last() + (end - wavyLine().last()) * (i / 20f), time)
        }
        scene.release(end, time)
        val points = strokes(controller).single().baseMesh.points
        val first = points.minBy { it.x }
        val last = points.minBy { it.y }
        assertTrue(first.y - last.y > 20f, "the line turned up, from (${first.x}, ${first.y}) to (${last.x}, ${last.y})")
        // Still a straight line: every point on the segment from its start to its end
        val dx = last.x - first.x
        val dy = last.y - first.y
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        val off = points.maxOf { kotlin.math.abs((it.x - first.x) * dy - (it.y - first.y) * dx) / length }
        assertTrue(off < 0.5f, "a straight line, ${off} mm off it")
        controller.undo()
        assertEquals(0, strokes(controller).size, "undone in one step")
    }

    /** A line across the page drawn by a hand that wavers a little. */
    private fun wavyLine(): List<Offset> = (0..60).map { i -> Offset(200f + i * 6f, 400f + 2f * sin(i / 6f)) }

    /**
     * Presses at the start of [path], moves along it a point every 16 ms, then stays at its end for
     * [holdMs] sending events that waver by a pixel, as a pen held still does. The time reached.
     */
    private fun ImageComposeScene.draw(path: List<Offset>, holdMs: Long): Long {
        var time = 1_000L
        send(PointerEventType.Press, path.first(), time)
        for (point in path.drop(1)) {
            time += 16L
            send(PointerEventType.Move, point, time)
        }
        val end = path.last()
        var held = 0L
        while (held < holdMs) {
            held += 16L
            time += 16L
            send(PointerEventType.Move, end + Offset(if (held % 32L == 0L) 1f else 0f, 0f), time)
        }
        return time
    }

    private fun ImageComposeScene.release(at: Offset, time: Long) {
        send(PointerEventType.Release, at, time + 16L)
        render()
    }

    private fun ImageComposeScene.send(type: PointerEventType, position: Offset, timeMillis: Long) {
        sendPointerEvent(
            eventType = type,
            pointers = listOf(ComposeScenePointer(PointerId(0), position, pressed = type != PointerEventType.Release, type = PointerType.Touch)),
            timeMillis = timeMillis
        )
    }

    private fun strokes(controller: PageboardController): List<StrokeElement> = controller.document.pages.flatMap { it.strokes }

    private fun withCanvas(block: (ImageComposeScene, PageboardController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = PageboardController(document = Document(), coroutineScope = scope)
        controller.selectTool(Tool.INK_PEN)
        val scene = ImageComposeScene(width = 800, height = 1200, density = Density(1f)) {
            PageCanvas(controller)
        }
        try {
            scene.render()
            block(scene, controller)
        } finally {
            scene.close()
            scope.cancel()
        }
    }
}
