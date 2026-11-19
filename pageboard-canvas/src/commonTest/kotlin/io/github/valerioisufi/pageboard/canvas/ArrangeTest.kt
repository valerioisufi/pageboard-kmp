package io.github.valerioisufi.pageboard.canvas

import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.StackMove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The selection in the stack of its page: drawn at its level until it is moved, arranged and
 * undone, put on top of the page it is moved to, pasted in its order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArrangeTest {

    private fun image(x: Float, y: Float = 0f) = ImageElement().apply {
        this.x = x; this.y = y; width = 20f; height = 20f
    }

    private fun controller(scope: CoroutineScope, pages: Int = 1): PageboardController {
        val document = Document()
        repeat(pages - 1) { document.addPage() }
        return PageboardController(document = document, coroutineScope = scope).also { it.viewport.setSize(800f, 1200f) }
    }

    @Test
    fun aSelectionStaysAtItsLevelUntilItIsMoved() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val under = image(0f)
        controller.session.submit(AddElements.of(page, listOf(under, image(5f))))
        val selections = controller.selectionManager

        selections.selectImage(0, under)
        val selection = assertNotNull(selections.currentSelection)
        // Drawn by the tiles, at its level
        assertFalse(selection.isLifted)
        assertFalse(controller.hiddenElements.isHidden(under.id))

        // Lifted while moved, back at its level after
        selections.beginTransformation()
        assertTrue(selection.isLifted)
        assertTrue(controller.hiddenElements.isHidden(under.id))
        selections.translateBy(10f, 0f)
        selections.applyTransformation()
        assertFalse(selection.isLifted)
        assertFalse(controller.hiddenElements.isHidden(under.id))
        assertEquals(10f, under.x)
        // A press without a move too
        selections.beginTransformation()
        selections.applyTransformation()
        assertFalse(controller.hiddenElements.isHidden(under.id))
    }

    @Test
    fun theSelectionIsArrangedAndUndone() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val under = image(0f)
        val over = image(5f)
        controller.session.submit(AddElements.of(page, listOf(under, over)))
        val selections = controller.selectionManager
        selections.selectImage(0, under)

        assertTrue(selections.canArrange(StackMove.FRONT))
        assertTrue(selections.canArrange(StackMove.FORWARD))
        assertFalse(selections.canArrange(StackMove.BACK))
        assertFalse(selections.canArrange(StackMove.BACKWARD))
        selections.arrange(StackMove.FRONT)
        assertEquals(listOf(over, under), page.stack)
        // Still selected, at its new level
        assertEquals<List<ImageElement>?>(listOf(under), selections.currentSelection?.images)
        assertFalse(selections.canArrange(StackMove.FRONT))
        assertTrue(selections.canArrange(StackMove.BACK))

        controller.undo()
        assertEquals(listOf(under, over), page.stack)
        controller.redo()
        assertEquals(listOf(over, under), page.stack)
    }

    @Test
    fun elementsMovedToAnotherPageGoOnTopThere() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, pages = 2)
        val (first, second) = controller.document.pages
        val moved = image(20f, 280f)
        controller.session.submit(AddElements.of(first, listOf(moved)))
        val there = image(20f, 0f)
        controller.session.submit(AddElements.of(second, listOf(there)))
        // The one moved is older: it would go under the other one
        val selections = controller.selectionManager
        selections.selectImage(0, moved)
        selections.beginTransformation()
        selections.translateBy(0f, 60f)
        selections.applyTransformation()

        assertSame(second, controller.document.findElement(moved.id)?.first)
        assertEquals(listOf(there, moved), second.stack)
    }

    @Test
    fun pastedElementsKeepTheirOrder() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val under = image(0f)
        val over = image(5f)
        controller.session.submit(AddElements.of(page, listOf(under, over)))
        val selections = controller.selectionManager
        selections.selectImage(0, over)
        selections.arrange(StackMove.BACK)
        // over is under now: copied with under, pasted in that order
        selections.selectWithLasso(0, floatArrayOf(-5f, -5f, 40f, -5f, 40f, 40f, -5f, 40f))
        selections.copySelection()
        selections.pasteSelection()

        val pasted = page.stack.drop(2)
        assertEquals(2, pasted.size)
        assertEquals(over.x, (pasted[0] as ImageElement).x - 10f)
        assertEquals(under.x, (pasted[1] as ImageElement).x - 10f)
    }
}
