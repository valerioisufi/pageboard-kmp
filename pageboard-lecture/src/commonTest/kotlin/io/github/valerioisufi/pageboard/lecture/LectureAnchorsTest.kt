package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.PdfTextLayout
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The writing a note of the lecture is about, found from the time it was written (see `docs/plans/auto-placement.md`). */
class LectureAnchorsTest {

    private val start = 1_000_000_000L

    private fun Page.write(x: Float, y: Float, width: Float, atMs: Long) = add(
        TextElement().apply {
            this.x = x; this.y = y; this.width = width; height = 8f; text = "notes"
            added = Stamp(start + atMs, 0, "tablet")
        }
    )

    @Test
    fun theBlockWrittenWhenTheNoteWasSaid() {
        val page = Page().apply {
            write(20f, 20f, 100f, atMs = 60_000)
            // Two lines of one block, written around the 10th minute
            write(20f, 100f, 120f, atMs = 595_000)
            write(20f, 110f, 80f, atMs = 610_000)
            write(20f, 200f, 100f, atMs = 1_200_000)
        }
        assertEquals(Rect(20f, 100f, 140f, 118f), LectureAnchors.anchorOf(page, start + 600_000, start, start + 1_800_000))
    }

    @Test
    fun withoutWritingOfThatMomentTheLastBlockOfTheLecture() {
        val page = Page().apply {
            write(20f, 20f, 100f, atMs = -3_600_000)
            write(20f, 60f, 100f, atMs = 120_000)
            write(20f, 150f, 100f, atMs = 300_000)
        }
        assertEquals(Rect(20f, 150f, 120f, 158f), LectureAnchors.anchorOf(page, start + 900_000, start, start + 1_800_000))
    }

    @Test
    fun noWritingOfTheLectureNoAnchor() {
        val page = Page().apply { write(20f, 20f, 100f, atMs = -3_600_000) }
        assertNull(LectureAnchors.anchorOf(page, start + 600_000, start, start + 1_800_000))
    }

    @Test
    fun theQuoteIsFoundInTheTypedTextTheHandwritingOrTheSlide() {
        val page = Page().apply {
            add(TextElement().apply { x = 20f; y = 30f; width = 150f; height = 10f; text = "Il limite di una funzione in un punto" })
        }
        // A typed text, its words in any case and order
        assertEquals(Rect(20f, 30f, 170f, 40f), LectureAnchors.quoteAnchor(page, "limite di una FUNZIONE"))
        // A block of handwriting, by its text as read
        val handwriting = listOf(Rect(20f, 100f, 120f, 130f) to "teorema di Bolzano: esiste uno zero")
        assertEquals(Rect(20f, 100f, 120f, 130f), LectureAnchors.quoteAnchor(page, "il teorema di Bolzano", handwriting))
        // The text of the slide: the line of the words quoted
        val widthPt = 210f / 25.4f * 72f
        val heightPt = 297f / 25.4f * 72f
        val text = "Derivata della composta"
        val boxes = FloatArray(text.length * 4) { i ->
            val c = i / 4
            when (i % 4) { 0 -> 72f + c * 6f; 1 -> 400f; 2 -> 72f + c * 6f + (if (text[c] == ' ') 0f else 5f); else -> 412f }
        }
        val layout = PdfTextLayout(widthPt, heightPt, text.map { it.code }.toIntArray(), boxes)
        val onSlide = assertNotNull(LectureAnchors.quoteAnchor(page, "la derivata della composta", pdfText = layout))
        assertEquals(400f * 25.4f / 72f, onSlide.top, 0.2f)
        // Too few words in common
        assertNull(LectureAnchors.quoteAnchor(page, "serie geometrica convergente", handwriting, layout))
    }
}
