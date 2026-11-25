package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.unit.Density
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.geometry.mapOffset
import io.github.valerioisufi.pageboard.ink.InkStrokeModeller
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.LockElements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class LayerGesturesTest {

    private var time = 100L

    private fun ImageComposeScene.longPressAt(pos: Offset) {
        time += 50L
        sendPointerEvent(
            eventType = PointerEventType.Press,
            pointers = listOf(ComposeScenePointer(id = PointerId(1L), position = pos, pressed = true, type = PointerType.Touch)),
            timeMillis = time
        )
        render()
        Thread.sleep(650L)
        time += 700L
        render()
        sendPointerEvent(
            eventType = PointerEventType.Release,
            pointers = listOf(ComposeScenePointer(id = PointerId(1L), position = pos, pressed = false, type = PointerType.Touch)),
            timeMillis = time
        )
        render()
    }

    private fun image(x: Float, y: Float, w: Float = 50f, h: Float = 50f) = ImageElement().apply {
        this.x = x; this.y = y; width = w; height = h
    }

    @Test
    fun repeatedLongPressCyclesDownStackAndWraps() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = PageboardController(document = Document(strokeModeller = InkStrokeModeller), coroutineScope = scope)
        val page = controller.document.pages.single()
        val bottom = image(20f, 20f)
        val middle = image(20f, 20f)
        val top = image(20f, 20f)
        controller.session.submit(AddElements.of(page, listOf(bottom, middle, top)))

        val scene = ImageComposeScene(width = 800, height = 1200, density = Density(1f)) {
            PageCanvas(controller)
        }
        try {
            scene.render()
            val pos = controller.viewport.pageMmToScreen(0)!!.mapOffset(Offset(45f, 45f))

            scene.longPressAt(pos)
            assertEquals(listOf(top.id), controller.selectionManager.currentSelection?.elements()?.map { it.id })

            scene.longPressAt(pos)
            assertEquals(listOf(middle.id), controller.selectionManager.currentSelection?.elements()?.map { it.id })

            scene.longPressAt(pos)
            assertEquals(listOf(bottom.id), controller.selectionManager.currentSelection?.elements()?.map { it.id })

            // Wraps back to topmost
            scene.longPressAt(pos)
            assertEquals(listOf(top.id), controller.selectionManager.currentSelection?.elements()?.map { it.id })
        } finally {
            scene.close()
            scope.cancel()
        }
    }

    @Test
    fun secondaryClickOnLockedElementExposesUnlockTarget() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = PageboardController(document = Document(strokeModeller = InkStrokeModeller), coroutineScope = scope)
        val page = controller.document.pages.single()
        val lockedBg = image(20f, 20f)
        controller.session.submit(AddElements.of(page, listOf(lockedBg)))
        controller.session.submit(LockElements(page.id, listOf(lockedBg.id), true))

        val scene = ImageComposeScene(width = 800, height = 1200, density = Density(1f)) {
            PageCanvas(controller)
        }
        try {
            scene.render()
            val pos = controller.viewport.pageMmToScreen(0)!!.mapOffset(Offset(45f, 45f))
            time += 50L
            scene.sendPointerEvent(
                eventType = PointerEventType.Press,
                pointers = listOf(ComposeScenePointer(id = PointerId(2L), position = pos, pressed = true, type = PointerType.Mouse)),
                buttons = PointerButtons(isSecondaryPressed = true),
                button = PointerButton.Secondary,
                timeMillis = time
            )
            scene.render()

            assertNull(controller.selectionManager.currentSelection)
            assertNotNull(controller.contextMenuPosition)
            val target = assertNotNull(controller.lockedElementForMenu)
            assertSame(lockedBg, target.second)
        } finally {
            scene.close()
            scope.cancel()
        }
    }
}
