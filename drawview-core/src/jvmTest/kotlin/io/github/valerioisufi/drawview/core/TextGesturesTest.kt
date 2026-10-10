package io.github.valerioisufi.drawview.core

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import io.github.valerioisufi.drawview.core.canvas.DrawCanvas
import io.github.valerioisufi.drawview.core.change.AddElements
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.geometry.toCompose
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The gestures of the text tool sent to a [DrawCanvas]: a box drawn with a mouse, a tap on another
 * text while one is written.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TextGesturesTest {

    private fun withCanvas(block: (ImageComposeScene, DrawViewController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = scope)
        val scene = ImageComposeScene(width = 800, height = 1200, density = Density(1f)) {
            DrawCanvas(controller)
        }
        try {
            scene.render()
            controller.selectTool(Tool.TEXT)
            block(scene, controller)
        } finally {
            scene.close()
            scope.cancel()
        }
    }

    private var time = 0L

    private fun ImageComposeScene.mouse(type: PointerEventType, position: Offset, pressed: Boolean) {
        time += 16
        sendPointerEvent(
            eventType = type,
            position = position,
            timeMillis = time,
            type = PointerType.Mouse,
            buttons = PointerButtons(isPrimaryPressed = pressed),
            button = if (type == PointerEventType.Move) null else PointerButton.Primary
        )
    }

    private fun ImageComposeScene.drag(from: Offset, to: Offset) {
        mouse(PointerEventType.Press, from, pressed = true)
        for (step in 1..10) mouse(PointerEventType.Move, from + (to - from) * (step / 10f), pressed = true)
        mouse(PointerEventType.Release, to, pressed = false)
    }

    private fun ImageComposeScene.click(at: Offset) {
        mouse(PointerEventType.Press, at, pressed = true)
        mouse(PointerEventType.Release, at, pressed = false)
    }

    @Test
    fun aMouseDrawsTheBoxOfANewText() = withCanvas { scene, controller ->
        val from = Offset(200f, 300f)
        val to = Offset(500f, 380f)
        scene.drag(from, to)

        val editor = controller.textEditorManager
        assertTrue(editor.isEditing)
        assertFalse(editor.autoWidth, "the width drawn stays")
        val topLeft = assertNotNull(controller.screenToPageMm(0, from))
        val bottomRight = assertNotNull(controller.screenToPageMm(0, to))
        assertEquals(topLeft.x, editor.boxX, 0.01f)
        assertEquals(topLeft.y, editor.boxY, 0.01f)
        assertEquals(bottomRight.x - topLeft.x, editor.boxWidth, 0.01f)
    }

    @Test
    fun aTapOnAnotherTextOpensItAtOnce() = withCanvas { scene, controller ->
        val page = controller.document.pages[0]
        val other = DrawText().apply { text = "Other"; x = 20f; y = 120f; width = 60f; height = 10f }
        controller.session.submit(AddElements.of(page, listOf(other)))
        val editor = controller.textEditorManager
        editor.startNewText(0, 20f, 40f)
        editor.updateValue(TextFieldValue("First", TextRange(5)))

        // A tap on the other text: the first one saved, the other one opened
        scene.click(assertNotNull(controller.pageMmToScreen(0)).mapPoint(40f, 125f).toCompose())
        assertEquals(listOf("Other", "First"), page.texts.map { it.text })
        assertSame(other, editor.activeTextItem)

        // A tap on an empty spot: saved, nothing opened
        scene.click(assertNotNull(controller.pageMmToScreen(0)).mapPoint(150f, 250f).toCompose())
        assertFalse(editor.isEditing)
    }
}
