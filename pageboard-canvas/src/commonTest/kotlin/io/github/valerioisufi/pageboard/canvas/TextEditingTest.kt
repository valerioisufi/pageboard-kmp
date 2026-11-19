package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.canvas.FrameStyle
import io.github.valerioisufi.pageboard.canvas.SelectionFrame
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.canvas.controller.TextEditorManager
import io.github.valerioisufi.pageboard.canvas.geometry.toCompose
import io.github.valerioisufi.pageboard.canvas.render.tile.TextBlockSize
import io.github.valerioisufi.pageboard.canvas.render.tile.TextLineMetrics
import io.github.valerioisufi.pageboard.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.pageboard.canvas.text.RichTextEditing
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.change.ChangeObserver
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.change.toData
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.text.TextDefaults
import io.github.valerioisufi.pageboard.model.text.TextSizes
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

    /** Lines 0.5 mm per point high; a character [wide] mm per point wide, wrapped on the width. */
    private class FakeTextAdapter(private val wide: Float = 0.5f) : TextTileAdapter {
        override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, paperColor: Int) {}

        override fun measure(text: DrawText): TextBlockSize {
            val charWidth = text.fontSize * wide
            val lines = ceil(text.text.length * charWidth / text.width).coerceAtLeast(1f)
            return TextBlockSize(minOf(text.width, text.text.length * charWidth), lines * text.fontSize * 0.5f)
        }

        override fun lineMetrics(fontSize: Float) = TextLineMetrics(fontSize * 0.5f, fontSize * 0.4f)
    }

    private fun controller(scope: CoroutineScope): DrawViewController {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = scope)
        controller.viewport.setSize(800f, 1200f)
        controller.tileManager.tileRenderer.textAdapter = FakeTextAdapter()
        return controller
    }

    private fun TextEditorManager.type(text: String) = updateValue(TextFieldValue(text, TextRange(text.length)))

    @Test
    fun theTextsAreAsHighAsTheirLinesWithoutAChange() = runTest(UnconfinedTestDispatcher()) {
        val document = DrawDocument()
        // At 20 points a line is 10 mm high: one box lower than its line, one higher
        val low = DrawText().apply { text = "Code:\n```\nval a = 1\n```"; width = 400f; height = 3f; fontSize = 20f }
        val high = DrawText().apply { text = "plain"; width = 400f; height = 30f; fontSize = 20f }
        document.pages[0].add(low)
        document.pages[0].add(high)
        val controller = DrawViewController(document = document, coroutineScope = backgroundScope)
        var changes = 0
        controller.session.addObserver(ChangeObserver { changes++ })
        controller.textBoxes.dispatcher = UnconfinedTestDispatcher(testScheduler)
        controller.textAdapter = FakeTextAdapter()

        val page = controller.document.pages[0]
        assertEquals(10f, page.texts.single { it.id == low.id }.height, "the height of its lines")
        assertEquals(10f, page.texts.single { it.id == high.id }.height)
        assertFalse(controller.historyManager.canUndo, "nothing the user did")
        assertEquals(0, changes, "nothing changed in the document")
    }

    @Test
    fun aChangedTextIsAsHighAsItsNewLines() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val text = DrawText().apply { text = "one line"; width = 100f; height = 10f; fontSize = 20f }
        page.add(text)

        // Three lines of 100 mm (10 mm a character), the height it carries left behind
        controller.session.submit(UpdateTexts(page.id, listOf(text.toData().copy(text = "x".repeat(25)))))
        assertEquals(30f, page.texts.single().height)
    }

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
    fun aTapJustPastABlockOfCodeAtAnEndOfTheTextAddsALineByIt() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val ending = "Above\n```\nval a = 1\n```"
        editor.startEditing(DrawText().apply { x = 10f; y = 20f; width = 60f; height = 30f; text = ending }, 0)
        editor.reportLayoutHeight(30f)
        // Within the width, 5 mm below: a line after the block, the caret on it
        assertTrue(editor.lineBesideBlockAt(40f, 55f, reachMm = 8f))
        assertEquals(TextFieldValue("$ending\n", TextRange(ending.length + 1)), editor.value)
        // No block ends the text now; too far, out of its width, inside: nothing
        editor.updateValue(TextFieldValue(ending), smartNewline = false)
        assertFalse(editor.lineBesideBlockAt(40f, 60f, reachMm = 8f), "too far")
        assertFalse(editor.lineBesideBlockAt(75f, 55f, reachMm = 8f), "beside the box")
        assertFalse(editor.lineBesideBlockAt(40f, 40f, reachMm = 8f), "in the box")
        assertFalse(editor.lineBesideBlockAt(40f, 15f, reachMm = 8f), "above it, no block starting the text")
        assertEquals(ending, editor.value.text)

        // Above a text starting with a block: a line before it
        val starting = "```\nval a = 1\n```\nBelow"
        editor.updateValue(TextFieldValue(starting), smartNewline = false)
        assertTrue(editor.lineBesideBlockAt(40f, 15f, reachMm = 8f))
        assertEquals(TextFieldValue("\n$starting", TextRange(0)), editor.value)
        // An unclosed block takes the text after it: no line
        editor.updateValue(TextFieldValue("Above\n```\nval a = 1"), smartNewline = false)
        assertFalse(editor.lineBesideBlockAt(40f, 55f, reachMm = 8f), "not closed")
    }

    @Test
    fun anOpenedTextPutsTheCaretWhereItIsTouched() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val text = DrawText().apply { x = 10f; y = 20f; width = 40f; height = 10f; this.text = "Hello" }

        editor.startEditing(text, 0, caretAt = text.toLocal(25f, 24f).toCompose())
        assertEquals("Hello", editor.value.text)
        assertEquals(Offset(15f, 4f), editor.takeCaretRequest())
        assertNull(editor.takeCaretRequest(), "given once")
    }

    @Test
    fun aPointOfARotatedTextIsTakenBeforeItsRotation() {
        val text = DrawText().apply { x = 0f; y = 0f; width = 40f; height = 10f; rotation = 90f }
        // Turned around its top-left corner (0, 0): its lines go down, their bottom on the left
        val corner = text.toLocal(0f, 0f)
        assertEquals(0f, corner.x, 1e-3f)
        assertEquals(0f, corner.y, 1e-3f)
        val end = text.toLocal(-10f, 40f)
        assertEquals(40f, end.x, 1e-3f)
        assertEquals(10f, end.y, 1e-3f)
    }

    @Test
    fun aTurnedTextIsScaledAroundTheMiddleOfItsFrame() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        // One line of 10 mm at 20 points, turned by a quarter around its corner (80, 50)
        val text = DrawText().apply { this.text = "abc"; x = 80f; y = 50f; width = 60f; height = 10f; fontSize = 20f; rotation = 90f }
        page.add(text)
        val centerX = text.centerX
        val centerY = text.centerY

        controller.selectionManager.selectText(0, text)
        controller.selectionManager.beginTransformation()
        controller.selectionManager.scaleBy(2f)
        controller.selectionManager.applyTransformation()

        assertEquals(120f, text.width)
        assertEquals(40f, text.fontSize)
        assertEquals(20f, text.height, "its line at 40 points")
        assertEquals(centerX, text.centerX, 1e-3f, "the middle of its frame where it was")
        assertEquals(centerY, text.centerY, 1e-3f)
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
        fun draft() = controller.presence.activity.value?.text

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
        assertTrue(editor.isActive(RichTextEditing.Format.FORMULA))
        editor.toggle(RichTextEditing.Format.FORMULA)
        assertEquals("Area pi r^2", editor.value.text)
        assertFalse(editor.isActive(RichTextEditing.Format.FORMULA))
        editor.toggleHeading()
        assertEquals("# Area pi r^2", editor.value.text)
        assertTrue(editor.inHeading)
        editor.toggleBulletList()
        assertTrue(editor.inBulletList)
        assertFalse(editor.inHeading)
        editor.toggleNumberedList()
        assertTrue(editor.inNumberedList)
        assertFalse(editor.inBulletList)
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
        controller.viewport.density = 2f
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
        val radius = FrameStyle.hitRadiusPx(PointerType.Touch, controller.viewport.density)
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
    fun theFillOfABoxIsChosenKeptAndUndone() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val tint = 0xFFFFF1B8.toInt()
        editor.startNewText(0, 10f, 50f)
        assertTrue(editor.takesBackground)
        editor.type("Filled")
        editor.changeBackground(tint)
        assertEquals(tint, controller.presence.activity.value?.text?.background, "the others see it")
        editor.finishEditing()
        val text = controller.document.pages[0].texts.single()
        assertEquals(tint, text.background)

        // Written again: the fill stays
        editor.startEditing(text, 0)
        assertEquals(tint, editor.background)
        editor.type("Filled again")
        editor.finishEditing()
        assertEquals(tint, text.background)

        // Taken off the texts selected, in one change
        controller.selectionManager.selectText(0, text)
        val selected = assertNotNull(controller.textFormatTarget)
        assertEquals(tint, selected.background)
        selected.changeBackground(null)
        assertNull(text.background)
        controller.undo()
        assertEquals(tint, text.background)
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
    fun aTextFollowingItsWidthKeepsItsRoomAndShowsItsBox() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val editor = controller.textEditorManager
        var changes = 0
        controller.session.addObserver(ChangeObserver { changes++ })

        editor.startNewText(0, 30f, 50f)
        // 2 characters of 8 mm
        editor.type("Hi")
        editor.finishEditing()
        val text = page.texts.single()
        assertEquals(30f, text.x)
        assertEquals(page.widthMm - 30f - 5f, text.width, 0.01f, "the room of its lines, up to the margin")
        assertEquals(17f, text.boxWidth, 0.01f, "its box: its line and room for the caret")
        assertEquals(30f, text.getRotatedBounds().left)
        assertFalse(text.containsPoint(60f, 52f), "the room beyond its line is not the text")

        // Opened on its box; left as it was, nothing changes
        editor.startEditing(text, 0)
        assertEquals(30f, editor.boxX)
        assertEquals(17f, editor.boxWidth, 0.01f)
        val before = changes
        editor.finishEditing()
        assertEquals(before, changes)
    }

    @Test
    fun aWiderLayoutDoesNotWrapALineThatHasRoom() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val editor = controller.textEditorManager
        editor.startNewText(0, 10f, 50f)
        // 20 characters of 8 mm: 160 mm in a room of 195 mm (to the margin of the page)
        editor.type("x".repeat(20))
        editor.finishEditing()
        val text = page.texts.single()
        assertEquals(8f, text.height)

        // Fonts 4% wider: 166.4 mm, still one line (a box of 161 mm would wrap it)
        controller.textBoxes.dispatcher = UnconfinedTestDispatcher(testScheduler)
        controller.textAdapter = FakeTextAdapter(wide = 0.52f)
        assertEquals(8f, text.height, "one line")
        assertEquals(167.4f, text.boxWidth, 0.01f)
    }

    @Test
    fun theBoxOfATextFollowingItsWidthIsOnTheSideOfItsAlignment() {
        fun text(alignment: TextAlignment) = DrawText().apply {
            x = 10f; y = 20f; width = 100f; height = 10f; autoWidth = true; naturalWidth = 29f; this.alignment = alignment
        }
        assertEquals(10f, text(TextAlignment.START).getRotatedBounds().left)
        assertEquals(10f + 35f, text(TextAlignment.CENTER).getRotatedBounds().left)
        assertEquals(10f + 70f, text(TextAlignment.END).getRotatedBounds().left)
        assertEquals(30f, text(TextAlignment.END).boxWidth)
        assertEquals(10f + 85f, text(TextAlignment.END).centerX)
        // Not yet laid out: its room
        assertEquals(100f, text(TextAlignment.CENTER).apply { naturalWidth = Float.NaN }.boxWidth)
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

    @Test
    fun listEditingEnterContinuesAndExits() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 50f, 50f)
        editor.type("- First")
        assertTrue(editor.handleEnter(), "Enter continues bullet list")
        assertEquals("- First\n- ", editor.value.text)
        assertEquals(TextRange(10), editor.value.selection)

        // Enter on empty item exits list
        assertTrue(editor.handleEnter(), "Enter on empty item exits list")
        assertEquals("- First\n", editor.value.text)
        assertEquals(TextRange(8), editor.value.selection)
    }

    @Test
    fun listEditingIndentAndOutdent() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 50f, 50f)
        editor.type("- First\n- Item")
        editor.indent()
        assertEquals("- First\n  - Item", editor.value.text)
        editor.outdent()
        assertEquals("- First\n- Item", editor.value.text)
    }

    @Test
    fun aMarkerTypedStartsAListInItsStyleAndBackspaceTakesItBack() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 50f, 50f)
        editor.updateValue(TextFieldValue("a.", TextRange(2)))
        editor.updateValue(TextFieldValue("a. ", TextRange(3)))
        assertEquals("a.  ", editor.value.text)
        assertEquals(TextRange(4), editor.value.selection)
        // Backspace right after: as it was typed, and text
        editor.updateValue(TextFieldValue("a. ", TextRange(3)))
        assertEquals("a. ", editor.value.text)
        editor.updateValue(TextFieldValue("a. x", TextRange(4)))
        assertEquals("a. x", editor.value.text, "it stays text")
        // Going on: an item, and Enter its next one
        editor.updateValue(TextFieldValue("", TextRange(0)))
        editor.updateValue(TextFieldValue("i)", TextRange(2)))
        editor.updateValue(TextFieldValue("i) ", TextRange(3)))
        editor.updateValue(TextFieldValue("i)  x", TextRange(5)))
        editor.handleEnter()
        assertEquals("i)  x\nii)  ", editor.value.text)
        // A capital letter, or another letter, is a name
        editor.updateValue(TextFieldValue("A.", TextRange(2)))
        editor.updateValue(TextFieldValue("A. ", TextRange(3)))
        assertEquals("A. ", editor.value.text)
        editor.updateValue(TextFieldValue("b.", TextRange(2)))
        editor.updateValue(TextFieldValue("b. ", TextRange(3)))
        assertEquals("b. ", editor.value.text)
    }

    @Test
    fun softKeyboardReturnContinuesAndExitsList() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 50f, 50f)
        editor.type("- Item")
        // Simulating soft keyboard inserting '\n'
        editor.updateValue(TextFieldValue("- Item\n", TextRange(7)))
        assertEquals("- Item\n- ", editor.value.text)
        assertEquals(TextRange(9), editor.value.selection)

        // Simulating soft keyboard inserting '\n' on empty item
        editor.updateValue(TextFieldValue("- Item\n- \n", TextRange(10)))
        assertEquals("- Item\n", editor.value.text)
        assertEquals(TextRange(7), editor.value.selection)
    }

    @Test
    fun theMarkersOfTheListItemsAreNoPlaceForTheCaret() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 50f, 50f)
        editor.type("a\n- Item")
        // The arrow left from the text of the item: to the end of the line before, not into the marker
        editor.updateValue(TextFieldValue("a\n- Item", TextRange(4)))
        editor.updateValue(TextFieldValue("a\n- Item", TextRange(3)))
        assertEquals(TextRange(1), editor.value.selection)
        // Backspace at the start of the text of the item takes its marker away
        editor.updateValue(TextFieldValue("a\n- Item", TextRange(4)))
        editor.updateValue(TextFieldValue("a\n-Item", TextRange(3)))
        assertEquals(TextFieldValue("a\nItem", TextRange(2)), editor.value)
    }

    @Test
    fun typingADelimiterOverTheSelectionSurroundsIt() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 50f, 50f)
        editor.updateValue(TextFieldValue("Hello world", TextRange(6, 11)))
        // First *: italic
        editor.updateValue(TextFieldValue("Hello *", TextRange(7)))
        assertEquals(TextFieldValue("Hello *world*", TextRange(7, 12)), editor.value)
        assertTrue(editor.isActive(RichTextEditing.Format.ITALIC))
        // Second * (replacing the selected "world" at 7..12 in "Hello *world*"): bold
        editor.updateValue(TextFieldValue("Hello ***", TextRange(8)))
        assertEquals(TextFieldValue("Hello **world**", TextRange(8, 13)), editor.value)
        assertTrue(editor.isActive(RichTextEditing.Format.BOLD))
    }

    @Test
    fun aDelimiterComposedByADeadKeyOverTheSelectionSurroundsItWhenCommitted() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 50f, 50f)
        editor.updateValue(TextFieldValue("una parola qui", TextRange(4, 10)))
        // The dead key: the backtick composed in place of the selection, then committed as it is
        editor.updateValue(TextFieldValue("una ` qui", TextRange(5), composition = TextRange(4, 5)))
        editor.updateValue(TextFieldValue("una ` qui", TextRange(5)))
        assertEquals(TextFieldValue("una `parola` qui", TextRange(5, 11)), editor.value)
        // Committed by replacing the composition (as macOS does), the same
        editor.updateValue(TextFieldValue("una parola qui", TextRange(4, 10)))
        editor.updateValue(TextFieldValue("una ~ qui", TextRange(5), composition = TextRange(4, 5)))
        editor.updateValue(TextFieldValue("una ~ qui", TextRange(5), composition = TextRange(4, 5)))
        editor.updateValue(TextFieldValue("una ~ qui", TextRange(5)))
        assertEquals(TextFieldValue("una ~parola~ qui", TextRange(5, 11)), editor.value)
    }

    @Test
    fun aLetterComposedByADeadKeyOverTheSelectionReplacesIt() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 50f, 50f)
        editor.updateValue(TextFieldValue("una parola qui", TextRange(4, 10)))
        editor.updateValue(TextFieldValue("una ` qui", TextRange(5), composition = TextRange(4, 5)))
        editor.updateValue(TextFieldValue("una è qui", TextRange(5)))
        assertEquals(TextFieldValue("una è qui", TextRange(5)), editor.value)
        // A composition from a caret with no selection surrounds nothing
        editor.updateValue(TextFieldValue("una è qui", TextRange(9)))
        editor.updateValue(TextFieldValue("una è qui`", TextRange(10), composition = TextRange(9, 10)))
        editor.updateValue(TextFieldValue("una è qui`", TextRange(10)))
        assertEquals(TextFieldValue("una è qui`", TextRange(10)), editor.value)
    }

    @Test
    fun theUndoOfTheFieldTakesBackADelimiterComposedOverTheSelection() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 50f, 50f)
        editor.updateValue(TextFieldValue("una parola qui", TextRange(4, 10)))
        editor.updateValue(TextFieldValue("una ` qui", TextRange(5), composition = TextRange(4, 5)))
        editor.updateValue(TextFieldValue("una ` qui", TextRange(5)))
        // The field goes back to what it had before the composition
        editor.updateValue(TextFieldValue("una parola qui", TextRange(4, 10)))
        assertEquals(TextFieldValue("una parola qui", TextRange(4, 10)), editor.value)
    }

    @Test
    fun pastingAnAddressOverTheSelectionMakesItALinkAndUndoesAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startNewText(0, 50f, 50f)
        editor.updateValue(TextFieldValue("Read the guide", TextRange(5, 14)))
        editor.updateValue(TextFieldValue("Read https://example.com", TextRange(24)))
        assertEquals(TextFieldValue("Read [the guide](https://example.com)", TextRange(6, 15)), editor.value)
        // BasicTextField undo restores what was before the paste
        editor.updateValue(TextFieldValue("", TextRange(0)))
        assertEquals(TextFieldValue("Read the guide", TextRange(5, 14)), editor.value)
    }
}
