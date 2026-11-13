package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.shape.ShapeElement
import io.github.valerioisufi.drawview.model.shape.ShapeElementType
import io.github.valerioisufi.drawview.model.shape.ShapeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The shapes in the editor, sent to a [DrawCanvas] as pointer events. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ShapeToolTest {

    @Test
    fun theEraserErasesAShapeByItsOutline() = withCanvas { scene, controller ->
        controller.customElementManager.add(ShapeElementType, ShapeElement(ShapeKind.RECTANGLE, BLACK, 0.5f), 0, Rect(40f, 40f, 120f, 100f), select = false)
        controller.selectTool(Tool.ERASER)
        scene.render()
        // Across the inside of the box, without touching its sides: nothing erased
        scene.drag(controller.screen(60f, 70f), controller.screen(100f, 70f))
        assertEquals(1, shapes(controller).size)
        // Across its left side: erased, undone in one step
        scene.drag(controller.screen(30f, 70f), controller.screen(50f, 70f))
        assertEquals(0, shapes(controller).size)
        controller.undo()
        assertEquals(1, shapes(controller).size)
    }

    private fun DrawViewController.screen(xMm: Float, yMm: Float): Offset =
        viewport.pageMmToScreen(0)!!.mapPoint(xMm, yMm).let { Offset(it.x, it.y) }

    private fun ImageComposeScene.drag(from: Offset, to: Offset) {
        var time = nextTime
        send(PointerEventType.Press, from, time)
        for (i in 1..10) {
            time += 16L
            send(PointerEventType.Move, from + (to - from) * (i / 10f), time)
        }
        send(PointerEventType.Release, to, time + 16L)
        nextTime = time + 1_000L
        render()
    }

    private var nextTime = 1_000L

    private fun ImageComposeScene.send(type: PointerEventType, position: Offset, timeMillis: Long) {
        sendPointerEvent(
            eventType = type,
            pointers = listOf(ComposeScenePointer(PointerId(0), position, pressed = type != PointerEventType.Release, type = PointerType.Touch)),
            timeMillis = timeMillis
        )
    }

    private fun shapes(controller: DrawViewController): List<DrawCustomElement> =
        controller.document.pages.flatMap { it.customElements }.filter { it.type == ShapeElementType.id }

    private fun withCanvas(block: (ImageComposeScene, DrawViewController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = scope)
        val scene = ImageComposeScene(width = 800, height = 1200, density = Density(1f)) {
            DrawCanvas(controller)
        }
        try {
            scene.render()
            block(scene, controller)
        } finally {
            scene.close()
            scope.cancel()
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
    }
}
