package io.github.valerioisufi.drawview.core

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.core.canvas.FrameStyle
import io.github.valerioisufi.drawview.core.canvas.SelectionFrame
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.controller.TextEditorManager
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.TextAlignment
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.core.render.tile.TextBlockSize
import io.github.valerioisufi.drawview.core.render.tile.TextLineMetrics
import io.github.valerioisufi.drawview.core.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.core.text.RichTextEditing
import io.github.valerioisufi.drawview.core.text.TextDefaults
import io.github.valerioisufi.drawview.core.text.TextSizes
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Writing the texts: where the caret goes, what keeps the text written, the size of the boxes,
 * the formatting.
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

    private fun TextEditorManager.type(text: String) = updateValue(TextFieldValue(text, TextRange(text.length)))

    @Test
    fun aNewTextPutsTheCaretOnTheTap() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        controller.updateTextDefaults(TextDefaults(fontSize = 20f, alignment = TextAlignment.CENTER))
        val editor = controller.textEditorManager

        editor.startNewText(0, 30f, 50f)
        // The caret on the tap: the first line (10 mm high) centered on it, the box too (centered text)
        assertEquals(30f, editor.boxX + editor.boxWidth / 2f)
        assertEquals(45f, editor.boxY)
        assertEquals(20f, editor.fontSize, "the size of the tool")
        assertEquals(TextAlignment.CENTER, editor.alignment, "the alignment of the tool")
        assertNull(editor.takeCaretRequest(), "a new text has its caret at its start")
    }

    @Test
    fun anOpenedTextPutsTheCaretWhereItIsTouched() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val text = DrawText().apply { x = 10f; y = 20f; width = 40f; height = 10f; this.text = "Hello" }

        editor.startEditing(text, 0, caretAt = text.toLocal(25f, 24f))
        assertEquals("Hello", editor.value.text)
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

        // Another tool
        editor.startNewText(0, 30f, 50f)
        editor.type("Kept")
        controller.selectTool(Tool.INK_PEN)
        assertFalse(editor.isEditing)
        assertEquals(listOf("Kept"), page.texts.map { it.text })

        // The page grid
        controller.selectTool(Tool.TEXT)
        editor.startNewText(0, 30f, 100f)
        editor.type("Grid")
        controller.togglePageGrid()
        controller.togglePageGrid()
        assertEquals(2, page.texts.size)

        // The drawing mode turned off
        editor.startNewText(0, 30f, 150f)
        editor.type("Mode")
        if (controller.isDrawingMode) controller.toggleDrawingMode()
        assertFalse(editor.isEditing)
        assertEquals(3, page.texts.size)

        // Another text opened
        if (!controller.isDrawingMode) controller.toggleDrawingMode()
        editor.startNewText(0, 30f, 200f)
        editor.type("Before")
        editor.startEditing(page.texts.first(), 0)
        assertEquals(listOf("Kept", "Grid", "Mode", "Before"), page.texts.map { it.text })
    }

    @Test
    fun aTextIsSavedWithItsStyleAndTheHeightOfItsLines() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val editor = controller.textEditorManager

        editor.startDrawnText(0, Rect(10f, 50f, 80f, 60f))
        editor.type("x".repeat(40))
        editor.changeFontSize(24f)
        editor.changeColor(0xFF1A73E8.toInt())
        editor.changeAlignment(TextAlignment.CENTER)
        editor.resizeBy(-20f, leftEdge = false)
        editor.finishEditing()

        val text = page.texts.single()
        assertEquals(24f, text.fontSize)
        assertEquals(0xFF1A73E8.toInt(), text.color)
        assertEquals(TextAlignment.CENTER, text.alignment)
        assertEquals(50f, text.width)
        // 40 characters of 12 mm on 50 mm: 10 lines of 12 mm
        assertEquals(120f, text.height)
        assertEquals(TextDefaults(24f, 0xFF1A73E8.toInt(), TextAlignment.CENTER), controller.textDefaults, "the style used becomes the one of the tool")
    }

    @Test
    fun theOthersSeeTheTextAsItIsWritten() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        fun draft() = controller.activity.value?.text

        editor.startNewText(0, 10f, 50f)
        assertEquals("", draft()?.text)
        editor.type("Hi")
        assertEquals("Hi", draft()?.text)
        editor.moveBy(5f, 0f)
        assertEquals(15f, draft()?.x)
        editor.changeColor(0xFFFF0000.toInt())
        assertEquals(0xFFFF0000.toInt(), draft()?.color)
        editor.finishEditing()
        assertNull(draft())
    }

    @Test
    fun theFormattingChangesTheSelection() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 50f)
        editor.updateValue(TextFieldValue("Area pi r^2", TextRange(5, 11)))

        editor.toggle(RichTextEditing.Format.FORMULA)
        assertEquals("Area \$pi r^2\$", editor.value.text)
        editor.toggle(RichTextEditing.Format.FORMULA)
        assertEquals("Area pi r^2", editor.value.text)
        editor.toggleHeading()
        assertEquals("# Area pi r^2", editor.value.text)
    }

    @Test
    fun theBoxStaysOnItsPage() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 50f)

        editor.moveBy(-1000f, 5000f)
        assertEquals(5f - editor.boxWidth, editor.boxX, "a part stays on the page")
        assertEquals(page.heightMm - 5f, editor.boxY)

        val right = editor.boxX + editor.boxWidth
        editor.resizeBy(1000f, leftEdge = true)
        assertEquals(20f, editor.boxWidth, "never narrower than its minimum")
        assertEquals(right, editor.boxX + editor.boxWidth, 1e-3f, "the other edge stays")
    }

    @Test
    fun aResizedTextIsMeasuredAgain() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 50f)
        editor.type("x".repeat(40))
        editor.finishEditing()
        val text = controller.document.pages[0].texts.single()

        controller.selectionManager.selectText(0, text)
        // Half the width: twice the lines
        val before = assertNotNull(controller.measureText(text)).height
        controller.selectionManager.beginTransformation()
        controller.selectionManager.resizeText(Offset(text.x + text.width / 2f, text.y), isRightEdge = true)
        assertEquals(controller.measureText(text)?.height, text.height)
        assertTrue(text.height > before)
        controller.selectionManager.applyTransformation()
        assertEquals(assertNotNull(controller.measureText(text)).height, text.height, "the change keeps the height measured")

        // A bigger size wraps the lines again on the same width
        controller.selectionManager.updateSelectedTextsFontSize(32f)
        assertEquals(32f, text.fontSize)
        assertEquals(assertNotNull(controller.measureText(text)).height, text.height)
    }

    @Test
    fun aTextHasTheSameFrameSelectedAndWritten() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        controller.density = 2f
        val editor = controller.textEditorManager
        editor.startNewText(0, 30f, 50f)
        editor.type("Framed")
        editor.finishEditing()
        val text = controller.document.pages[0].texts.single()

        controller.selectionManager.selectText(0, text)
        val selected = assertNotNull(controller.selectionManager.currentSelection?.let { SelectionFrame.compute(controller, it) })
        controller.selectionManager.clearSelection()
        editor.startEditing(text, 0)
        val written = assertNotNull(SelectionFrame.ofEditedText(controller))

        for ((a, b) in selected.corners.zip(written.corners)) {
            assertEquals(a.x, b.x, 0.01f)
            assertEquals(a.y, b.y, 0.01f)
        }
        // The text being written is resized and moved, not turned nor scaled
        assertNotNull(selected.rotationHandle)
        assertNull(written.rotationHandle)
        assertFalse(written.scalesFromCorners)
        val radius = FrameStyle.hitRadiusPx(PointerType.Touch, controller.density)
        assertEquals(SelectionFrame.Handle.LEFT, written.handleAt(written.sideHandles[0] + Offset(radius - 1f, 0f), radius))
        assertEquals(SelectionFrame.Handle.RIGHT, written.handleAt(written.sideHandles[1], radius))
        assertNull(written.handleAt(written.center, radius))
        assertEquals(44f, radius, "a target of 44 dp for a finger")
    }

    @Test
    fun theSizesGoFromFourToTwoHundredEightyEightPoints() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(18f, TextSizes.larger(16f))
        assertEquals(14f, TextSizes.smaller(16f))
        assertEquals(12f, TextSizes.larger(11.5f), "a typed size steps to the next preset")
        assertEquals(TextSizes.MAX, TextSizes.larger(288f))
        assertEquals(TextSizes.MIN, TextSizes.smaller(6f))

        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 50f)
        editor.changeFontSize(1000f)
        assertEquals(288f, editor.fontSize)
        editor.changeFontSize(1f)
        assertEquals(4f, editor.fontSize)
        editor.changeFontSize(200f)
        editor.type("Big")
        editor.finishEditing()

        // Scaled by its corners, never beyond the largest size
        val text = controller.document.pages[0].texts.single()
        controller.selectionManager.selectText(0, text)
        controller.selectionManager.beginTransformation()
        controller.selectionManager.scaleBy(3f)
        controller.selectionManager.applyTransformation()
        assertEquals(288f, text.fontSize, 0.01f)
    }

    @Test
    fun theFormattingAppliesToTheTextWrittenOrToTheTextsSelected() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        assertNull(controller.textFormatTarget)

        editor.startNewText(0, 10f, 50f)
        assertSame(editor, controller.textFormatTarget)
        editor.type("One")
        editor.finishEditing()
        val text = controller.document.pages[0].texts.single()

        controller.selectionManager.selectText(0, text)
        val selected = assertNotNull(controller.textFormatTarget)
        assertFalse(selected.formatsCharacters)
        selected.changeAlignment(TextAlignment.END)
        selected.changeColor(0xFF188038.toInt())
        assertEquals(TextAlignment.END, text.alignment)
        assertEquals(TextAlignment.END, selected.alignment)
        assertEquals(0xFF188038.toInt(), text.color)

        controller.undo()
        assertEquals(TextAlignment.END, text.alignment, "the color undone first")
        controller.undo()
        assertEquals(TextAlignment.START, text.alignment)
    }

    @Test
    fun aTappedTextHasTheWidthOfItsText() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val editor = controller.textEditorManager

        editor.startNewText(0, 30f, 50f)
        assertTrue(editor.autoWidth)
        // 2 characters of 8 mm, and room for the caret
        editor.type("Hi")
        assertEquals(17f, editor.boxWidth, 0.01f)
        // Up to the margin of the page, then the lines wrap
        editor.type("x".repeat(100))
        assertEquals(page.widthMm - 30f - 5f, editor.boxWidth, 0.01f)
        editor.finishEditing()
        val text = page.texts.single()
        assertTrue(text.autoWidth)

        // Its width set by hand: it stays
        editor.startEditing(text, 0)
        editor.resizeBy(-40f, leftEdge = false)
        assertFalse(editor.autoWidth)
        editor.type("Short")
        editor.finishEditing()
        assertFalse(text.autoWidth)
        assertEquals(page.widthMm - 35f - 40f, text.width, 0.01f)
    }

    @Test
    fun aDrawnTextKeepsItsWidth() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startDrawnText(0, Rect(10f, 20f, 90f, 40f))
        assertEquals(10f, editor.boxX)
        assertEquals(20f, editor.boxY)
        assertFalse(editor.autoWidth)
        editor.type("Hi")
        assertEquals(80f, editor.boxWidth)
        editor.finishEditing()
        assertEquals(80f, controller.document.pages[0].texts.single().width)
    }

    @Test
    fun aSelectedTextResizedByItsSidesStopsFollowingItsText() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 50f)
        editor.type("Some words here")
        editor.finishEditing()
        val text = controller.document.pages[0].texts.single()

        controller.selectionManager.selectText(0, text)
        controller.selectionManager.beginTransformation()
        controller.selectionManager.resizeText(Offset(text.x + 60f, text.y), isRightEdge = true)
        controller.selectionManager.applyTransformation()
        assertFalse(text.autoWidth)
        controller.undo()
        assertTrue(text.autoWidth, "one change, undone at once")
    }

    @Test
    fun aTextCopiedElsewhereIsPastedAsANewText() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        controller.textEditorManager.pasteText("A note **from** elsewhere")
        val text = page.texts.single()
        assertEquals("A note **from** elsewhere", text.text)
        assertTrue(text.autoWidth)
        assertTrue(text.x >= 5f && text.x + text.width <= page.widthMm - 5f)
        assertSame(text, controller.selectionManager.currentSelection?.texts?.single(), "selected, to be moved")
        controller.undo()
        assertTrue(page.texts.isEmpty())
    }

    @Test
    fun theTextsSelectedGiveTheirTextFromTheTopDown() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 120f)
        editor.type("Second")
        editor.finishEditing()
        editor.startNewText(0, 10f, 40f)
        editor.type("First")
        editor.finishEditing()
        controller.selectionManager.selectWithLasso(0, floatArrayOf(0f, 0f, 200f, 0f, 200f, 200f, 0f, 200f))
        assertEquals("First\n\nSecond", controller.selectionManager.selectedPlainText)
    }

    @Test
    fun aTextFollowingItsWidthGrowsFromTheSideOfItsAlignment() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager

        // Centered: its middle stays where it was tapped
        controller.updateTextDefaults(TextDefaults(alignment = TextAlignment.CENTER))
        editor.startNewText(0, 100f, 50f)
        assertEquals(100f, editor.boxX + editor.boxWidth / 2f, 0.01f)
        editor.type("Hi")
        assertEquals(100f, editor.boxX + editor.boxWidth / 2f, 0.01f)
        editor.type("Hello there")
        assertEquals(100f, editor.boxX + editor.boxWidth / 2f, 0.01f)
        editor.finishEditing()

        // At the end: its right edge stays
        controller.updateTextDefaults(TextDefaults(alignment = TextAlignment.START))
        editor.startNewText(0, 100f, 100f)
        editor.type("Hi")
        val right = editor.boxX + editor.boxWidth
        editor.changeAlignment(TextAlignment.END)
        editor.type("Hello there")
        assertEquals(right, editor.boxX + editor.boxWidth, 0.01f)
    }
}
