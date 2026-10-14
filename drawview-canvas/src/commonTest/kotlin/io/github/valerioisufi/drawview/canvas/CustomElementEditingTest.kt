package io.github.valerioisufi.drawview.canvas

import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.ElementType
import io.github.valerioisufi.drawview.model.decode
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/**
 * The editor edits the elements of the types of the application through their box, like the
 * images, and their value through the changes of the document.
 */
class CustomElementEditingTest {

    @Serializable
    data class Note(val text: String)

    object NoteType : ElementType<Note> {
        override val id = "com.example.note"
        override val serializer = Note.serializer()
    }

    private fun controller(scope: CoroutineScope): DrawViewController =
        DrawViewController(document = DrawDocument(), coroutineScope = scope).apply { setViewport(800f, 1200f) }

    @Test
    fun anElementOfTheApplicationIsAddedMovedChangedCopiedAndRemoved() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val selection = controller.selectionManager

        val element = assertNotNull(controller.customElementManager.add(NoteType, Note("a"), 0, Rect(20f, 20f, 60f, 40f)))
        assertSame(element, page.customElements.single())
        // Selected, drawn over the tiles meanwhile
        assertSame(element, selection.currentSelection?.customs?.single())
        assertTrue(controller.hiddenElements.isDragged(element.id))

        // Moved by its box
        selection.beginTransformation()
        selection.translateBy(10f, 5f)
        selection.applyTransformation()
        assertEquals(30f, element.x)
        assertEquals(25f, element.y)

        // Its value replaced, then put back by undo
        assertTrue(controller.customElementManager.update(element, NoteType, Note("b")))
        assertEquals(Note("b"), NoteType.decode(element))
        controller.undo()
        assertEquals(Note("a"), NoteType.decode(element))

        // Copied and pasted: a new element of the same value
        selection.selectCustomElement(0, element)
        selection.copySelection()
        selection.pasteSelection()
        assertEquals(2, page.customElements.size)
        val pasted = page.customElements.last()
        assertNotSame(element, pasted)
        assertEquals(Note("a"), NoteType.decode(pasted))

        // Removed (the pasted one is selected)
        selection.deleteSelection()
        assertEquals(listOf(element), page.customElements)
    }

    @Test
    fun theLassoSelectsTheElementsOfTheApplication() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val element = assertNotNull(controller.customElementManager.add(NoteType, Note("a"), 0, Rect(20f, 20f, 40f, 40f), select = false))

        controller.selectionManager.selectWithLasso(0, floatArrayOf(10f, 10f, 50f, 10f, 50f, 50f, 10f, 50f))
        val selected = assertNotNull(controller.selectionManager.currentSelection)
        assertEquals(listOf(element), selected.customs)
        assertEquals(Rect(20f, 20f, 40f, 40f), selected.boundingBox)
    }
}
