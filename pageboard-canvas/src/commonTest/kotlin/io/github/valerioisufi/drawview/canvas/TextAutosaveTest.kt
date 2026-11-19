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
import io.github.valerioisufi.drawview.model.change.CommittedChange
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** The text being written saved in the document with the editor open (see `docs/plans/text-autosave.md`). */
class TextAutosaveTest {

    /** Every line of the source 10 mm high (a line never wraps). */
    private class LinesAdapter : TextTileAdapter {
        override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, paperColor: Int) {}
        override fun measure(text: DrawText): TextBlockSize = TextBlockSize(text.width, text.text.split('\n').size * 10f)
        override fun lineMetrics(fontSize: Float) = TextLineMetrics(10f, 8f)
    }

    private fun controller(scope: CoroutineScope, pages: Int = 1): DrawViewController {
        val document = DrawDocument()
        repeat(pages - 1) { document.addPage() }
        val controller = DrawViewController(document = document, coroutineScope = scope)
        controller.viewport.setSize(800f, 1200f)
        controller.tileManager.tileRenderer.textAdapter = LinesAdapter()
        return controller
    }

    private fun TextEditorManager.type(text: String) = updateValue(TextFieldValue(text, TextRange(text.length)))

    /** The changes committed by [controller] from now on. */
    private fun committed(controller: DrawViewController) = mutableListOf<CommittedChange>().also { list ->
        controller.session.addObserver { list.add(it) }
    }

    /** A text [content] on the first page of [controller], as one written before. */
    private fun existingText(controller: DrawViewController, content: String): DrawText {
        val text = DrawText().apply {
            text = content
            x = 20f
            y = 30f
            width = 100f
        }
        controller.document.pages[0].add(text)
        return text
    }

    private fun DrawViewController.startDrawn() = textEditorManager.startDrawnText(0, Rect(20f, 30f, 120f, 40f))

    @Test
    fun aNewTextIsSavedWithTheEditorOpen() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val page = controller.document.pages[0]
        controller.startDrawn()
        val session = editor.sessionId
        editor.updateValue(TextFieldValue("hello", TextRange(2)))
        editor.saveWriting()

        val saved = page.texts.single()
        assertEquals("hello", saved.text)
        // The writing goes on as it was
        assertTrue(editor.isEditing)
        assertEquals(session, editor.sessionId)
        assertEquals(TextFieldValue("hello", TextRange(2)), editor.value)
        assertTrue(controller.hiddenElements.isDragged(saved.id), "the editor draws it, not the tiles")
        assertTrue(controller.canUndo)

        editor.type("hello world")
        editor.saveWriting()
        assertEquals("hello world", page.texts.single().text)
        editor.type("hello world!")
        editor.finishEditing()
        assertEquals(saved.id, page.texts.single().id)
        assertEquals("hello world!", page.texts.single().text)
        assertFalse(controller.hiddenElements.isDragged(saved.id))

        // One step for the whole writing
        controller.undo()
        assertTrue(page.texts.isEmpty())
        assertFalse(controller.canUndo)
        controller.redo()
        assertEquals("hello world!", page.texts.single().text)
    }

    @Test
    fun aChangedTextIsSavedAndUndoneInOneStep() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val text = existingText(controller, "abc")
        editor.startEditing(text, 0)
        editor.type("abcd")
        editor.saveWriting()
        assertEquals("abcd", text.text)
        assertTrue(editor.isEditing)
        editor.type("abcde")
        editor.saveWriting()
        assertEquals("abcde", text.text)
        editor.type("abcdef")
        editor.finishEditing()
        assertEquals("abcdef", text.text)

        controller.undo()
        assertEquals("abc", controller.document.pages[0].texts.single().text)
        assertFalse(controller.canUndo)
    }

    @Test
    fun nothingIsSavedWhenBlankOrUnchanged() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val changes = committed(controller)
        controller.startDrawn()
        editor.saveWriting()
        editor.type("  \n")
        editor.saveWriting()
        assertTrue(changes.isEmpty())
        assertTrue(controller.document.pages[0].texts.isEmpty())

        editor.type("text")
        editor.saveWriting()
        editor.saveWriting()
        assertEquals(1, changes.size, "saved once")
        editor.finishEditing()
        assertEquals(1, changes.size, "nothing changed since")
        assertTrue(controller.canUndo)
    }

    @Test
    fun aNewTextSavedThenEmptiedLeavesNothingToUndo() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.startDrawn()
        editor.type("gone")
        editor.saveWriting()
        editor.type("")
        editor.finishEditing()
        assertTrue(controller.document.pages[0].texts.isEmpty())
        assertFalse(controller.canUndo)
    }

    @Test
    fun aChangedTextSavedThenEmptiedComesBackWithOneUndo() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val text = existingText(controller, "abc")
        editor.startEditing(text, 0)
        editor.type("ab")
        editor.saveWriting()
        editor.type("")
        editor.finishEditing()
        assertTrue(controller.document.pages[0].texts.isEmpty())
        controller.undo()
        assertEquals("abc", controller.document.pages[0].texts.single().text)
    }

    @Test
    fun aBoxOfAChainIsSavedWithItsLinksAndTheWritingIsUndoneInOneStep() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        // 245 mm down the first page: room for four lines
        editor.startDrawnText(0, Rect(20f, 245f, 120f, 255f))
        editor.type("line 1\nline 2")
        editor.saveWriting()
        editor.type("line 1\nline 2\nline 3\nline 4\nline 5")
        // Flowed into a box on a new page, the editor on it
        assertEquals(2, controller.document.pages.size)
        val first = controller.document.pages[0].texts.single()
        val second = controller.document.pages[1].texts.single()
        assertEquals(second.id, first.next)
        assertEquals(1, editor.activeTextPageIndex)

        editor.type("line 5\nline 6")
        editor.saveWriting()
        assertEquals("line 5\nline 6", second.text)
        assertEquals(second.id, first.next, "the chain kept")
        assertEquals("line 1\nline 2\nline 3\nline 4", first.text)
        editor.type("line 5\nline 6\nline 7")
        editor.finishEditing()
        assertEquals("line 5\nline 6\nline 7", controller.document.pages[1].texts.single().text)

        // Saved, flowed and closed: one writing, one step
        controller.undo()
        assertEquals(1, controller.document.pages.size)
        assertTrue(controller.document.pages[0].texts.isEmpty())
        assertFalse(controller.canUndo)
    }

    @Test
    fun aTextChangedOnAnotherDeviceIsNotLost() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val text = existingText(controller, "abc")
        val before = text.toData().copy(order = null)
        editor.startEditing(text, 0)
        editor.type("abc here")
        editor.saveWriting()
        // Changed meanwhile on another device that had not seen the save
        val page = controller.document.pages[0]
        assertTrue(controller.session.receive(remoteOperation(UpdateTexts(page.id, listOf(before.copy(text = "abc there"))))))
        editor.type("abc here, more")
        editor.finishEditing()

        val texts = page.texts.map { it.text }
        assertEquals("abc here, more", page.texts.first { it.id == text.id }.text)
        assertTrue("abc there" in texts, "the other device's text kept as a copy: $texts")
    }

    @Test
    fun aTextMovedToAnotherPageIsSavedThere() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, pages = 2)
        val editor = controller.textEditorManager
        val text = existingText(controller, "abc")
        editor.startEditing(text, 0)
        editor.type("abcd")
        // Its center over the second page
        editor.moveTo(20f, controller.document.pages[0].heightMm + 20f, free = true)
        assertEquals(1, editor.activeTextPageIndex)
        editor.saveWriting()
        assertTrue(controller.document.pages[0].texts.isEmpty())
        assertEquals("abcd", controller.document.pages[1].texts.single().text)
        assertTrue(editor.isEditing)
        editor.type("abcde")
        editor.finishEditing()
        assertEquals("abcde", controller.document.pages[1].texts.single().text)

        controller.undo()
        assertEquals("abc", controller.document.pages[0].texts.single().text)
        assertTrue(controller.document.pages[1].texts.isEmpty())
    }

    @Test
    fun undoWhileWritingUndoesTheWriting() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val text = existingText(controller, "abc")
        editor.startEditing(text, 0)
        editor.type("abcd")
        editor.saveWriting()
        editor.type("abcde")
        controller.undo()
        assertFalse(editor.isEditing)
        assertEquals("abc", assertNotNull(controller.document.pages[0].texts.singleOrNull()).text)
    }

    @Test
    fun anotherChangeWhileWritingIsAStepOfItsOwn() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val text = existingText(controller, "abc")
        editor.startEditing(text, 0)
        editor.type("abcd")
        editor.saveWriting()
        // A page added meanwhile (e.g. from the menu)
        controller.pageManager.addNewPageAtBottom()
        editor.type("abcde")
        editor.finishEditing()
        assertEquals(2, controller.document.pages.size)

        controller.undo()
        assertEquals("abcd", text.text, "the part of the writing after the page")
        controller.undo()
        assertEquals(1, controller.document.pages.size)
        controller.undo()
        assertEquals("abc", text.text)
    }
}
