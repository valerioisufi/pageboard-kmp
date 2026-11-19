package io.github.valerioisufi.pageboard.model.placement

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.PdfTextLayout
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.newElementId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The sizes of the characters of a page and the size a note takes there (see `docs/plans/placement-text-size.md`). */
class PageTextSizesTest {

    private fun text(content: String, size: Float) = TextElement().apply { text = content; fontSize = size; width = 100f; height = 10f }

    /** A stroke of writing [heightMm] high. */
    private fun stroke(x: Float, heightMm: Float, tool: StrokeTool = StrokeTool.PRESSURE_PEN) = StrokeData(
        id = newElementId(),
        points = listOf(StrokePoint(x, 100f, 0.5f, 0L), StrokePoint(x + 2f, 100f + heightMm, 0.5f, 8L)),
        tool = tool,
        size = 0.3f,
        color = 0xFF000000.toInt()
    ).toElement()

    /** A PDF page of [widthPt] × [heightPt] points with [count] characters [charPt] points high. */
    private fun slide(widthPt: Float, heightPt: Float, count: Int, charPt: Float) = PdfTextLayout(
        widthPt, heightPt, IntArray(count) { 'a'.code },
        FloatArray(count * 4) { i -> val c = i / 4; when (i % 4) { 0 -> 50f + c; 1 -> 100f; 2 -> 50f + c + 0.8f; else -> 100f + charPt } }
    )

    @Test
    fun theTypedSizeIsTheOneOfMostCharacters() {
        val page = Page().apply {
            add(text("# A long heading of the page", 24f))
            add(text("The paragraphs of the student, typed at twelve points, many words.", 12f))
        }
        assertEquals(12f, PageTextSizes.of(page).typed)
        assertNull(PageTextSizes.of(Page().apply { add(text("Short", 12f)) }).typed)
    }

    @Test
    fun theSlideSizeFollowsTheScaleTheSlideIsShownAt() {
        // A slide of 720 × 405 points shown on a page of 254 × 142.875 mm (its size: 1 point = 1 point)
        val page = Page(widthMm = 254f, heightMm = 142.875f)
        assertEquals(18f, PageTextSizes.of(page, slide(720f, 405f, 60, 18f)).slide)
        // The same slide shown on an A4 page in portrait: its width is 210 mm, its characters smaller
        val a4 = Page()
        assertEquals(15f, PageTextSizes.of(a4, slide(720f, 405f, 60, 18f)).slide!!, 0.5f)
        assertNull(PageTextSizes.of(page, slide(720f, 405f, 10, 18f)).slide)
    }

    @Test
    fun theHandwritingSizeComesFromTheMedianHeightOfItsStrokes() {
        val page = Page().apply {
            repeat(12) { add(stroke(10f + it * 5f, heightMm = 3.5f)) }
            // A long line and a highlight do not count
            add(stroke(10f, heightMm = 80f))
            repeat(5) { add(stroke(10f, heightMm = 20f, tool = StrokeTool.HIGHLIGHTER)) }
        }
        // The height of a stroke counts its thickness too
        val height = page.strokes.first().bounds.height
        assertEquals(height / 0.3527f / 0.7f, PageTextSizes.of(page).handwriting!!, 0.01f)
        assertNull(PageTextSizes.of(Page().apply { repeat(3) { add(stroke(10f, 3f)) } }).handwriting)
    }

    @Test
    fun aNoteTakesTheTypedSizeElseTheSlideElseTheHandwritingElseTheDefault() {
        assertEquals(12f, TextSizePolicy.forNote(PageTextSizes(typed = 12f, slide = 28f, handwriting = 20f), default = 16f))
        // 85 % of a body of 20 points: 17, the nearest preset 16
        assertEquals(16f, TextSizePolicy.forNote(PageTextSizes(slide = 20f, handwriting = 10f), default = 11f))
        assertEquals(14f, TextSizePolicy.forNote(PageTextSizes(handwriting = 14.2f), default = 11f))
        assertEquals(11f, TextSizePolicy.forNote(PageTextSizes(), default = 11f))
        // Within 8 and 24 points
        assertEquals(24f, TextSizePolicy.forNote(PageTextSizes(slide = 60f), default = 11f))
        assertEquals(8f, TextSizePolicy.forNote(PageTextSizes(typed = 4f), default = 11f))
        assertEquals(10f, TextSizePolicy.smaller(11f))
        assertEquals(8f, TextSizePolicy.smaller(8f))
    }

    @Test
    fun theSizeOfADocumentIsTheOneOfMostOfItsTypedCharacters() {
        val document = Document()
        document.pages[0].add(text("Notes typed at fourteen points in this notebook.", 14f))
        document.addPage().add(text("Title", 28f))
        assertEquals(14f, PageTextSizes.ofDocument(document))
    }
}
