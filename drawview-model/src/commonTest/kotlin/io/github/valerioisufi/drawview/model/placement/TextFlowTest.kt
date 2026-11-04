package io.github.valerioisufi.drawview.model.placement

import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A long text split into blocks and flowed through the free space of a page and onto new pages (see `docs/auto-placement.md`). */
class TextFlowTest {

    private val flow = TextFlow()

    /** Every line 6 mm high, whatever the width. */
    private fun heightOf(text: String, @Suppress("UNUSED_PARAMETER") width: Float) = text.lines().size * 6f

    private fun empty() = PageOccupancy(210f, 297f, emptyList())

    @Test
    fun blocksNeverBreakCodeFormulasOrListItems() {
        val markdown = """
            First paragraph.

            ```kotlin
            val a = 1

            val b = 2
            ```

            $$
            x = 1

            y = 2
            $$

            - an item

              its second paragraph
            - another item

            Last paragraph.
        """.trimIndent()
        val blocks = TextFlow.blocksOf(markdown)
        assertEquals(5, blocks.size, blocks.joinToString("\n---\n"))
        assertTrue(blocks[1].startsWith("```kotlin") && blocks[1].endsWith("```"))
        assertTrue(blocks[2].startsWith("$$") && blocks[2].endsWith("$$"))
        assertTrue(blocks[3].contains("its second paragraph") && blocks[3].contains("another item"))
        assertEquals("Last paragraph.", blocks[4])
    }

    @Test
    fun sectionsGoFromOneHeadingToTheNext() {
        val sections = TextFlow.sectionsOf("Intro.\n\n## One\nFirst.\n\nMore.\n\n```\n# not a heading\n```\n\n### Two\nSecond.")
        assertEquals(listOf("Intro.", "## One\nFirst.\n\nMore.\n\n```\n# not a heading\n```", "### Two\nSecond."), sections)
    }

    @Test
    fun aShortTextIsOnePieceAtTheTopOfTheFreeSpace() {
        val pieces = flow.flow("One.\n\nTwo.\n\nThree.", empty(), empty(), ::heightOf)
        val piece = pieces.single()
        assertEquals(0, piece.newPage)
        assertEquals("One.\n\nTwo.\n\nThree.", piece.text)
        assertEquals(10f, piece.box.left)
        assertEquals(10f, piece.box.top)
        assertEquals(190f, piece.box.width)
    }

    @Test
    fun theTextFillsTheFreeSpaceAroundTheContentThenNewPages() {
        // The top half of the page written
        val first = PageOccupancy(210f, 297f, listOf(Rect(10f, 10f, 200f, 150f)))
        val markdown = (1..60).joinToString("\n\n") { "Paragraph $it." }
        val pieces = flow.flow(markdown, first, empty(), ::heightOf)

        assertTrue(pieces.first().box.top >= 150f)
        assertEquals(0, pieces.first().newPage)
        assertTrue(pieces.any { it.newPage == 1 })
        // Nothing lost, in order
        assertEquals((1..60).map { "Paragraph $it." }, pieces.flatMap { it.text.split("\n\n") })
        for (a in pieces) {
            assertTrue(a.box.bottom <= 297f - 10f + 0.01f, "inside the page: ${a.box}")
            if (a.newPage == 0) assertFalse(a.box.overlaps(Rect(10f, 10f, 200f, 150f)))
            for (b in pieces) if (a !== b && a.newPage == b.newPage) assertFalse(a.box.overlaps(b.box))
        }
    }

    @Test
    fun withoutAFirstPageItGoesOnNewPagesAndABlockTallerThanAPageIsSplit() {
        val tall = (1..80).joinToString("\n") { "line $it" }
        val pieces = flow.flow(tall, null, empty(), ::heightOf)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.newPage >= 1 })
        assertEquals((1..80).map { "line $it" }, pieces.flatMap { it.text.lines() })
    }

    @Test
    fun aFirstPlaceBeginsTheTextThereAndWhatFollowsStaysBelowIt() {
        val taken = PageOccupancy(210f, 297f, listOf(Rect(20f, 150f, 190f, 160f)))
        val first = TextFlow.FirstPlace(BesideAnchor(Rect(20f, 150f, 190f, 160f)), 170f, 60f)
        val markdown = (1..200).joinToString("\n\n") { "Block $it" }

        val pieces = TextFlow().flow(markdown, taken, empty(), first, ::heightOf)

        assertTrue(pieces.first().box.top >= 160f, "below what it is about: ${pieces.first().box}")
        assertTrue(pieces.filter { it.newPage == 0 }.all { it.box.top >= 160f })
        assertTrue(pieces.any { it.newPage > 0 })
    }

    @Test
    fun aFirstPlaceCountsOnlyOnTheFirstPage() {
        val first = TextFlow.FirstPlace(BesideAnchor(Rect(20f, 150f, 190f, 160f)), 170f, 60f)

        val pieces = TextFlow().flow("Alone", null, empty(), first, ::heightOf)

        assertEquals(1, pieces.single().newPage)
        assertEquals(10f, pieces.single().box.top)
    }
}
