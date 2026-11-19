package io.github.valerioisufi.pageboard.lecture.ui

import androidx.compose.ui.graphics.Canvas
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.pageboard.lecture.NoteKind
import io.github.valerioisufi.pageboard.lecture.NoteSuggestion
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.geometry.Dimension
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.mm
import io.github.valerioisufi.pageboard.ui.extension.EditorContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The notes placed next to what they are about, in the free space of their page
 * (docs/plans/auto-placement.md); the review inserted as new pages, on the format of the document, which
 * its text is laid out on, whatever the page it follows (docs/plans/new-page-format.md).
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
        assertTrue(NoteInserter(NoLayout).startPlacement(EditorContext(controller), note("a", atMs = 605_000), lecture, onInserted = { inserted = true }, onCancel = {}))

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
    fun theWordsQuotedByTheModelWinOverTheTimeOfTheWriting() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val quoted = controller.write(40f, atMs = 60_000).apply { text = "Teorema di Bolzano" }
        controller.write(200f, atMs = 600_000)
        val note = note("a", atMs = 605_000).copy(anchor = "il teorema di Bolzano")
        assertTrue(NoteInserter(NoLayout).startPlacement(EditorContext(controller), note, lecture, onInserted = {}, onCancel = {}))

        val spot = assertNotNull(controller.placementManager.spots.single())
        assertTrue(spot.box.top >= quoted.y + quoted.height && spot.box.top < quoted.y + 25f, "below the quoted words: ${spot.box}")
    }

    @Test
    fun aQuotedHandwritingIsFoundByItsTextAsRead() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        val stroke = io.github.valerioisufi.pageboard.model.change.StrokeData(
            id = io.github.valerioisufi.pageboard.model.newElementId(),
            points = (0 until 20).map { io.github.valerioisufi.pageboard.model.StrokePoint(x = 20f + it * 5f, y = 120f + (it % 2), pressure = 0.5f, timestampMs = it * 8L) },
            tool = io.github.valerioisufi.pageboard.model.StrokeTool.PRESSURE_PEN,
            size = 0.5f,
            color = 0xFF000000.toInt()
        ).toElement()
        page.add(stroke)
        val key = inkBlocksOf(0, page.id, page.strokes).single().key
        val note = note("a", atMs = 605_000).copy(anchor = "la serie geometrica")
        assertTrue(NoteInserter(NoLayout).startPlacement(EditorContext(controller), note, null, handwriting = mapOf(key to "serie geometrica di ragione q"), onInserted = {}, onCancel = {}))

        val spot = assertNotNull(controller.placementManager.spots.single())
        // A definition: in the margin beside it
        assertTrue(spot.box.left >= stroke.bounds.right && spot.box.top < stroke.bounds.bottom && spot.box.bottom > stroke.bounds.top, "beside the handwriting: ${spot.box}")
    }

    @Test
    fun aNoteTakesTheSizeOfTheTextsTypedOnItsPage() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        controller.write(30f, atMs = 60_000).apply { fontSize = 14f; text = "The notes the student typed at fourteen points" }
        assertTrue(NoteInserter(NoLayout).startPlacement(EditorContext(controller), note("a", atMs = 60_000), lecture, onInserted = {}, onCancel = {}))
        assertEquals(14f, assertNotNull(controller.placementManager.request?.preview).fontSize)
        controller.placementManager.cancel()

        // Nothing typed: the size of a new text
        val empty = controller(backgroundScope)
        empty.updateTextDefaults(empty.textDefaults.copy(fontSize = 12f))
        assertTrue(NoteInserter(NoLayout).startPlacement(EditorContext(empty), note("b", atMs = 60_000), null, onInserted = {}, onCancel = {}))
        assertEquals(12f, assertNotNull(empty.placementManager.request?.preview).fontSize)
    }

    @Test
    fun aMarginNoteIsMadeOneSizeSmallerToStayBesideItsAnchor() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val page = controller.document.pages[0]
        fun typed(x: Float, y: Float, width: Float, height: Float) =
            DrawText().apply { this.x = x; this.y = y; this.width = width; this.height = height; fontSize = 14f; text = "Notes of the student at fourteen points" }.also { page.add(it) }
        val anchor = typed(20f, 100f, 110f, 10f)
        // The column on the right free from 100 to 128 mm only: 28 mm, a note of five lines is 29.6 mm at 14 points
        typed(135f, 10f, 65f, 86f)
        typed(135f, 131f, 65f, 150f)
        val note = NoteSuggestion(id = "m", kind = NoteKind.DEFINITION, title = "Definition", content = "one\ntwo\nthree\nfour", anchor = "student at fourteen")
        assertTrue(NoteInserter(NoLayout).startPlacement(EditorContext(controller), note, null, onInserted = {}, onCancel = {}))

        val spot = assertNotNull(controller.placementManager.spots.single())
        assertEquals(12f, assertNotNull(controller.placementManager.request?.preview).fontSize)
        assertTrue(spot.box.left >= anchor.x + anchor.width && spot.box.top < anchor.y + anchor.height, "beside: ${spot.box}")
    }

    @Test
    fun allTheNotesArePlacedTogetherWithoutCoveringEachOtherAndUndoneAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, pages = 2)
        controller.write(30f, atMs = 60_000)
        // The second page is full: its notes go on a new page after it
        controller.document.pages[1].add(DrawText().apply { width = 210f; height = 297f; text = "Full" })
        val notes = listOf(note("a", 60_000), note("b", 70_000), note("c", 80_000), note("d", 90_000, pageIndex = 1))
        var inserted = emptyList<NoteSuggestion>()
        assertTrue(NoteInserter(NoLayout).startPlacementAll(EditorContext(controller), notes, lecture, "4 notes", onInserted = { inserted = it }, onCancel = {}))

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
    fun theSectionsOfAReviewGoNextToTheirPagesAndTheRestAtTheEnd() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, pages = 3)
        val parts = listOf(2 to "## Second topic\nAbout page 3.", 0 to "## First topic\nAbout page 1.", null to "## Elsewhere\nAbout nothing.")
        assertTrue(NoteInserter(NoLayout).startReviewPlacement(EditorContext(controller), parts, "Review"))
        assertTrue(controller.placementManager.confirm())

        val pages = controller.document.pages
        assertEquals(3, pages.size)
        assertTrue(pages[0].texts.single().text.startsWith("## First topic"))
        assertTrue(pages[1].texts.isEmpty())
        // The last page holds its section, then the part of the end
        assertEquals("## Second topic\nAbout page 3.\n\n## Elsewhere\nAbout nothing.", pages[2].texts.single().text)
    }

    @Test
    fun theReviewAfterAPageOfAnotherFormatTakesTheFormatOfTheDocument() = runTest(UnconfinedTestDispatcher()) {
        val document = DrawDocument(defaultBackground = PageBackground.Ruled())
        val controller = DrawViewController(document = document, coroutineScope = backgroundScope)
        // A slide-sized page of its own background, the one on screen
        controller.pageManager.changeSinglePageTemplate(0, Dimension(254f.mm, 142.875f.mm), PageBackground.Solid())
        // Paragraphs enough for more than one page
        val review = (1..80).joinToString("\n\n") { "Paragraph $it of the review." }

        assertTrue(NoteInserter(NoLayout).startReviewPlacement(EditorContext(controller), review, startPageIndex = 0, label = "Review"))
        assertTrue(controller.placementManager.confirm())

        // The free space of the page shown first, then new pages
        assertTrue(document.pages[0].texts.isNotEmpty())
        val inserted = document.pages.drop(1)
        assertTrue(inserted.size > 1)
        for (page in inserted) {
            assertEquals(210f to 297f, page.widthMm to page.heightMm)
            assertEquals(PageBackground.Ruled(), page.background)
            assertFalse(page.hasCustomBackground)
            assertTrue(page.texts.isNotEmpty())
        }
        // Nothing lost, in order
        val paragraphs = document.pages.flatMap { page -> page.texts.sortedBy { it.y }.flatMap { it.text.split("\n\n") } }
        assertEquals((1..80).map { "Paragraph $it of the review." }, paragraphs)

        controller.undo()
        assertEquals(1, document.pages.size)
        assertTrue(document.pages[0].texts.isEmpty())
    }
}
