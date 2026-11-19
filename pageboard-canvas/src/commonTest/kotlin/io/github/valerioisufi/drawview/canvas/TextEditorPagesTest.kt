package io.github.valerioisufi.drawview.canvas

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
import io.github.valerioisufi.drawview.model.change.ChangeObserver
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The text being written moved to another page (see `docs/plans/text-editor-pages.md`): the box goes to
 * the page under its center, and the text is saved there.
 */
class TextEditorPagesTest {

    /** Lines 0.5 mm per point high, characters 0.5 mm per point wide. */
    private class FakeTextAdapter : TextTileAdapter {
        override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, paperColor: Int) {}

        override fun measure(text: DrawText): TextBlockSize =
            TextBlockSize(minOf(text.width, text.text.length * text.fontSize * 0.5f), text.fontSize * 0.5f)

        override fun lineMetrics(fontSize: Float) = TextLineMetrics(fontSize * 0.5f, fontSize * 0.4f)
    }

    /** A controller on a document of two pages, one under the other. */
    private fun controller(scope: CoroutineScope): DrawViewController {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = scope)
        controller.pageManager.addNewPageAtBottom()
        controller.viewport.setSize(800f, 1200f)
        controller.tileManager.tileRenderer.textAdapter = FakeTextAdapter()
        return controller
    }

    private fun TextEditorManager.type(text: String) = updateValue(TextFieldValue(text, TextRange(text.length)))

    /** The point ([x], [y]) of the first page in the millimeters of the second one. */
    private fun DrawViewController.onSecondPage(x: Float, y: Float): Pair<Float, Float> {
        val world = viewport.pageMmToWorld(0)!!.mapPoint(x, y)
        val toSecond = Matrix2D()
        assertTrue(viewport.pageMmToWorld(1)!!.invert(toSecond))
        val mm = toSecond.mapPoint(world.x, world.y)
        return mm.x to mm.y
    }

    @Test
    fun aNewTextDraggedToTheNextPageIsAddedThere() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val (first, second) = controller.document.pages
        val editor = controller.textEditorManager
        editor.startNewText(0, 30f, 50f)
        editor.type("Hi")

        // Its center 20 mm below the end of the first page: over the second one
        val y = first.heightMm + 20f
        editor.moveTo(30f, y, free = true)
        assertEquals(1, editor.activeTextPageIndex)
        val (x2, y2) = controller.onSecondPage(30f, y)
        assertEquals(x2, editor.boxX, 0.01f)
        assertEquals(y2, editor.boxY, 0.01f)
        assertEquals(30f, editor.boxX, 0.01f, "the pages share the scale")

        editor.finishEditing()
        assertTrue(first.texts.isEmpty())
        assertEquals("Hi", second.texts.single().text)
        assertEquals(y2, second.texts.single().y, 0.01f)
    }

    @Test
    fun aTextEditedAndMovedGoesToThePageInOneChange() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val (first, second) = controller.document.pages
        val editor = controller.textEditorManager
        editor.startNewText(1, 30f, 30f)
        editor.type("Below")
        editor.finishEditing()
        editor.startNewText(0, 30f, 50f)
        editor.type("Hi")
        editor.finishEditing()
        val text = first.texts.single()
        var changes = 0
        controller.session.addObserver(ChangeObserver { changes++ })

        editor.startEditing(text, 0)
        editor.type("Hi there")
        editor.moveTo(30f, first.heightMm + 25f, free = true)
        assertEquals(1, editor.activeTextPageIndex)
        editor.finishEditing()

        assertEquals(1, changes, "one change")
        assertFalse(text in first.texts)
        assertTrue(text in second.texts)
        assertEquals("Hi there", text.text)
        assertEquals(text, second.stack.last(), "on top of its elements")

        controller.undo()
        assertTrue(text in first.texts, "undone at once: back on its page")
        assertEquals("Hi", text.text)
        assertEquals(50f, text.y + text.height / 2f, 1f)
    }

    @Test
    fun theBoxComesBackToItsPage() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val first = controller.document.pages[0]
        val editor = controller.textEditorManager
        editor.startNewText(0, 30f, 50f)
        editor.type("Hi")
        editor.moveTo(30f, first.heightMm + 20f, free = true)
        assertEquals(1, editor.activeTextPageIndex)

        // From the second page back above its top: the first page again
        editor.moveTo(30f, -40f, free = true)
        assertEquals(0, editor.activeTextPageIndex)
        editor.finishEditing()
        assertEquals("Hi", first.texts.single().text)
    }

    @Test
    fun aMovedTextCancelledStaysOnItsPage() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val (first, second) = controller.document.pages
        val editor = controller.textEditorManager
        editor.startNewText(0, 30f, 50f)
        editor.type("Hi")
        editor.finishEditing()
        val text = first.texts.single()

        editor.startEditing(text, 0)
        editor.moveTo(30f, first.heightMm + 20f, free = true)
        // Its page deleted (the box over the other one): the editor closes, nothing saved
        controller.pageManager.deletePages(listOf(first.id))
        assertFalse(editor.isEditing)
        assertTrue(second.texts.isEmpty())
    }
}
