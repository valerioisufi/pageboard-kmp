package io.github.valerioisufi.drawview.agent.ui

import io.github.valerioisufi.drawview.agent.ItemOutcome
import io.github.valerioisufi.drawview.agent.PlannedItem
import io.github.valerioisufi.drawview.agent.PlannedMark
import io.github.valerioisufi.drawview.agent.Proposal
import io.github.valerioisufi.drawview.agent.ProposalEntry
import io.github.valerioisufi.drawview.agent.ProposalItem
import io.github.valerioisufi.drawview.agent.ProposalPlan
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which marks of the proposals are drawn, and how strongly (docs/agent-preview.md): those of the
 * proposals waiting, faint for an item left out, strong for the one chosen in the list.
 */
class ProposalMarksLayerTest {

    private fun mark(kind: PlannedMark.Kind, page: String = "page", onNewPage: Boolean = false) =
        PlannedMark(kind, page, Rect(10f, 10f, 50f, 20f), isOnNewPage = onNewPage)

    private fun item(vararg marks: PlannedMark) = PlannedItem(false, emptyList(), 0, emptyList(), marks = marks.toList())

    private fun proposal(id: String, items: Int, outcome: ItemOutcome = ItemOutcome.WAITING) = Proposal(
        id = id,
        documentId = "document",
        client = "Test client",
        title = "A change",
        entries = List(items) { ProposalEntry(ProposalItem.AddPage(), outcome = outcome) }
    )

    @Test
    fun theMarksOfTheProposalsWaitingAreDrawnAsTheUserChose() {
        val plan = ProposalPlan(
            isMeasured = true,
            items = listOf(
                item(mark(PlannedMark.Kind.CHANGED)),
                item(mark(PlannedMark.Kind.REMOVED)),
                item(mark(PlannedMark.Kind.MOVED), mark(PlannedMark.Kind.CHANGED)),
                item(mark(PlannedMark.Kind.PAGE_CHANGED))
            ),
            pageOrder = listOf("page")
        )
        val selection = ReviewSelection().apply {
            unchecked += 1
            chosen = 2
        }
        val drawn = drawnMarks(listOf(proposal("p", 4)), mapOf("p" to plan), mapOf("p" to selection)) { _, index -> "Item $index" }
        assertEquals(
            listOf(PlannedMark.Kind.CHANGED, PlannedMark.Kind.REMOVED, PlannedMark.Kind.MOVED, PlannedMark.Kind.CHANGED, PlannedMark.Kind.PAGE_CHANGED),
            drawn.map { it.mark.kind }
        )
        assertEquals(listOf(Emphasis.NORMAL, Emphasis.FAINT, Emphasis.CHOSEN, Emphasis.CHOSEN, Emphasis.NORMAL), drawn.map { it.emphasis })
        // Only a page changed as a whole says what happens to it
        assertEquals(listOf(null, null, null, null, "Item 3"), drawn.map { it.label })
    }

    @Test
    fun whatIsDecidedHasNoPlanOrGoesOnANewPageIsNotDrawn() {
        val plan = ProposalPlan(true, listOf(item(mark(PlannedMark.Kind.ADDED), mark(PlannedMark.Kind.ADDED, page = "new", onNewPage = true))), listOf("page", "new"))
        val drawn = drawnMarks(
            listOf(proposal("waiting", 1), proposal("decided", 1, ItemOutcome.ACCEPTED), proposal("unplanned", 1)),
            mapOf("waiting" to plan, "decided" to plan),
            emptyMap()
        )
        assertEquals(listOf("page"), drawn.map { it.mark.pageId })
        assertTrue(drawn.all { it.emphasis == Emphasis.NORMAL })
    }
}
