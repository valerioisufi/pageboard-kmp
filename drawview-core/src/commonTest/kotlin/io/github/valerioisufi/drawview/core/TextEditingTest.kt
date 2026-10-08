package io.github.valerioisufi.drawview.core

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.core.render.tile.TextBlockSize
import io.github.valerioisufi.drawview.core.render.tile.TextLineMetrics
import io.github.valerioisufi.drawview.core.render.tile.TextTileAdapter
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Writing the texts: where the caret goes, what keeps the text written, the size of the boxes.
 */
class TextEditingTest {

    /** Lines 0.5 mm per point high; a character 0.5 mm per point wide, wrapped on the width. */
    private class FakeTextAdapter : TextTileAdapter {
        override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D) {}

        override fun measure(text: DrawText): TextBlockSize {
            val charWidth = text.fontSize * 0.5f
            val lines = ceil(text.text.length * charWidth / text.width).coerceAtLeast(1f)
            return TextBlockSize(minOf(text.width, text.text.length * charWidth), lines * text.fontSize * 0.5f)
        }

        override fun lineMetrics(fontSize: Float) = TextLineMetrics(fontSize * 0.5f, fontSize * 0.4f)
    }

    private fun controller(scope: CoroutineScope): DrawViewController {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = scope)
        controller.setViewport(800f, 1200f)
        controller.tileManager.tileRenderer.textAdapter = FakeTextAdapter()
        return controller
    }

    /** Saves what the editor shows, as the editor of the interface does. */
    private fun DrawViewController.attachEditor(text: String) {
        textEditorManager.finishEditingRequest = {
            textEditorManager.finishTextEditing(text, isLatex = false, color = 0xFF000000.toInt(), fontSize = 16f, measuredHeightMm = 8f)
        }
    }

    @Test
    fun aNewTextPutsTheCaretOnTheTap() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        controller.updateTextDefaults(20f, controller.defaultTextColor)
        val editor = controller.textEditorManager

        editor.startNewText(0, 30f, 50f)
        // The box starts at the tap, its first line (10 mm high) centered on it
        assertEquals(30f, editor.activeTextMmX)
        assertEquals(45f, editor.activeTextMmY)
        assertNull(editor.takeCaretRequest(), "a new text has its caret at its start")
    }

    @Test
    fun anOpenedTextPutsTheCaretWhereItIsTouched() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val text = DrawText().apply { x = 10f; y = 20f; width = 40f; height = 10f; this.text = "Hello" }

        editor.startEditing(text, 0, text.x, text.y, text.width, caretAt = text.toLocal(25f, 24f))
        assertEquals(Offset(15f, 4f), editor.takeCaretRequest())
        assertNull(editor.takeCaretRequest(), "given once")
    }

    @Test
    fun aPointOfARotatedTextIsTakenBeforeItsRotation() {
        val text = DrawText().apply { x = 0f; y = 0f; width = 40f; height = 10f; rotation = 90f }
        // Turned around its middle (20, 5): its top-left corner is now at (25, -15)
        val corner = text.toLocal(25f, -15f)
        assertEquals(0f, corner.x, 1e-3f)
        assertEquals(0f, corner.y, 1e-3f)
        val end = text.toLocal(15f, 25f)
        assertEquals(40f, end.x, 1e-3f)
        assertEquals(10f, end.y, 1e-3f)
    }

    @Test
    fun theTextWrittenIsKeptWhenTheEditorIsLeft() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val editor = controller.textEditorManager
        controller.attachEditor("Kept")

        // Another tool
        editor.startNewText(0, 30f, 50f)
        controller.selectTool(Tool.INK_PEN)
        assertFalse(editor.isEditing)
        assertEquals(listOf("Kept"), page.texts.map { it.text })

        // The page grid
        controller.selectTool(Tool.TEXT)
        editor.startNewText(0, 30f, 100f)
        controller.togglePageGrid()
        controller.togglePageGrid()
        assertEquals(2, page.texts.size)

        // The drawing mode turned off
        editor.startNewText(0, 30f, 150f)
        if (controller.isDrawingMode) controller.toggleDrawingMode()
        assertFalse(editor.isEditing)
        assertEquals(3, page.texts.size)
    }

    @Test
    fun aResizedTextIsMeasuredAgain() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        controller.attachEditor("x".repeat(40))
        editor.startNewText(0, 10f, 50f)
        editor.requestFinishEditing()
        val text = controller.document.pages[0].texts.single()

        controller.selectionManager.selectText(0, text)
        // Half the width: twice the lines
        val before = assertNotNull(controller.measureText(text)).height
        controller.selectionManager.beginTransformation()
        controller.selectionManager.resizeText(Offset(text.x + text.width / 2f, text.y), isRightEdge = true)
        assertEquals(controller.measureText(text)?.height, text.height)
        assert(text.height > before)
        controller.selectionManager.applyTransformation()
        assertEquals(assertNotNull(controller.measureText(text)).height, text.height, "the change keeps the height measured")

        // A bigger size wraps the lines again on the same width
        controller.selectionManager.updateSelectedTextsFontSize(32f)
        assertEquals(32f, text.fontSize)
        assertEquals(assertNotNull(controller.measureText(text)).height, text.height)
    }
}
