package io.github.valerioisufi.pageboard.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A finger on the text being written, the field drawn scaled by the zoom and turned with its text
 * (docs/plans/text-fidelity.md): the caret goes on the character tapped, a long press selects its word,
 * the handles are drawn where the text is and move the selection.
 */
@OptIn(ExperimentalTestApi::class)
class TextBoxTouchTest {

    private val text = "Una parola qui e un'altra parola là\nseconda riga del testo"

    private fun DesktopComposeUiTest.fieldLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** Where the canvas is in the window: the field of a box not turned is at the corner of its box. */
    private var canvasInRoot = Offset.Zero

    private fun DesktopComposeUiTest.findCanvas(controller: DrawViewController) {
        canvasInRoot = Offset.Zero
        canvasInRoot = fieldBounds().topLeft - toScreen(controller, Offset.Zero)
    }

    /** [point] of the layout of the field (20 px per mm) in the window: the place of the box, its rotation, the zoom. */
    private fun toScreen(controller: DrawViewController, point: Offset): Offset {
        val editor = controller.textEditorManager
        val page = editor.activeTextPageIndex
        val origin = checkNotNull(controller.viewport.pageMmToScreen(page)).mapPoint(editor.boxX, editor.boxY)
        val k = controller.viewport.pixelsPerMm(page) / PX_PER_MM
        val turned = Matrix2D().postRotate(editor.rotation).mapPoint(point.x * k, point.y * k)
        return canvasInRoot + Offset(origin.x + turned.x, origin.y + turned.y)
    }

    /** Where a finger taps the character [index] of the text (plain: shown as it is written), a little after its left edge. */
    private fun DesktopComposeUiTest.onCharacter(controller: DrawViewController, index: Int): Offset {
        val box = fieldLayout().getBoundingBox(index)
        return toScreen(controller, Offset(box.left + box.width / 4f, box.center.y))
    }

    /** The bottom of the caret at [offset] on the screen. */
    private fun DesktopComposeUiTest.caretBottom(controller: DrawViewController, offset: Int): Offset {
        val rect = fieldLayout().getCursorRect(offset)
        return toScreen(controller, Offset(rect.left, rect.bottom))
    }

    private fun DesktopComposeUiTest.bounds(tag: String): Rect = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun near(a: Float, b: Float, tolerance: Float = 3f) = abs(a - b) <= tolerance

    @Test
    fun aTapPutsTheCaretOnTheCharacterTappedAndItsHandleUnderIt() {
        for (factor in listOf(0.6f, 2f)) textBoxScene { controller ->
            startBox(controller, 30f, 60f, widthMm = 120f, factor = factor)
            type(text)
            findCanvas(controller)
            val index = text.indexOf("parola") + 3
            onRoot().performTouchInput { click(onCharacter(controller, index)) }
            waitForIdle()
            assertEquals(TextRange(index), controller.textEditorManager.value.selection, "the caret at a zoom of $factor")
            // The handle hangs from the bottom of the caret, centered under it
            val handle = bounds("text-handle-caret")
            val bottom = caretBottom(controller, index)
            assertTrue(near(handle.center.x, bottom.x), "the handle at ${handle.center.x}, the caret at ${bottom.x} (zoom $factor)")
            assertTrue(handle.top < bottom.y && handle.bottom > bottom.y + 10f, "the handle from ${handle.top} to ${handle.bottom}, the caret ending at ${bottom.y} (zoom $factor)")
        }
    }

    @Test
    fun aLongPressSelectsTheWordWithItsHandlesAtItsEnds() {
        for (factor in listOf(0.6f, 2f)) textBoxScene { controller ->
            startBox(controller, 30f, 60f, widthMm = 120f, factor = factor)
            type(text)
            findCanvas(controller)
            val word = text.indexOf("altra")
            onRoot().performTouchInput { longClick(onCharacter(controller, word + 2)) }
            waitForIdle()
            assertEquals(TextRange(word, word + "altra".length), controller.textEditorManager.value.selection, "the word at a zoom of $factor")
            // The start handle on the left of the start, the end one on the right of the end, hanging from them
            val start = caretBottom(controller, word)
            val end = caretBottom(controller, word + "altra".length)
            val startHandle = bounds("text-handle-start")
            val endHandle = bounds("text-handle-end")
            assertTrue(startHandle.center.x < start.x && near(startHandle.center.x, start.x - HANDLE_RADIUS), "the start handle at ${startHandle.center.x}, the start at ${start.x} (zoom $factor)")
            assertTrue(endHandle.center.x > end.x && near(endHandle.center.x, end.x + HANDLE_RADIUS), "the end handle at ${endHandle.center.x}, the end at ${end.x} (zoom $factor)")
            assertTrue(near(startHandle.center.y, start.y + HANDLE_RADIUS) && near(endHandle.center.y, end.y + HANDLE_RADIUS), "the handles under the line (zoom $factor)")
        }
    }

    @Test
    fun draggingTheEndHandleMovesTheEndOfTheSelection() = textBoxScene { controller ->
        startBox(controller, 30f, 60f, widthMm = 120f, factor = 1.5f)
        type(text)
        findCanvas(controller)
        val word = text.indexOf("parola")
        onRoot().performTouchInput { longClick(onCharacter(controller, word + 2)) }
        waitForIdle()
        val handle = bounds("text-handle-end").center
        val from = caretBottom(controller, word + "parola".length)
        val target = text.indexOf("altra") + 2
        val to = caretBottom(controller, target)
        onRoot().performTouchInput {
            down(handle)
            moveBy(Offset(20f, 0f))
            moveTo(handle + (to - from))
            up()
        }
        waitForIdle()
        assertEquals(TextRange(word, target), controller.textEditorManager.value.selection)
    }

    @Test
    fun aTapOnATurnedTextPutsTheCaretOnTheCharacterTapped() = textBoxScene { controller ->
        startBox(controller, 30f, 60f, widthMm = 120f, factor = 1.5f)
        type(text)
        findCanvas(controller)
        runOnIdle {
            controller.textEditorManager.finishEditing()
            val saved = controller.document.pages[0].texts.single()
            controller.selectionManager.selectText(0, saved)
            controller.selectionManager.beginTransformation()
            controller.selectionManager.rotateBy(-20f)
            controller.selectionManager.applyTransformation()
            controller.selectionManager.clearSelection()
            controller.textEditorManager.startEditing(controller.document.pages[0].texts.single(), 0)
        }
        waitForIdle()
        val index = text.indexOf("seconda") + 4
        onRoot().performTouchInput { click(onCharacter(controller, index)) }
        waitForIdle()
        assertEquals(TextRange(index), controller.textEditorManager.value.selection)
    }

    private companion object {
        const val PX_PER_MM = 20f

        /** Half the size of a handle, 22 dp at a density of 1. */
        const val HANDLE_RADIUS = 11f
    }
}
