package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.CompositeChange
import io.github.valerioisufi.pageboard.model.change.CopiedContent
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.DocumentSession
import io.github.valerioisufi.pageboard.model.change.PlaceElements
import io.github.valerioisufi.pageboard.model.change.RemoveElements
import io.github.valerioisufi.pageboard.model.change.ReorderElements
import io.github.valerioisufi.pageboard.model.change.TextData
import io.github.valerioisufi.pageboard.model.change.RestyleMarkups
import io.github.valerioisufi.pageboard.model.change.UpdateElementContent
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.change.toData

/** The blue of what a model wrote, like the notes of the lecture assistant (see `docs/plans/text-import.md`). */
public const val AGENT_TEXT_COLOR: Int = 0xFF1F5AA6.toInt()

/**
 * What [ProposalApplier.apply] did.
 *
 * @param outcomes the outcome of each item, in their order: accepted or stale.
 * @param inverse the change undoing them all, or `null` when nothing changed.
 * @param plan where their text went; `null` when nothing was planned.
 * @param changed the elements each item made or changed, as they are after it, with the base to change
 *   them again by; empty for an item that was not applied or only removed.
 */
public class AppliedProposal(
    public val outcomes: List<ItemOutcome>,
    public val inverse: DocumentChange?,
    public val plan: ProposalPlan? = null,
    public val changed: List<List<ElementRef>> = emptyList()
)

/**
 * Turns the items accepted by the user into one [CompositeChange]: one operation, one entry of the
 * history, undone at once. Each item is checked against the document as it is now: what changed since
 * the model read it is reported as stale, never overwritten. Where a text goes is the plan of
 * [ProposalPlanner], computed now.
 *
 * Used on the thread of the document.
 */
public class ProposalApplier(private val engines: AgentEngines = AgentEngines()) {
    private val planner = ProposalPlanner(engines)

    /** The indexes of [items] that would not be applied to [document] as it is now. */
    public fun staleItems(document: DrawDocument, items: List<ProposalItem>): Set<Int> = planner.staleItems(document, items)

    /**
     * Applies [items] to the document of [session], those still valid.
     *
     * @param chosen the places chosen by the user, by index in [items] and in the order of the pieces.
     * @param copies what the items copying from a document copy, taken from it as it is now, by index in
     *   [items]: an item copying with none is stale.
     */
    public fun apply(
        session: DocumentSession,
        items: List<ProposalItem>,
        chosen: Map<Int, List<ChosenPlace>> = emptyMap(),
        copies: Map<Int, CopiedContent> = emptyMap()
    ): AppliedProposal {
        val document = session.document
        if (session.isReadOnly) return AppliedProposal(items.map { ItemOutcome.STALE }, null)
        val plan = planner.plan(document, items, chosen)
        val changes = mutableListOf<DocumentChange>()
        val touched = mutableListOf<List<String>>()
        val outcomes = items.mapIndexed { index, item ->
            val planned = plan.items[index]
            if (planned.isStale) {
                touched += emptyList<String>()
                return@mapIndexed ItemOutcome.STALE
            }
            val from = changes.size
            if (item is ProposalItem.CopyElements || item is ProposalItem.CopyPages) {
                val pasted = copies[index]?.let { CopyItems.changeOf(document, item, it) }
                if (pasted == null) {
                    touched += emptyList<String>()
                    return@mapIndexed ItemOutcome.STALE
                }
                changes += pasted
            }
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
                    val alignment = item.alignment?.let { ElementItems.parseAlignment(it) }
                        ?: item.styleFrom?.let { document.findText(it)?.second?.alignment }
                        ?: TextAlignment.START
                    for (piece in planned.pieces) {
                        val data = TextData(
                            id = piece.textId,
                            text = piece.markdown,
                            x = piece.box.left,
                            y = piece.box.top,
                            width = piece.box.width,
                            color = piece.color,
                            fontSize = piece.fontSize,
                            next = piece.next,
                            alignment = alignment
                        )
                        changes += AddElements(piece.pageId, listOf(data))
                    }
                }
                is ProposalItem.AddPage -> changes += planned.newPages
                is ProposalItem.RemovePage, is ProposalItem.MovePage, is ProposalItem.DuplicatePage,
                is ProposalItem.SetPageFormat, is ProposalItem.RenameDocument, is ProposalItem.SetDocumentDefaults,
                is ProposalItem.RestorePages -> changes += PageItems.changesOf(document, item)
                is ProposalItem.AddShape, is ProposalItem.UpdateShape -> changes += ShapeItems.changesOf(document, item)
                is ProposalItem.AddMarkup, is ProposalItem.RestyleMarkups -> changes += MarkupItems.changesOf(document, item, engines)
                is ProposalItem.CopyElements, is ProposalItem.CopyPages -> {}
                is ProposalItem.ImportPdf -> changes += CopyItems.importChanges(document, item)
                else -> changes += ElementItems.changesOf(document, item)
            }
            touched += changes.subList(from, changes.size).flatMap { it.elementsLeft() }.distinct()
            ItemOutcome.ACCEPTED
        }
        if (changes.isEmpty()) return AppliedProposal(outcomes, null, plan)
        val inverse = session.submit(CompositeChange(changes))
        val shown = document.pages.flatMap { page -> page.elements().filter { it.removed == null } }.associateBy { it.id }
        val changed = touched.map { ids -> ids.mapNotNull { id -> shown[id]?.let { ElementRef(id, it.elementBase()) } } }
        return AppliedProposal(outcomes, inverse, plan, changed)
    }

    /** The ids of the elements this change makes or changes, which are still there after it. */
    private fun DocumentChange.elementsLeft(): List<String> = when (this) {
        is AddElements -> elements.map { it.id }
        is UpdateTexts -> texts.map { it.id }
        is PlaceElements -> placements.map { it.id }
        is ReorderElements -> orders.map { it.id }
        is UpdateElementContent -> listOf(id)
        is RestyleMarkups -> styles.map { it.id }
        is CompositeChange -> this.changes.flatMap { it.elementsLeft() }
        else -> emptyList()
    }
}
