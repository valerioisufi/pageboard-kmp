package io.github.valerioisufi.drawview.core

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.controller.TextMarkupManager
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawPdf
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.PdfTextLayout
import io.github.valerioisufi.drawview.core.model.TextMarkupKind
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.core.model.pdfPointsToPageMm
import io.github.valerioisufi.drawview.core.render.tile.PdfTileAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TextMarkupTest {

    /**
     * An A4 page (in points) with "Hello world" on a first line and "second" on a second one,
     * 12 pt wide characters; the space and the line break are inferred by PDFium (no box).
     */
    private fun layout(): PdfTextLayout {
        val chars = mutableListOf<Int>()
        val boxes = mutableListOf<Float>()
        fun add(char: Char, left: Float, top: Float) {
            chars.add(char.code)
            boxes.addAll(listOf(left, top, left + 12f, top + 24f))
        }
        fun generated(char: Char) {
            chars.add(char.code)
            boxes.addAll(listOf(0f, 0f, 0f, 0f))
        }
        "Hello".forEachIndexed { i, c -> add(c, 72f + i * 12f, 100f) }
        generated(' ')
        "world".forEachIndexed { i, c -> add(c, 138f + i * 12f, 100f) }
        generated('\r')
        generated('\n')
        "second".forEachIndexed { i, c -> add(c, 72f + i * 12f, 130f) }
        return PdfTextLayout(A4_WIDTH_PT, A4_HEIGHT_PT, chars.toIntArray(), boxes.toFloatArray())
    }

    // --- Text layout ---

    @Test
    fun layoutGroupsTheCharactersInLines() {
        val layout = layout()
        assertTrue(layout.hasText)
        assertEquals(19, layout.charCount)

        // The whole first line, the generated space included
        assertEquals(listOf(Rect(72f, 100f, 198f, 124f)), layout.lineRects(0, 11))
        val both = layout.lineRects(6, 19)
        assertEquals(2, both.size)
        assertEquals(Rect(138f, 100f, 198f, 124f), both[0])
        assertEquals(Rect(72f, 130f, 144f, 154f), both[1])

        assertEquals("Hello world\nsecond", layout.text(0, 19))
        assertEquals(0..4, layout.wordRange(2))
        assertEquals(13..18, layout.wordRange(15))
        // The space after "Hello" (inferred by PDFium): the word before it
        assertEquals(0..4, layout.wordRange(5))
    }

    @Test
    fun overlappingLinesAreSplitHalfway() {
        val layout = PdfTextLayout(
            A4_WIDTH_PT, A4_HEIGHT_PT,
            intArrayOf('a'.code, 'b'.code),
            floatArrayOf(10f, 10f, 20f, 30f, 10f, 26f, 20f, 46f)
        )
        val lines = layout.lineRects(0, 2)
        assertEquals(2, lines.size)
        assertEquals(28f, lines[0].bottom)
        assertEquals(28f, lines[1].top)
    }

    @Test
    fun pointerFindsTheNearestCharacter() {
        val layout = layout()
        // Inside the "e" of "Hello"
        assertEquals(1, layout.charIndexNear(90f, 110f, 0f))
        // Too far from any character
        assertEquals(-1, layout.charIndexNear(400f, 500f, 10f))
        // Beyond the end of the first line: its last character, even if the second line is nearer
        assertEquals(10, layout.charIndexForSelection(400f, 110f))
        // Between the lines: the nearest character
        assertEquals(13, layout.charIndexForSelection(75f, 128f))
    }

    // --- Editor ---

    private class FakePdfAdapter(private val layout: PdfTextLayout?) : PdfTileAdapter {
        override fun renderPdfPage(canvas: Canvas, resourcePath: String, pdfPageIndex: Int, pageRect: Rect, clipRect: Rect) {}
        override fun getPageSizesMm(resourcePath: String): List<Pair<Float, Float>> = listOf(210f to 297f)
        override fun getTextLayout(resourcePath: String, pdfPageIndex: Int): PdfTextLayout? = layout
    }

    private fun controllerWithPdf(scope: CoroutineScope, layout: PdfTextLayout? = layout()): DrawViewController {
        val document = DrawDocument()
        document.resources.add(DrawResource("pdf-1", DrawResource.ResourceType.PDF, "/notes/slides.pdf"))
        document.pages[0].pdfs.add(DrawPdf(pdfPageIndex = 0).apply { resourceId = "pdf-1" })
        val controller = DrawViewController(document = document, coroutineScope = scope)
        controller.tileManager.tileRenderer.pdfAdapter = FakePdfAdapter(layout)
        controller.setViewport(800f, 1200f)
        return controller
    }

    /** Screen position of the point ([x], [y]) of the PDF of the first page. */
    private fun DrawViewController.screenOf(x: Float, y: Float): Offset {
        val page = document.pages[0]
        val toScreen = pdfPointsToPageMm(A4_WIDTH_PT, A4_HEIGHT_PT, page.widthMm, page.heightMm)
            .postConcat(assertNotNull(pageMmToScreen(0)))
        return toScreen.mapPoint(x, y)
    }

    @Test
    fun toolMarksTheSweptTextAndUndoRemovesIt() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWithPdf(backgroundScope)
        controller.selectTool(Tool.TEXT_MARKUP)
        val markups = controller.textMarkupManager
        val page = controller.document.pages[0]

        assertTrue(markups.beginMarking(controller.screenOf(140f, 110f), 10f))
        // A tap does not mark anything
        assertNull(markups.finishMarking())
        assertTrue(page.markups.isEmpty())

        assertTrue(markups.beginMarking(controller.screenOf(140f, 110f), 10f))
        markups.updateMarking(controller.screenOf(100f, 140f))
        val marking = assertNotNull(markups.marking)
        assertTrue(marking.isShown)
        val markup = assertNotNull(markups.finishMarking())
        assertNull(markups.marking)

        assertEquals(listOf(markup), page.markups.toList())
        assertEquals("world\nsec", markup.text)
        assertEquals(6, markup.startIndex)
        assertEquals(16, markup.endIndex)
        assertEquals(2, markup.rects.size)
        // The first preset of the tool: a yellow highlight
        assertEquals(TextMarkupKind.HIGHLIGHT, markup.kind)
        assertEquals(0xFFF6E94A.toInt(), markup.color)

        controller.undo()
        assertTrue(page.markups.isEmpty())
        controller.redo()
        assertEquals(listOf(markup), page.markups.toList())
    }

    @Test
    fun noMarkingAwayFromTheText() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWithPdf(backgroundScope)
        controller.selectTool(Tool.TEXT_MARKUP)
        assertFalse(controller.textMarkupManager.beginMarking(controller.screenOf(400f, 600f), 10f))
        assertNull(controller.textMarkupManager.marking)
    }

    @Test
    fun scannedPageTellsThatItHasNoText() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWithPdf(backgroundScope, PdfTextLayout(A4_WIDTH_PT, A4_HEIGHT_PT, IntArray(0), FloatArray(0)))
        val messages = mutableListOf<DrawViewController.Message>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.messages.collect { messages.add(it) } }
        assertFalse(controller.textMarkupManager.beginMarking(controller.screenOf(100f, 110f), 10f))
        assertEquals(listOf(DrawViewController.Message.PDF_PAGE_WITHOUT_TEXT), messages)
    }

    @Test
    fun longPressSelectsAWordThatTheHandlesExtend() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWithPdf(backgroundScope)
        val markups = controller.textMarkupManager

        assertTrue(markups.selectAt(controller.screenOf(100f, 110f), 10f))
        assertEquals("Hello", markups.selectedText)
        val (_, end) = assertNotNull(markups.handleKnobs())
        assertEquals(TextMarkupManager.Handle.END, markups.handleAt(end, 24f))

        markups.beginHandleDrag(TextMarkupManager.Handle.END, end)
        // The pointer drags the knob: the end follows the text above it, from the "o" of "Hello"
        // to the "r" of "world"
        markups.dragHandleTo(end + (controller.screenOf(168f, 112f) - controller.screenOf(132f, 112f)))
        assertEquals("Hello wor", markups.selectedText)

        val markup = assertNotNull(markups.markSelection(TextMarkupKind.UNDERLINE, 0xFFE53935.toInt()))
        assertNull(markups.textSelection)
        assertEquals("Hello wor", markup.text)
        assertEquals(TextMarkupKind.UNDERLINE, markup.kind)
    }

    @Test
    fun selectedMarkCanBeRestyledAndDeleted() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWithPdf(backgroundScope)
        val markups = controller.textMarkupManager
        val page = controller.document.pages[0]
        markups.selectAt(controller.screenOf(100f, 110f), 10f)
        val markup = assertNotNull(markups.markSelection(TextMarkupKind.HIGHLIGHT, 0xFFFFF176.toInt()))

        // A long press on the mark selects it
        assertTrue(markups.selectAt(controller.screenOf(100f, 110f), 10f))
        assertEquals(markup, markups.selectedMarkup?.markup)
        assertEquals("Hello", markups.selectedText)

        markups.restyleSelectedMarkup(TextMarkupKind.STRIKEOUT, 0xFF000000.toInt())
        assertEquals(TextMarkupKind.STRIKEOUT, markup.kind)
        controller.undo()
        assertEquals(TextMarkupKind.HIGHLIGHT, markup.kind)
        assertEquals(0xFFFFF176.toInt(), markup.color)

        markups.selectMarkupAt(controller.screenOf(100f, 110f), 10f)
        markups.deleteSelectedMarkup()
        assertTrue(page.markups.isEmpty())
        controller.undo()
        assertEquals(listOf(markup), page.markups.toList())
    }

    @Test
    fun eraserRemovesTheMarksItTouches() = runTest(UnconfinedTestDispatcher()) {
        val controller = controllerWithPdf(backgroundScope)
        val markups = controller.textMarkupManager
        val page = controller.document.pages[0]
        markups.selectAt(controller.screenOf(100f, 110f), 10f)
        val hello = assertNotNull(markups.markSelection(TextMarkupKind.HIGHLIGHT, 0xFFFFF176.toInt()))
        markups.selectAt(controller.screenOf(100f, 140f), 10f)
        val second = assertNotNull(markups.markSelection(TextMarkupKind.UNDERLINE, 0xFFE53935.toInt()))

        controller.selectTool(Tool.ERASER)
        val erased = controller.eraserManager.eraseAlongSegment(controller.screenOf(80f, 112f), controller.screenOf(90f, 112f))
        controller.eraserManager.commitGesture()
        assertEquals(1, erased)
        assertEquals(listOf(second), page.markups.toList())

        controller.undo()
        assertEquals(listOf(hello, second), page.markups.toList())
    }

    private companion object {
        const val A4_WIDTH_PT = 595.2756f
        const val A4_HEIGHT_PT = 841.8898f
    }
}
