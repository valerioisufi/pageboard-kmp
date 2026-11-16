package io.github.valerioisufi.drawview.model.placement

import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The free space of a page and the places the planner finds in it (see `docs/plans/auto-placement.md`). */
class PlacementPlannerTest {

    private val planner = PlacementPlanner()

    /** A block of [width] preferred, as high as a text of [area] mm² laid out on its width. */
    private fun block(width: Float = 90f, area: Float = 90f * 20f) = PlacementBlock(width) { w -> area / w }

    private fun text(x: Float, y: Float, width: Float, height: Float) = DrawText().apply {
        this.x = x; this.y = y; this.width = width; this.height = height; text = "Notes"
    }

    private fun assertFreeOf(occupancy: PageOccupancy, rect: Rect) {
        for (box in occupancy.boxes) assertFalse(rect.overlaps(box.inflate(planner.clearanceMm - 0.01f)), "$rect covers $box")
        assertTrue(rect.left >= planner.pageMarginMm && rect.top >= planner.pageMarginMm, "$rect in the margin")
        assertTrue(rect.right <= occupancy.widthMm - planner.pageMarginMm + 0.01f && rect.bottom <= occupancy.heightMm - planner.pageMarginMm + 0.01f, "$rect in the margin")
    }

    @Test
    fun emptyPageWithoutAnchorTakesTheTopLeftAtThePreferredWidth() {
        val occupancy = PageOccupancy.of(DrawPage())
        val best = planner.candidates(occupancy, block(), BesideAnchor(null)).first().rect
        assertEquals(10f, best.left)
        assertEquals(10f, best.top)
        assertEquals(90f, best.width)
        assertEquals(20f, best.height)
    }

    @Test
    fun theNoteGoesBelowItsAnchorAlignedWithIt() {
        val page = DrawPage().apply { add(text(20f, 40f, 170f, 30f)) }
        val occupancy = PageOccupancy.of(page)
        val anchor = Rect(20f, 40f, 190f, 70f)
        val best = planner.candidates(occupancy, block(), BesideAnchor(anchor)).first().rect
        assertFreeOf(occupancy, best)
        assertEquals(20f, best.left)
        assertTrue(best.top >= anchor.bottom && best.top <= anchor.bottom + 8f, "just below: $best")
    }

    @Test
    fun theNoteGoesToTheRightOfANarrowAnchorRatherThanAbove() {
        // The writing on the left half, the page below it full
        val page = DrawPage().apply {
            add(text(10f, 100f, 90f, 30f))
            add(text(10f, 135f, 190f, 150f))
        }
        val occupancy = PageOccupancy.of(page)
        val best = planner.candidates(occupancy, block(width = 80f), BesideAnchor(Rect(10f, 100f, 100f, 130f))).first().rect
        assertFreeOf(occupancy, best)
        assertTrue(best.left >= 100f && best.top < 130f, "to the right: $best")
    }

    @Test
    fun aNarrowerBoxIsUsedWhenThePreferredWidthDoesNotFit() {
        // A free column 50 mm wide between two blocks
        val page = DrawPage().apply {
            add(text(0f, 0f, 70f, 297f))
            add(text(130f, 0f, 80f, 297f))
        }
        val occupancy = PageOccupancy.of(page)
        val best = assertNotNull(planner.candidates(occupancy, block(), BesideAnchor(null)).firstOrNull()).rect
        assertFreeOf(occupancy, best)
        assertTrue(best.width in 30f..54f, "narrowed: $best")
        assertEquals(90f * 20f / best.width, best.height, 0.01f)
    }

    @Test
    fun aFullPageHasNoPlace() {
        val page = DrawPage().apply { add(text(0f, 0f, 210f, 297f)) }
        assertTrue(planner.candidates(PageOccupancy.of(page), block(), BesideAnchor(null)).isEmpty())
    }

    @Test
    fun rotatedElementsTakeTheirTurnedBox() {
        val image = DrawImage().apply { x = 55f; y = 100f; width = 100f; height = 10f; rotation = 90f }
        val occupancy = PageOccupancy.of(DrawPage().apply { add(image) })
        val box = occupancy.boxes.single()
        assertTrue(box.height > 99f && box.width < 11f, "turned: $box")
    }

    @Test
    fun aPdfPageIsTakenByItsLinesOfTextOrWholeWithoutThem() {
        val page = DrawPage().apply { add(DrawPdf()) }
        assertEquals(listOf(page.rect()), PageOccupancy.of(page).boxes)

        // One line of text at the top of a page of the size of the page, in points
        val widthPt = 210f / 25.4f * 72f
        val heightPt = 297f / 25.4f * 72f
        val layout = PdfTextLayout(widthPt, heightPt, intArrayOf('A'.code, 'B'.code), floatArrayOf(72f, 72f, 90f, 90f, 90f, 72f, 108f, 90f))
        val occupancy = PageOccupancy.of(page, pdfText = { layout })
        val line = occupancy.boxes.single()
        assertEquals(25.4f, line.left, 0.1f)
        assertEquals(25.4f, line.top, 0.1f)
        assertEquals(38.1f, line.right, 0.1f)
        val best = planner.candidates(occupancy, block(), BesideAnchor(line)).first().rect
        assertFreeOf(occupancy, best)
    }

    @Test
    fun aRuledPagePutsTheTopOfABlockOnALine() {
        val page = DrawPage(background = PageBackground.Ruled(spacingMm = 8f))
        val best = planner.candidates(PageOccupancy.of(page), block(), BesideAnchor(null)).first().rect
        assertEquals(0f, best.top % 8f, 0.01f)
    }

    @Test
    fun candidatesAreApartAndTheSameEveryTime() {
        val page = DrawPage().apply { add(text(20f, 40f, 170f, 30f)) }
        val occupancy = PageOccupancy.of(page)
        val policy = BesideAnchor(Rect(20f, 40f, 190f, 70f))
        val first = planner.candidates(occupancy, block(), policy)
        val second = planner.candidates(occupancy, block(), policy)
        assertEquals(PlacementPlanner.DEFAULT_MAX_CANDIDATES, first.size)
        assertEquals(first.map { it.rect }, second.map { it.rect })
        for (a in first) for (b in first) {
            if (a !== b) assertTrue(kotlin.math.hypot(a.rect.left - b.rect.left, a.rect.top - b.rect.top) >= planner.separationMm)
        }
    }

    @Test
    fun placeAllPutsNoBlockOverAnother() {
        val page = DrawPage().apply { add(text(20f, 40f, 170f, 30f)) }
        val occupancy = PageOccupancy.of(page)
        val anchor = BesideAnchor(Rect(20f, 40f, 190f, 70f))
        val placed = planner.placeAll(occupancy, List(4) { block() to anchor }).map { assertNotNull(it) }
        for (a in placed) {
            assertFreeOf(occupancy, a)
            for (b in placed) if (a !== b) assertFalse(a.overlaps(b), "$a over $b")
        }
    }

    @Test
    fun excludedElementsAreNotTaken() {
        val note = text(10f, 10f, 190f, 277f)
        val occupancy = PageOccupancy.of(DrawPage().apply { add(note) }, excluded = setOf(note.id))
        assertTrue(occupancy.boxes.isEmpty())
    }

    @Test
    fun aShortNoteGoesBesideItsAnchorInTheMargin() {
        // A line of writing on the left, the right column free
        val page = DrawPage().apply { add(text(10f, 80f, 120f, 12f)) }
        val occupancy = PageOccupancy.of(page)
        val anchor = Rect(10f, 80f, 130f, 92f)
        val best = planner.candidates(occupancy, block(width = 60f, area = 60f * 12f), InMargin(anchor)).first().rect
        assertFreeOf(occupancy, best)
        assertTrue(best.left >= 130f && best.top < anchor.bottom && best.bottom > anchor.top, "beside: $best")
    }

    @Test
    fun anAnnouncementGoesAtTheBottomOfTheFreeSpace() {
        val page = DrawPage().apply { add(text(10f, 10f, 190f, 100f)) }
        val occupancy = PageOccupancy.of(page)
        val best = planner.candidates(occupancy, block(width = 190f, area = 190f * 10f), PageBottom).first().rect
        assertFreeOf(occupancy, best)
        assertEquals(287f, best.bottom, 2.01f)
        assertEquals(10f, best.left)
    }

    @Test
    fun theLargestAreasOfAnEmptyPageAreThePageInsideItsMargins() {
        val areas = FreeSpace(PageOccupancy(210f, 296f, emptyList())).largestAreas(8)
        assertEquals(listOf(Rect(10f, 10f, 200f, 286f)), areas)
    }

    @Test
    fun theLargestAreasGoAroundWhatThePageHoldsAndDoNotOverlap() {
        val taken = Rect(20f, 140f, 200f, 160f)
        val areas = FreeSpace(PageOccupancy(210f, 296f, listOf(taken))).largestAreas(8)

        assertTrue(areas.size >= 2, "$areas")
        assertTrue(areas.zipWithNext().all { (a, b) -> a.width * a.height >= b.width * b.height }, "the largest first: $areas")
        assertTrue(areas.none { it.overlaps(taken) }, "$areas")
        for (a in areas) for (b in areas) if (a !== b) assertFalse(a.overlaps(b), "$a, $b")
        assertTrue(areas.all { it.width >= 20f && it.height >= 20f }, "$areas")
    }

    @Test
    fun aPageTakenWholeHasNoArea() {
        val whole = Rect(0f, 0f, 210f, 296f)
        assertTrue(FreeSpace(PageOccupancy(210f, 296f, listOf(whole))).largestAreas(8).isEmpty())
    }

    @Test
    fun theLargestAreasAreBoundedByTheirCount() {
        assertEquals(1, FreeSpace(PageOccupancy(210f, 296f, listOf(Rect(20f, 140f, 200f, 160f)))).largestAreas(1).size)
    }
}
