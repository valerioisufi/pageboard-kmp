package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The writing a note of the lecture is about, found from the time it was written (see `docs/auto-placement.md`). */
class LectureAnchorsTest {

    private val start = 1_000_000_000L

    private fun DrawPage.write(x: Float, y: Float, width: Float, atMs: Long) = add(
        DrawText().apply {
            this.x = x; this.y = y; this.width = width; height = 8f; text = "notes"
            added = Stamp(start + atMs, 0, "tablet")
        }
    )

    @Test
    fun theBlockWrittenWhenTheNoteWasSaid() {
        val page = DrawPage().apply {
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
        val page = DrawPage().apply {
            write(20f, 20f, 100f, atMs = -3_600_000)
            write(20f, 60f, 100f, atMs = 120_000)
            write(20f, 150f, 100f, atMs = 300_000)
        }
        assertEquals(Rect(20f, 150f, 120f, 158f), LectureAnchors.anchorOf(page, start + 900_000, start, start + 1_800_000))
    }

    @Test
    fun noWritingOfTheLectureNoAnchor() {
        val page = DrawPage().apply { write(20f, 20f, 100f, atMs = -3_600_000) }
        assertNull(LectureAnchors.anchorOf(page, start + 600_000, start, start + 1_800_000))
    }
}
