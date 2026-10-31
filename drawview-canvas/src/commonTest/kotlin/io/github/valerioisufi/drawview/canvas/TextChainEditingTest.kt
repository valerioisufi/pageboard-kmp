package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.TextEditorManager
import io.github.valerioisufi.drawview.canvas.render.tile.TextBlockSize
import io.github.valerioisufi.drawview.canvas.render.tile.TextLineMetrics
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.text.TextChains
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** Writing a text across the pages: the boxes of its chain, the text flowing through them, the caret following it. */
class TextChainEditingTest {

    /** Every line of the source 10 mm high (a line never wraps). */
    private class LinesAdapter : TextTileAdapter {
        override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, paperColor: Int) {}
        override fun measure(text: DrawText): TextBlockSize = TextBlockSize(text.width, text.text.split('\n').size * 10f)
        override fun lineMetrics(fontSize: Float) = TextLineMetrics(10f, 8f)
    }

    private fun controller(scope: CoroutineScope): DrawViewController {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = scope)
        controller.viewport.setSize(800f, 1200f)
        controller.tileManager.tileRenderer.textAdapter = LinesAdapter()
        return controller
    }

    private fun TextEditorManager.type(text: String) = updateValue(TextFieldValue(text, TextRange(text.length)))

    private fun lines(from: Int, to: Int) = (from..to).joinToString("\n") { "line $it" }

    /** A box 100 mm wide starting 245 mm down the first page (297 mm high, 5 mm of margin): room for 4 lines. */
    private fun DrawViewController.startLow() = textEditorManager.startDrawnText(0, Rect(20f, 245f, 120f, 255f))

    @Test
    fun aTextGoingBelowItsPageGoesOnIntoABoxOnANewPage() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startLow()
        editor.type(lines(1, 4))
        assertEquals(1, controller.document.pages.size, "four lines fit")
        editor.type(lines(1, 5))
        // The fifth line went on: a page added, the caret in its box
        assertEquals(2, controller.document.pages.size)
        val first = controller.document.pages[0].texts.single()
        val second = controller.document.pages[1].texts.single()
        assertEquals(lines(1, 4), first.text)
        assertEquals(second.id, first.next)
        assertEquals(1, editor.activeTextPageIndex)
        assertEquals("line 5", editor.value.text)
        assertEquals(TextRange(6), editor.value.selection)
        assertEquals(first.x, second.x)
        assertTrue(second.y < 20f, "at the top of the new page")
        editor.type("line 5\nline 6")
        editor.finishEditing()
        assertEquals("line 5\nline 6", controller.document.pages[1].texts.single().text)
    }

    @Test
    fun linesTakenAwayPullTheNextOnesBack() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startLow()
        editor.type(lines(1, 6))
        editor.finishEditing()
        val first = controller.document.pages[0].texts.single()
        editor.startEditing(first, 0)
        editor.type(lines(1, 2))
        editor.finishEditing()
        // The two lines of the second box came back, the second box removed
        assertEquals(lines(1, 2) + "\n" + lines(5, 6), first.text)
        assertTrue(controller.document.pages[1].texts.isEmpty())
        assertNull(first.next)
    }

    @Test
    fun theStyleIsTheOneOfTheChain() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startLow()
        editor.type(lines(1, 6))
        editor.finishEditing()
        val first = controller.document.pages[0].texts.single()
        editor.startEditing(first, 0)
        editor.changeColor(0xFF188038.toInt())
        editor.changeBackground(0xFFFFF1B8.toInt())
        editor.finishEditing()
        val second = controller.document.pages[1].texts.single()
        assertEquals(0xFF188038.toInt(), second.color)
        assertEquals(0xFFFFF1B8.toInt(), second.background)
    }

    @Test
    fun theCaretGoesOnIntoTheBoxesBeforeAndAfter() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startLow()
        editor.type(lines(1, 6))
        assertTrue(editor.inChain)
        // In the second box, at its start: back to the end of the first
        editor.updateValue(editor.value.copy(selection = TextRange(0)))
        assertTrue(editor.moveInChain(forward = false))
        assertEquals(0, editor.activeTextPageIndex)
        assertEquals(TextRange(editor.value.text.length), editor.value.selection)
        assertTrue(editor.moveInChain(forward = true))
        assertEquals(1, editor.activeTextPageIndex)
        assertEquals(TextRange(0), editor.value.selection)
        // Backspace at its start: its first line joined to the last line of the first box
        assertTrue(editor.backspaceIntoPreviousBox())
        editor.finishEditing()
        val chain = TextChains.of(controller.document).chainOf(controller.document.pages[0].texts.single().id)
        assertEquals(lines(1, 3) + "\nline 4line 5\nline 6", TextChains.joined(chain))
        assertFalse(chain.isEmpty())
    }

    @Test
    fun aChainWrittenIsUndoneAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startLow()
        editor.type(lines(1, 4))
        editor.finishEditing()
        val first = controller.document.pages[0].texts.single()
        editor.startEditing(first, 0)
        editor.type(lines(1, 6))
        editor.finishEditing()
        assertEquals(2, controller.document.pages.size)
        controller.undo()
        assertEquals(1, controller.document.pages.size, "the page added with the text")
        assertEquals(lines(1, 4), controller.document.pages[0].texts.single().text)
        assertNull(controller.document.pages[0].texts.single().next)
    }
}
