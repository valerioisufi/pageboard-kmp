package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.change.ChangeObserver
import io.github.valerioisufi.drawview.model.change.ChangeOrigin
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.store.PreferenceStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The external models of an application (Controller): the access, the proposals and the tools, on
 * the documents the application is working on. One per application, given to its way in and to its
 * review. See `docs/agent-tools.md`.
 *
 * @param documents the one the editor and the synchronization use: the only writer of the store.
 */
public class ExternalAgents(
    private val documents: OpenDocuments,
    private val engines: AgentEngines = AgentEngines(),
    preferences: PreferenceStore? = documents.store as? PreferenceStore
) {
    public val access: AgentAccess = AgentAccess(preferences)
    public val proposals: ProposalBook = ProposalBook()
    public val activity: AgentActivity = AgentActivity()
    public val tools: DocumentTools = DocumentTools(documents, access, proposals, engines, ::received)

    private val applier = ProposalApplier(engines)
    private val planner = ProposalPlanner(engines)
    private val decisions = Mutex()

    /**
     * The user decided about the proposal [proposalId]: the items at [accepted] are applied as one
     * change, the others are rejected. Nothing happens if it is decided already.
     *
     * @param chosen the places the user chose for the pieces of the items, by index of an item.
     */
    public suspend fun decide(proposalId: String, accepted: Set<Int>, chosen: Map<Int, List<ChosenPlace>> = emptyMap()): Unit = decisions.withLock {
        val proposal = proposals.find(proposalId)?.takeIf { it.isWaiting } ?: return
        val indexes = proposal.entries.indices.filter { it in accepted }
        val outcomes = MutableList(proposal.entries.size) { ItemOutcome.REJECTED }
        val placed = mutableMapOf<Int, List<PlannedPiece>>()
        if (indexes.isNotEmpty()) {
            val open = documents.open(proposal.documentId)
            if (open == null) {
                indexes.forEach { outcomes[it] = ItemOutcome.STALE }
            } else {
                try {
                    val (applied, stamp) = open.edit { session ->
                        engines.editors?.settle(proposal.documentId)
                        val places = indexes.withIndex().mapNotNull { (position, index) -> chosen[index]?.let { position to it } }.toMap()
                        var stamp: Stamp? = null
                        val observer = ChangeObserver { committed -> if (committed.origin == ChangeOrigin.LOCAL) stamp = committed.operation.stamp }
                        session.addObserver(observer)
                        try {
                            applier.apply(session, indexes.map { proposal.entries[it].item }, places) to stamp
                        } finally {
                            session.removeObserver(observer)
                        }
                    }
                    val inverse = applied.inverse
                    if (inverse != null && stamp != null) {
                        activity.record(proposal.documentId, proposal.client, proposal.title, indexes.size, inverse, stamp)
                    }
                    indexes.forEachIndexed { position, index ->
                        outcomes[index] = applied.outcomes[position]
                        applied.plan?.items?.get(position)?.let { placed[index] = it.pieces }
                    }
                } finally {
                    documents.close(open)
                }
            }
        }
        proposals.resolve(proposalId, outcomes, placed)
    }

    /** The items of [proposal] that would not be applied as the document is now, to show them. */
    public suspend fun staleItems(proposal: Proposal): Set<Int> {
        val open = documents.open(proposal.documentId) ?: return proposal.entries.indices.toSet()
        try {
            return open.edit { session -> applier.staleItems(session.document, proposal.entries.map { it.item }) }
        } finally {
            documents.close(open)
        }
    }

    /**
     * What [proposal] would do to its document as it is now; `null` when it cannot be opened, or holds
     * too much text to be planned before the user accepts it.
     *
     * @param chosen the places the user chose for the pieces of the items, by index of an item.
     */
    public suspend fun plan(proposal: Proposal, chosen: Map<Int, List<ChosenPlace>> = emptyMap()): ProposalPlan? {
        if (!ProposalLimits().canPlan(proposal.entries.map { it.item })) return null
        val open = documents.open(proposal.documentId) ?: return null
        try {
            return open.edit { session -> planner.plan(session.document, proposal.entries.map { it.item }, chosen) }
        } finally {
            documents.close(open)
        }
    }

    /**
     * Undoes the batch [batchId] of the [activity], whether its document is in an editor or not, when
     * what it changed is as it left it; otherwise nothing is done.
     */
    public suspend fun undo(batchId: String): UndoOutcome = decisions.withLock {
        val batch = activity.find(batchId)?.takeUnless { it.undone } ?: return UndoOutcome.GONE
        val undo = activity.undoOf(batchId) ?: return UndoOutcome.GONE
        val open = documents.open(batch.documentId) ?: return UndoOutcome.UNAVAILABLE
        val outcome = try {
            open.edit { session ->
                when {
                    session.isReadOnly -> UndoOutcome.UNAVAILABLE
                    else -> {
                        // What is being written is finished first: written on the batch, it is a change since
                        engines.editors?.settle(batch.documentId)
                        if (!unchangedSince(session.document, undo.inverse, undo.stamp)) {
                            UndoOutcome.CHANGED
                        } else {
                            session.submit(undo.inverse)
                            UndoOutcome.UNDONE
                        }
                    }
                }
            }
        } finally {
            documents.close(open)
        }
        if (outcome == UndoOutcome.UNDONE) activity.markUndone(batchId)
        outcome
    }

    /**
     * A new [proposal] of the client [connection]: applied at once when the user chose so, its document
     * in an editor or not (it is in the [activity], to be undone); shown for its review otherwise.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun received(proposal: Proposal, connection: String) {
        if (access.grants.value.applyDirectly) {
            decide(proposal.id, proposal.entries.indices.toSet())
            return
        }
        engines.editors?.openForReview(proposal.documentId)
    }
}
