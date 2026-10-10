package io.github.valerioisufi.drawview.core

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.core.controller.Collaborator
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.controller.TextEditorManager
import io.github.valerioisufi.drawview.core.geometry.toCompose
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.change.CommittedChange
import io.github.valerioisufi.drawview.model.change.RemoveElements
import io.github.valerioisufi.drawview.model.geometry.Dimension
import io.github.valerioisufi.drawview.model.geometry.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The editor makes every change of the document through its session.
 */
class EditorChangesTest {

    private fun stroke(left: Float, top: Float, size: Float = 5f): DrawStroke {
        val mesh = StrokeMesh(
            positions = floatArrayOf(left, top, left + size, top, left + size, top + size, left, top + size),
            indices = shortArrayOf(0, 1, 2, 0, 2, 3),
            color = Color.Black.toArgb(),
            tool = StrokeTool.MARKER,
            size = 1f,
            points = listOf(StrokePoint(left, top), StrokePoint(left + size, top + size))
        )
        return DrawStroke(mesh = mesh, brushConfig = BrushConfig(size = 1f))
    }

    private fun controllerWith(pages: Int, scope: CoroutineScope): DrawViewController {
        val doc = DrawDocument()
        repeat(pages - 1) { doc.addPage() }
        val controller = DrawViewController(document = doc, coroutineScope = scope)
        controller.setViewport(800f, 1200f)
        return controller
    }

    /** The changes committed by [controller] from now on. */
    private fun committed(controller: DrawViewController) = mutableListOf<CommittedChange>().also { list ->
        controller.session.addObserver { list.add(it) }
    }

    @Test
    fun anEraserGestureIsUndoneAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(1, backgroundScope)
        controller.selectTool(Tool.ERASER)
        val page = controller.document.pages[0]
        val strokes = listOf(stroke(20f, 50f), stroke(60f, 50f), stroke(100f, 50f), stroke(20f, 150f))
        strokes.forEach { page.add(it) }
        val changes = committed(controller)

        val mmToScreen = assertNotNull(controller.pageMmToScreen(0))
        // Two segments of one gesture, each erasing as soon as it touches
        controller.eraserManager.eraseAlongSegment(mmToScreen.mapPoint(15f, 52f).toCompose(), mmToScreen.mapPoint(65f, 52f).toCompose())
        assertEquals(listOf(strokes[2], strokes[3]), page.strokes.toList())
        controller.eraserManager.eraseAlongSegment(mmToScreen.mapPoint(65f, 52f).toCompose(), mmToScreen.mapPoint(110f, 52f).toCompose())
        controller.eraserManager.commitGesture()
        assertEquals(listOf(strokes[3]), page.strokes.toList())
        assertEquals(2, changes.size, "every segment is applied (and sent) at once")

        controller.undo()
        assertEquals(strokes, page.strokes.toList())
        assertFalse(controller.canUndo)
        controller.redo()
        assertEquals(listOf(strokes[3]), page.strokes.toList())
    }

    @Test
    fun aLassoMoveToAnotherPageIsUndoneInPlace() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(2, backgroundScope)
        val (first, second) = controller.document.pages
        val strokes = listOf(stroke(20f, 20f, 10f), stroke(100f, 20f, 10f), stroke(150f, 150f, 10f))
        strokes.forEach { first.add(it) }

        controller.selectionManager.selectWithLasso(0, floatArrayOf(10f, 10f, 40f, 10f, 40f, 40f, 10f, 40f))
        controller.selectionManager.beginTransformation()
        // Down to the next page
        controller.selectionManager.translateBy(0f, first.heightMm + 30f)
        controller.selectionManager.applyTransformation()
        assertEquals(listOf(strokes[0]), second.strokes.toList())
        assertEquals(1, controller.selectionManager.currentSelection?.pageIndex)

        controller.undo()
        assertEquals(strokes, first.strokes.toList(), "back where it was in the stacking order")
        assertTrue(strokes[0].transform.isIdentity)
        assertTrue(second.strokes.isEmpty())
    }

    @Test
    fun aTextIsCreatedEditedAndDeleted() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(1, backgroundScope)
        val page = controller.document.pages[0]
        val editor = controller.textEditorManager

        editor.startNewText(0, 10f, 20f)
        editor.write("Hello")
        val text = page.texts.single()
        assertEquals("Hello", text.text)

        editor.startEditing(text, 0)
        editor.changeColor(0xFFFF0000.toInt())
        editor.write("Hello, world")
        assertSame(text, page.texts.single())
        assertEquals("Hello, world", text.text)
        assertFalse(controller.hiddenElements.isDragged(text.id))

        // Saved without changes: nothing to undo
        editor.startEditing(text, 0)
        editor.finishEditing()

        editor.startEditing(text, 0)
        editor.write(" ")
        assertTrue(page.texts.isEmpty())

        controller.undo()
        assertSame(text, page.texts.single())
        controller.undo()
        assertEquals("Hello", text.text)
        controller.undo()
        assertTrue(page.texts.isEmpty())
        assertFalse(controller.canUndo)
    }

    @Test
    fun aTextAnotherDeviceIsWritingIsLeftToIt() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(1, backgroundScope)
        val page = controller.document.pages[0]
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 20f)
        editor.write("Notes")
        val text = page.texts.single()
        val refused = mutableListOf<String>()
        backgroundScope.launch { controller.editsRefused.collect { refused.add(it.name) } }

        // Selected here, then written on another device: left to it
        controller.selectionManager.selectText(0, text)
        controller.collaborators = listOf(
            Collaborator("bob", "Bob", Color.Blue, pageId = null, position = Offset.Zero, text = DrawText(text.id), textPageId = page.id)
        )
        assertNull(controller.selectionManager.currentSelection)
        // Neither opened nor selected meanwhile: the user is told who is writing it
        editor.startEditing(text, 0)
        assertFalse(editor.isEditing)
        controller.selectionManager.selectWithLasso(0, floatArrayOf(0f, 0f, 200f, 0f, 200f, 200f, 0f, 200f))
        assertNull(controller.selectionManager.currentSelection)
        assertEquals(listOf("Bob", "Bob"), refused)

        // Written: free again
        controller.collaborators = emptyList()
        editor.startEditing(text, 0)
        assertTrue(editor.isEditing)
    }

    @Test
    fun pastedStrokesAreNewStrokesWithTheSameGeometry() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(1, backgroundScope)
        val page = controller.document.pages[0]
        val original = stroke(20f, 20f, 10f)
        page.add(original)

        controller.selectionManager.selectWithLasso(0, floatArrayOf(10f, 10f, 40f, 10f, 40f, 40f, 10f, 40f))
        controller.selectionManager.copySelection()
        controller.selectionManager.pasteSelection()
        val pasted = page.strokes.last()
        assertNotEquals(original.id, pasted.id)
        assertSame(original.baseMesh, pasted.baseMesh)
        assertEquals(original.bounds.left + 10f, pasted.bounds.left, 1e-3f)

        controller.undo()
        assertEquals(listOf(original), page.strokes.toList())
    }

    @Test
    fun aChangeFromAnotherDeviceDropsTheStaleSelection() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(1, backgroundScope)
        val page = controller.document.pages[0]
        val selected = stroke(20f, 20f, 10f)
        page.add(selected)
        controller.selectionManager.selectWithLasso(0, floatArrayOf(10f, 10f, 40f, 10f, 40f, 40f, 10f, 40f))
        assertNotNull(controller.selectionManager.currentSelection)

        assertTrue(controller.session.receive(remoteOperation(RemoveElements.of(page, listOf(selected)))))
        assertNull(controller.selectionManager.currentSelection)
        assertFalse(controller.canUndo, "the changes of another device are not in the history")
    }

    @Test
    fun theDocumentTemplateChangeIsUndoable() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWith(2, backgroundScope)
        val document = controller.document
        controller.pageManager.applyDocumentTemplateChange(Dimension(297f.mm, 210f.mm), PageBackground.Grid(), overrideAll = true)
        assertEquals(listOf(297f, 297f), document.pages.map { it.widthMm })
        controller.undo()
        assertEquals(listOf(210f, 210f), document.pages.map { it.widthMm })
        assertEquals(210f, document.defaultWidthMm)
    }

    /** Writes [text] in the editor (the whole of it) and saves it. */
    private fun TextEditorManager.write(text: String) {
        updateValue(TextFieldValue(text, TextRange(text.length)))
        finishEditing()
    }
}
