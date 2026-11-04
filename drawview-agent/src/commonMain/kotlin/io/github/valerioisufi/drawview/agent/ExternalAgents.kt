package io.github.valerioisufi.drawview.agent

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
    public val tools: DocumentTools = DocumentTools(documents, access, proposals, engines, ::received)

    private val applier = ProposalApplier(engines)
    private val planner = ProposalPlanner(engines)
    private val decisions = Mutex()

    /**
     * The user decided about the proposal [proposalId]: the items at [accepted] are applied as one
     * change, the others are rejected. Nothing happens if it is decided already.
     */
    public suspend fun decide(proposalId: String, accepted: Set<Int>): Unit = decisions.withLock {
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
                    val applied = open.edit { session ->
                        engines.editors?.settle(proposal.documentId)
                        applier.apply(session, indexes.map { proposal.entries[it].item })
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
     */
    public suspend fun plan(proposal: Proposal): ProposalPlan? {
        if (!ProposalLimits().canPlan(proposal.entries.map { it.item })) return null
        val open = documents.open(proposal.documentId) ?: return null
        try {
            return open.edit { session -> planner.plan(session.document, proposal.entries.map { it.item }) }
        } finally {
            documents.close(open)
        }
    }

    /**
     * A new [proposal] of the client [connection]: applied at once for a client in direct mode whose
     * document is in an editor (so that it can be undone), shown for its review otherwise.
     */
    private suspend fun received(proposal: Proposal, connection: String) {
        val editors = engines.editors
        if (access.isDirect(connection) && editors != null) {
            val open = documents.open(proposal.documentId)
            val inEditor = try {
                open?.edit { editors.settle(proposal.documentId) } == true
            } finally {
                open?.let { documents.close(it) }
            }
            if (inEditor) {
                decide(proposal.id, proposal.entries.indices.toSet())
                return
            }
        }
        editors?.openForReview(proposal.documentId)
    }
}
