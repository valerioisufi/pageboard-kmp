package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.ui.graphics.Canvas
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.lecture.NoteKind
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.geometry.Dimension
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The notes placed next to what they are about, in the free space of their page
 * (docs/auto-placement.md); the review inserted as new pages, on the format of the document, which
 * its text is laid out on, whatever the page it follows (docs/new-page-format.md).
 */
class NoteInserterTest {

    /** Lays nothing out: the heights are estimated from the lines. */
    private object NoLayout : TextTileAdapter {
        override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, paperColor: Int) {}
    }

    private val start = 1_000_000_000L
    private val lecture = start..(start + 3_600_000)

    private fun note(id: String, atMs: Long, pageIndex: Int = 0) =
        NoteSuggestion(id = id, kind = NoteKind.DEFINITION, title = "Note $id", content = "What the lecturer said.", pageIndex = pageIndex, atMs = atMs)

    private fun controller(scope: kotlinx.coroutines.CoroutineScope, pages: Int = 1): DrawViewController {
        val document = DrawDocument()
        repeat(pages - 1) { document.addPage() }
        return DrawViewController(document = document, coroutineScope = scope).apply { viewport.setSize(800f, 1200f) }
    }

    /** A typed line of the student at [y], written [atMs] after the start of the lecture. */
    private fun DrawViewController.write(y: Float, atMs: Long, pageIndex: Int = 0) = DrawText().apply {
        x = 20f; this.y = y; width = 170f; height = 10f; text = "The student's notes"
        added = Stamp(start + atMs, 0, "tablet")
    }.also { document.pages[pageIndex].add(it) }

    @Test
    fun aNoteIsProposedBelowWhatWasWrittenWhenItWasSaid() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        controller.write(30f, atMs = 60_000)
        val written = controller.write(150f, atMs = 600_000)
        var inserted = false
        assertTrue(NoteInserter(NoLayout).startPlacement(controller, note("a", atMs = 605_000), lecture, onInserted = { inserted = true }, onCancel = {}))

        val spot = assertNotNull(controller.placementManager.spots.single())
        assertEquals(0, spot.pageIndex)
        assertEquals(20f, spot.box.left)
        assertTrue(spot.box.top >= written.y + written.height && spot.box.top < written.y + 25f, "below the writing: ${spot.box}")
        assertTrue(controller.placementManager.confirm())
        assertTrue(inserted)
        val placed = controller.document.pages[0].texts.single { it.text.startsWith("**Note a**") }
        assertEquals(spot.box.left, placed.x)
        assertEquals(spot.box.top, placed.y)
    }

    @Test
    fun allTheNotesArePlacedTogetherWithoutCoveringEachOtherAndUndoneAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, pages = 2)
        controller.write(30f, atMs = 60_000)
        // The second page is full: its notes go on a new page after it
        controller.document.pages[1].add(DrawText().apply { width = 210f; height = 297f; text = "Full" })
        val notes = listOf(note("a", 60_000), note("b", 70_000), note("c", 80_000), note("d", 90_000, pageIndex = 1))
        var inserted = emptyList<NoteSuggestion>()
        assertTrue(NoteInserter(NoLayout).startPlacementAll(controller, notes, lecture, "4 notes", onInserted = { inserted = it }, onCancel = {}))

        val spots = controller.placementManager.spots.map { assertNotNull(it) }
        assertEquals(4, spots.size)
        for (a in spots) for (b in spots) if (a !== b && a.pageIndex == b.pageIndex && a.newPageAfter == b.newPageAfter) assertFalse(a.box.overlaps(b.box))
        assertTrue(spots.last().newPageAfter)
        assertTrue(controller.placementManager.confirm())
        assertEquals(notes, inserted)
        assertEquals(3, controller.document.pages.size)
        assertEquals(4, controller.document.pages[0].texts.size)
        assertEquals(1, controller.document.pages[2].texts.size)

        controller.undo()
        assertEquals(2, controller.document.pages.size)
        assertEquals(1, controller.document.pages[0].texts.size)
    }

    @Test
    fun theReviewAfterAPageOfAnotherFormatTakesTheFormatOfTheDocument() = runTest(UnconfinedTestDispatcher()) {
        val document = DrawDocument(defaultBackground = PageBackground.Ruled())
        val controller = DrawViewController(document = document, coroutineScope = backgroundScope)
        // A slide-sized page of its own background, the one on screen
        controller.pageManager.changeSinglePageTemplate(0, Dimension(254f.mm, 142.875f.mm), PageBackground.Solid())
        // Paragraphs enough for more than one page
        val review = (1..80).joinToString("\n\n") { "Paragraph $it of the review." }

        assertTrue(NoteInserter(NoLayout).insertPages(controller, review, afterPageIndex = 0))

        val inserted = document.pages.drop(1)
        assertTrue(inserted.size > 1)
        for (page in inserted) {
            assertEquals(210f to 297f, page.widthMm to page.heightMm)
            assertEquals(PageBackground.Ruled(), page.background)
            assertFalse(page.hasCustomBackground)
            assertEquals(1, page.texts.size)
        }
    }
}
