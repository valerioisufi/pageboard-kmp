package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.CompositeChange
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.RemoveElements
import io.github.valerioisufi.drawview.model.change.TextData
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.toData

/** The blue of what a model wrote, like the notes of the lecture assistant (see `docs/text-import.md`). */
public const val AGENT_TEXT_COLOR: Int = 0xFF1F5AA6.toInt()

/**
 * What [ProposalApplier.apply] did.
 *
 * @param outcomes the outcome of each item, in their order: accepted or stale.
 * @param inverse the change undoing them all, or `null` when nothing changed.
 * @param plan where their text went; `null` when nothing was planned.
 */
public class AppliedProposal(public val outcomes: List<ItemOutcome>, public val inverse: DocumentChange?, public val plan: ProposalPlan? = null)

/**
 * Turns the items accepted by the user into one [CompositeChange]: one operation, one entry of the
 * history, undone at once. Each item is checked against the document as it is now: what changed since
 * the model read it is reported as stale, never overwritten. Where a text goes is the plan of
 * [ProposalPlanner], computed now.
 *
 * Used on the thread of the document.
 */
public class ProposalApplier(engines: AgentEngines = AgentEngines()) {
    private val planner = ProposalPlanner(engines)

    /** The indexes of [items] that would not be applied to [document] as it is now. */
    public fun staleItems(document: DrawDocument, items: List<ProposalItem>): Set<Int> = planner.staleItems(document, items)

    /**
     * Applies [items] to the document of [session], those still valid.
     *
     * @param chosen the places chosen by the user, by index in [items] and in the order of the pieces.
     */
    public fun apply(session: DocumentSession, items: List<ProposalItem>, chosen: Map<Int, List<ChosenPlace>> = emptyMap()): AppliedProposal {
        val document = session.document
        if (session.isReadOnly) return AppliedProposal(items.map { ItemOutcome.STALE }, null)
        val plan = planner.plan(document, items, chosen)
        val changes = mutableListOf<DocumentChange>()
        val outcomes = items.mapIndexed { index, item ->
            val planned = plan.items[index]
            if (planned.isStale) return@mapIndexed ItemOutcome.STALE
            when (item) {
                is ProposalItem.ReplaceText -> {
                    val (page, text) = document.findText(item.text)!!
                    // The versions it was made from are the ones of the text: not a version made apart
                    changes += UpdateTexts(page.id, listOf(text.toData().copy(text = item.markdown)))
                }
                is ProposalItem.RemoveText -> {
                    val (page, _) = document.findText(item.text)!!
                    changes += RemoveElements(page.id, listOf(item.text))
                }
                is ProposalItem.InsertText -> {
                    changes += planned.newPages
                    for (piece in planned.pieces) {
                        val data = TextData(
                            id = piece.textId,
                            text = piece.markdown,
                            x = piece.box.left,
                            y = piece.box.top,
                            width = piece.box.width,
                            color = AGENT_TEXT_COLOR,
                            fontSize = piece.fontSize,
                            next = piece.next
                        )
                        changes += AddElements(piece.pageId, listOf(data))
                    }
                }
                is ProposalItem.AddPage -> changes += planned.newPages
            }
            ItemOutcome.ACCEPTED
        }
        if (changes.isEmpty()) return AppliedProposal(outcomes, null, plan)
        return AppliedProposal(outcomes, session.submit(CompositeChange(changes)), plan)
    }
}
