package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.change.toData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** A list going on from the list of another text, in the editor and on the page (`docs/plans/checklists-and-lists.md`). */
class ListContinuationEditingTest {

    private fun text(id: String, content: String, y: Float) = TextElement(id).apply {
        text = content
        x = 20f
        this.y = y
        width = 100f
        height = 10f
    }

    @Test
    fun aListGoesOnFromTheTextBeforeAndFollowsIt() = runTest {
        val controller = PageboardController(document = Document(), coroutineScope = backgroundScope)
        val page = controller.document.pages[0]
        val first = text("a", "1. x\n2. y", 20f)
        val second = text("b", "1. z\n2. w", 60f)
        controller.session.submit(AddElements.of(page, listOf(first, second)))

        val editor = controller.textEditorManager
        editor.startEditing(second, 0)
        assertTrue(editor.canContinueNumbering)
        assertFalse(editor.continuesNumbering)
        editor.setContinuesNumbering(true)
        assertTrue(editor.continuesNumbering)
        assertEquals(3, editor.continuedListStart)
        editor.finishEditing()
        assertEquals("a", page.texts.single { it.id == "b" }.continueNumberingFrom)
        assertEquals(3, page.texts.single { it.id == "b" }.continuedListStart)

        // The text before gets an item: the list after it follows
        controller.session.submit(UpdateTexts(page.id, listOf(first.toData().copy(text = "1. x\n2. y\n3. v"))))
        assertEquals(4, page.texts.single { it.id == "b" }.continuedListStart)

        // Counting on its own again
        editor.startEditing(page.texts.single { it.id == "b" }, 0)
        editor.setContinuesNumbering(false)
        editor.finishEditing()
        assertNull(page.texts.single { it.id == "b" }.continueNumberingFrom)
        assertNull(page.texts.single { it.id == "b" }.continuedListStart)
    }

    @Test
    fun writingATextKeepsWhatTheEditorDoesNotChange() = runTest {
        val controller = PageboardController(document = Document(), coroutineScope = backgroundScope)
        val page = controller.document.pages[0]
        val first = text("a", "1. x", 20f)
        val second = text("b", "1. z", 60f).apply {
            continueNumberingFrom = "a"
            maxHeight = 40f
        }
        controller.session.submit(AddElements.of(page, listOf(first, second)))
        val editor = controller.textEditorManager
        editor.startEditing(page.texts.single { it.id == "b" }, 0)
        editor.updateValue(TextFieldValue("1. z changed", TextRange(12)))
        editor.finishEditing()
        val saved = page.texts.single { it.id == "b" }
        assertEquals("1. z changed", saved.text)
        assertEquals("a", saved.continueNumberingFrom)
        assertEquals(40f, saved.maxHeight)
    }
}
