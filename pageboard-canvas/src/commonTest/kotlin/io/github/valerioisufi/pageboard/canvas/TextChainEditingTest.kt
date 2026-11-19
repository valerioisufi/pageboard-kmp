package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.controller.TextEditorManager
import io.github.valerioisufi.pageboard.canvas.render.tile.TextBlockSize
import io.github.valerioisufi.pageboard.canvas.render.tile.TextLineMetrics
import io.github.valerioisufi.pageboard.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.text.TextChains
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
        override fun drawText(canvas: Canvas, text: TextElement, mmToCanvas: Matrix2D, paperColor: Int) {}
        override fun measure(text: TextElement): TextBlockSize = TextBlockSize(text.width, text.text.split('\n').size * 10f)
        override fun lineMetrics(fontSize: Float) = TextLineMetrics(10f, 8f)
    }

    private fun controller(scope: CoroutineScope): PageboardController {
        val controller = PageboardController(document = Document(), coroutineScope = scope)
        controller.viewport.setSize(800f, 1200f)
        controller.tileManager.tileRenderer.textAdapter = LinesAdapter()
        return controller
    }

    private fun TextEditorManager.type(text: String) = updateValue(TextFieldValue(text, TextRange(text.length)))

    private fun lines(from: Int, to: Int) = (from..to).joinToString("\n") { "line $it" }

    /** A box 100 mm wide starting 245 mm down the first page (297 mm high, 5 mm of margin): room for 4 lines. */
    private fun PageboardController.startLow() = textEditorManager.startDrawnText(0, Rect(20f, 245f, 120f, 255f))

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
    fun anItemTakenAwayMovesTheNumbersInTheNextBoxes() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startLow()
        val list = (1..6).joinToString("\n") { "$it. i$it" }
        editor.type(list)
        assertEquals("5. i5\n6. i6", editor.value.text, "the list went on in a new box")
        editor.finishEditing()
        val first = controller.document.pages[0].texts.single()
        editor.startEditing(first, 0)
        editor.type(first.text.removePrefix("1. i1\n"))
        assertEquals("1. i2\n2. i3\n3. i4", editor.value.text, "the start stays")
        editor.finishEditing()
        val texts = controller.document.pages.flatMap { it.texts }.joinToString("\n") { it.text }
        assertEquals("1. i2\n2. i3\n3. i4\n4. i5\n5. i6", texts, "the next box follows on closing")
    }

    @Test
    fun theNumbersAreRightAfterAnEditInTheEditor() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startDrawnText(0, Rect(20f, 20f, 120f, 30f))
        editor.type("1. a\n2. b\n3. c")
        editor.updateValue(TextFieldValue("2. b\n3. c", TextRange(4)))
        assertEquals("1. b\n2. c", editor.value.text)
        assertEquals(TextRange(4), editor.value.selection)
        // Backspace at the start of the text of an item: its marker goes, the next one goes on
        editor.type("1. a\n2. b\n3. c")
        val caret = "1. a\n2. ".length
        editor.updateValue(TextFieldValue("1. a\n2. b\n3. c", TextRange(caret)))
        editor.updateValue(TextFieldValue("1. a\n2.b\n3. c", TextRange(caret - 1)))
        assertEquals("1. a\nb\n2. c", editor.value.text)
        // Tab on an item: from 1 at its level
        editor.type("1. a\n2. b\n3. c")
        editor.updateValue(TextFieldValue("1. a\n2. b\n3. c", TextRange(13)))
        editor.indent()
        assertEquals("1. a\n2. b\n  1. c", editor.value.text)
        editor.outdent()
        assertEquals("1. a\n2. b\n3. c", editor.value.text)
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
        // The two lines of the second box came back, the second box removed with the page made for it
        assertEquals(lines(1, 2) + "\n" + lines(5, 6), first.text)
        assertEquals(1, controller.document.pages.size)
        assertNull(first.next)
    }

    @Test
    fun theLastBoxEmptiedGoesWithItsPageAndOneUndoBringsThemBack() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startLow()
        editor.type(lines(1, 6))
        editor.finishEditing()
        val first = controller.document.pages[0].texts.single()
        val second = controller.document.pages[1].texts.single()
        editor.startEditing(second, 1)
        editor.type("")
        editor.finishEditing()
        assertEquals(1, controller.document.pages.size)
        assertNull(first.next, "no link to a box removed")
        controller.undo()
        assertEquals(2, controller.document.pages.size)
        assertEquals(lines(5, 6), controller.document.pages[1].texts.single().text)
        assertEquals(second.id, controller.document.pages[0].texts.single().next)
    }

    @Test
    fun aPageWithSomethingElseOnItStays() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startLow()
        editor.type(lines(1, 6))
        editor.finishEditing()
        val page = controller.document.pages[1]
        controller.session.submit(AddElements.of(page, listOf(TextElement().also { it.text = "Mine"; it.x = 20f; it.y = 200f })))
        val second = page.texts.first { it.text != "Mine" }
        editor.startEditing(second, 1)
        editor.type("")
        editor.finishEditing()
        assertEquals(2, controller.document.pages.size)
        assertEquals(listOf("Mine"), controller.document.pages[1].texts.map { it.text })
    }

    @Test
    fun aBoxEmptiedInTheMiddleKeepsTheChainWhole() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startLow()
        editor.type(lines(1, 35))
        editor.finishEditing()
        assertEquals(3, controller.document.pages.size)
        val first = controller.document.pages[0].texts.single()
        val middle = controller.document.pages[1].texts.single()
        editor.startEditing(middle, 1)
        editor.type("")
        editor.finishEditing()
        // The lines of the last box come back to the one emptied, the last one goes with its page
        assertEquals(2, controller.document.pages.size)
        assertEquals(middle.id, first.next)
        assertEquals(lines(33, 35), controller.document.pages[1].texts.single().text)
        assertNull(controller.document.pages[1].texts.single().next)
        assertEquals(listOf(first.id, middle.id), TextChains.of(controller.document).chainOf(first.id).map { it.id })
    }

    @Test
    fun blankLinesAtTheEndStartNoBox() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startLow()
        editor.type(lines(1, 4) + "\n\n\n")
        assertEquals(1, controller.document.pages.size)
        assertEquals(0, editor.activeTextPageIndex)
        editor.finishEditing()
        assertEquals(1, controller.document.pages.size, "nor on closing")
        assertNull(controller.document.pages[0].texts.single().next)
    }

    @Test
    fun theKeysOfTheFrameOfAFlowGoOnInTheNewBox() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startLow()
        editor.type(lines(1, 4))
        val fieldSession = editor.sessionId
        val whole = lines(1, 5)
        editor.type(whole)
        assertEquals(1, editor.activeTextPageIndex, "the text flowed")
        assertEquals("line 5", editor.value.text)
        // The field of the first box still has the whole text: a key, then a Backspace, then two keys
        editor.typedBeforeFlow(fieldSession, whole, TextFieldValue(whole + "a", TextRange(whole.length + 1)))
        assertEquals("line 5a", editor.value.text)
        editor.typedBeforeFlow(fieldSession, whole + "a", TextFieldValue(whole, TextRange(whole.length)))
        assertEquals("line 5", editor.value.text)
        editor.typedBeforeFlow(fieldSession, whole, TextFieldValue(whole + "bc", TextRange(whole.length + 2)))
        assertEquals("line 5bc", editor.value.text)
        // Not from any other field
        editor.typedBeforeFlow(fieldSession - 1, whole, TextFieldValue(whole + "d", TextRange(whole.length + 1)))
        assertEquals("line 5bc", editor.value.text)
    }

    @Test
    fun backspaceInAnEmptyLastBoxLeavesNoTraceOfIt() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startLow()
        editor.type(lines(1, 5))
        editor.type("")
        assertTrue(editor.isEditing)
        val second = controller.document.pages.getOrNull(1)?.texts?.firstOrNull()
        assertTrue(second != null, "the second box written is open")
        assertTrue(editor.backspaceIntoPreviousBox())
        assertEquals(1, controller.document.pages.size)
        assertEquals(0, editor.activeTextPageIndex)
        assertFalse(editor.chainGoesOn, "no mark of a chain going on")
        assertNull(controller.document.pages[0].texts.single().next)
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
