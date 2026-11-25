package io.github.valerioisufi.pageboard.canvas

import kotlin.test.assertNull
import io.github.valerioisufi.pageboard.model.change.LockElements
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.canvas.render.tile.TextTileAdapter
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import io.github.valerioisufi.pageboard.canvas.PageCanvas
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.geometry.toCompose
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.change.AddElements
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
 * The gestures of the text tool sent to a [PageCanvas]: a box drawn with a mouse, a tap on another
 * text while one is written, the text being written dragged to another page.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TextGesturesTest {

    private fun withCanvas(block: (ImageComposeScene, PageboardController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = PageboardController(document = Document(), coroutineScope = scope)
        val scene = ImageComposeScene(width = 800, height = 1200, density = Density(1f)) {
            PageCanvas(controller)
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
        val topLeft = assertNotNull(controller.viewport.screenToPageMm(0, from))
        val bottomRight = assertNotNull(controller.viewport.screenToPageMm(0, to))
        assertEquals(topLeft.x, editor.boxX, 0.01f)
        assertEquals(topLeft.y, editor.boxY, 0.01f)
        assertEquals(bottomRight.x - topLeft.x, editor.boxWidth, 0.01f)
    }

    @Test
    fun aTapOnAnotherTextOpensItAtOnce() = withCanvas { scene, controller ->
        val page = controller.document.pages[0]
        val other = TextElement().apply { text = "Other"; x = 20f; y = 120f; width = 60f; height = 10f }
        controller.session.submit(AddElements.of(page, listOf(other)))
        val editor = controller.textEditorManager
        editor.startNewText(0, 20f, 40f)
        editor.updateValue(TextFieldValue("First", TextRange(5)))

        // A tap on the other text: the first one saved, the other one opened
        scene.click(assertNotNull(controller.viewport.pageMmToScreen(0)).mapPoint(40f, 125f).toCompose())
        assertEquals(listOf("Other", "First"), page.texts.map { it.text })
        assertSame(other, editor.activeTextItem)

        // A tap on an empty spot: saved, nothing opened
        scene.click(assertNotNull(controller.viewport.pageMmToScreen(0)).mapPoint(150f, 250f).toCompose())
        assertFalse(editor.isEditing)
    }

    @Test
    fun theTextBeingWrittenIsDraggedToTheNextPage() = withCanvas { scene, controller ->
        controller.pageManager.addNewPageAtBottom()
        // Both pages on screen
        controller.viewport.zoomBy(0.5f)
        scene.render()
        val first = controller.document.pages[0]
        val editor = controller.textEditorManager
        // Wide enough to be grabbed away from its handles
        editor.startDrawnText(0, Rect(40f, first.heightMm - 15f, 100f, first.heightMm - 7f))
        editor.updateValue(TextFieldValue("Moved", TextRange(5)))
        // As the field lays it out (no field in this scene)
        editor.reportLayoutHeight(8f)
        scene.render()

        // From the middle of the box, 120 px down (over the second page)
        val corner = assertNotNull(controller.viewport.pageMmToScreen(0)).mapPoint(editor.boxX, editor.boxY).toCompose()
        val from = corner + Offset(50f, 5f)
        scene.drag(from, from + Offset(0f, 120f))

        assertEquals(1, editor.activeTextPageIndex, "the box on the page under its center")
        val moved = assertNotNull(controller.viewport.pageMmToScreen(1)).mapPoint(editor.boxX, editor.boxY).toCompose()
        val pxPerMm = controller.viewport.pixelsPerMm(1)
        assertEquals(corner.x, moved.x, 5f * pxPerMm, "it follows the pointer, not shifted by the change of page")
        assertEquals(corner.y + 120f, moved.y, 5f * pxPerMm)

        editor.finishEditing()
        assertEquals("Moved", controller.document.pages[1].texts.single().text)
        assertTrue(first.texts.isEmpty())
    }

    /** Lays out a box of a checklist at the start of the first line of every text, [BOX_MM] wide. */
    private class FirstBoxAdapter : TextTileAdapter {
        override fun drawText(canvas: Canvas, text: TextElement, mmToCanvas: Matrix2D, paperColor: Int) = Unit

        override fun checkBoxAt(text: TextElement, paperColor: Int, localPointMm: Point): Int? =
            if (localPointMm.x in 0f..BOX_MM && localPointMm.y in 0f..BOX_MM) 0 else null
    }

    @Test
    fun aTapOnABoxOfTheChecklistChecksItWithoutOpeningTheText() = withCanvas { scene, controller ->
        controller.textAdapter = FirstBoxAdapter()
        val page = controller.document.pages[0]
        val list = TextElement().apply { text = "- [ ] milk"; x = 20f; y = 40f; width = 60f; height = 10f }
        controller.session.submit(AddElements.of(page, listOf(list)))
        val mmToScreen = assertNotNull(controller.viewport.pageMmToScreen(0))
        val box = mmToScreen.mapPoint(22f, 42f).toCompose()

        // The hand tool
        controller.selectTool(Tool.PAN)
        scene.click(box)
        assertEquals("- [x] milk", page.texts.single().text)
        assertFalse(controller.textEditorManager.isEditing)
        // A second tap right after is no double tap: the box back as it was, the text not opened
        scene.click(box)
        assertEquals("- [ ] milk", page.texts.single().text)
        assertFalse(controller.textEditorManager.isEditing)
        controller.undo()
        assertEquals("- [x] milk", page.texts.single().text, "each tap one step of undo")

        // The text tool: the box, not the text opened
        controller.selectTool(Tool.TEXT)
        time += 1_000
        scene.click(box)
        assertEquals("- [ ] milk", page.texts.single().text)
        assertFalse(controller.textEditorManager.isEditing)
        // Beside the box, the text tool opens the text as before
        time += 1_000
        scene.click(mmToScreen.mapPoint(50f, 45f).toCompose())
        assertTrue(controller.textEditorManager.isEditing)
    }

    @Test
    fun theBoxOfALockedTextStaysAsItIs() = withCanvas { scene, controller ->
        controller.textAdapter = FirstBoxAdapter()
        val page = controller.document.pages[0]
        val list = TextElement().apply { text = "- [ ] milk"; x = 20f; y = 40f; width = 60f; height = 10f }
        controller.session.submit(AddElements.of(page, listOf(list)))
        controller.session.submit(LockElements(page.id, listOf(list.id), true))
        controller.selectTool(Tool.PAN)
        scene.click(assertNotNull(controller.viewport.pageMmToScreen(0)).mapPoint(22f, 42f).toCompose())
        assertEquals("- [ ] milk", page.texts.single().text)
    }

    @Test
    fun theBottomHandleOfTheTextBeingWrittenSetsItsHeightAndATapClearsIt() = withCanvas { scene, controller ->
        val editor = controller.textEditorManager
        editor.startNewText(0, 20f, 40f)
        editor.updateValue(TextFieldValue("one\ntwo\nthree", TextRange(13)))
        // As the field lays it out (no field in this scene)
        editor.reportLayoutHeight(30f)
        scene.render()
        val handle = assertNotNull(SelectionFrame.ofEditedText(controller)?.heightHandle)
        scene.drag(handle, handle - Offset(0f, 20f))
        val height = assertNotNull(editor.maxHeight)
        assertTrue(height < editor.boxHeight, "the box stops above its last line: $height of ${editor.boxHeight}")
        // The handle now at the line where it stops: a tap on it clears the height
        scene.render()
        val moved = assertNotNull(SelectionFrame.ofEditedText(controller)?.heightHandle)
        time += 1_000
        scene.click(moved)
        assertNull(editor.maxHeight)
        assertTrue(editor.isEditing)
    }

    private companion object {
        const val BOX_MM = 5f
    }
}
