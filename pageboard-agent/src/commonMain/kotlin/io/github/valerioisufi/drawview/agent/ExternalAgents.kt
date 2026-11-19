package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.change.ChangeObserver
import io.github.valerioisufi.drawview.model.change.ChangeOrigin
import io.github.valerioisufi.drawview.store.DocumentCatalog
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.store.PreferenceStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The external models of an application (Controller): the access, the proposals and the tools, on
 * the documents the application is working on. One per application, given to its way in and to its
 * review. See `docs/plans/agent-tools.md`.
 *
 * @param documents the one the editor and the synchronization use: the only writer of the store.
 * @param journal where the proposals and the activity are kept, to outlive the application; `null`:
 *   in memory only.
 */
public class ExternalAgents(
    private val documents: OpenDocuments,
    private val engines: AgentEngines = AgentEngines(),
    preferences: PreferenceStore? = documents.store as? PreferenceStore,
    private val journal: AgentJournal? = null
) {
    public val access: AgentAccess = AgentAccess(preferences)
    public val proposals: ProposalBook = ProposalBook()
    public val activity: AgentActivity = AgentActivity()
    public val library: LibraryBook = LibraryBook()

    /** The documents and folders of the library, to name them in the review. */
    public val catalog: DocumentCatalog get() = documents.store
    public val tools: DocumentTools = DocumentTools(
        documents, access, proposals, engines, ::received,
        library = library, submitLibrary = ::receivedLibrary, activity = activity, undo = { undo(it) }, ready = ::restore
    )

    private val keeping = Mutex()
    private var restored = journal == null
    private var unread = JournalUnread()

    /**
     * Puts back the proposals and the activity the [journal] kept, once: called by the application when
     * it starts, and before anything else is done here. A journal that is not understood is put aside
     * as it is, and nothing comes back.
     */
    public suspend fun restore(): Unit = keeping.withLock {
        if (restored) return
        restored = true
        val text = journal?.load() ?: return
        val content = JournalCodec.decode(text)
        if (content == null) {
            journal.setAside()
            return
        }
        unread = content.unread
        proposals.restore(content.proposals)
        library.restore(content.library)
        activity.restore(content.batches, content.undos)
    }

    /** Keeps the proposals and the activity as they are now. */
    private suspend fun persist() {
        journal ?: return
        keeping.withLock {
            // Never over what was not read back yet
            if (!restored) return
            journal.save(JournalCodec.encode(proposals.proposals.value, library.proposals.value, activity.batches.value, activity::undoOf, unread))
        }
    }

    private val libraryApplier = LibraryApplier(engines)

    private val applier = ProposalApplier(engines)
    private val planner = ProposalPlanner(engines)
    private val decisions = Mutex()

    /**
     * The user decided about the proposal [proposalId]: the items at [accepted] are applied as one
     * change, the others are rejected. Nothing happens if it is decided already.
     *
     * @param chosen the places the user chose for the pieces of the items, by index of an item.
     */
    public suspend fun decide(proposalId: String, accepted: Set<Int>, chosen: Map<Int, List<ChosenPlace>> = emptyMap()) {
        restore()
        decisions.withLock { decided(proposalId, accepted, chosen) }
        persist()
    }

    private suspend fun decided(proposalId: String, accepted: Set<Int>, chosen: Map<Int, List<ChosenPlace>>) {
        val proposal = proposals.find(proposalId)?.takeIf { it.isWaiting } ?: return
        val indexes = proposal.entries.indices.filter { it in accepted }
        val outcomes = MutableList(proposal.entries.size) { ItemOutcome.REJECTED }
        val placed = mutableMapOf<Int, List<PlannedPiece>>()
        val changed = mutableMapOf<Int, List<ElementRef>>()
        if (indexes.isNotEmpty()) {
            val items = indexes.map { proposal.entries[it].item }
            val copies = CopyItems.copiesOf(documents, items) { access.allows(it) }
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
                            applier.apply(session, items, places, copies) to stamp
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
                        applied.changed.getOrNull(position)?.let { changed[index] = it }
                    }
                } finally {
                    documents.close(open)
                }
            }
        }
        proposals.resolve(proposalId, outcomes, placed, changed)
    }

    /**
     * The user decided about the proposal [proposalId] for the library: the items at [accepted] are
     * done in their order, the others are rejected. Nothing happens if it is decided already.
     */
    public suspend fun decideLibrary(proposalId: String, accepted: Set<Int>) {
        restore()
        decisions.withLock { decidedLibrary(proposalId, accepted) }
        persist()
    }

    private suspend fun decidedLibrary(proposalId: String, accepted: Set<Int>) {
        val proposal = library.find(proposalId)?.takeIf { it.isWaiting } ?: return
        val indexes = proposal.items.indices.filter { it in accepted }
        val outcomes = MutableList(proposal.items.size) { ItemOutcome.REJECTED }
        val applied = libraryApplier.apply(documents, indexes.map { proposal.items[it] })
        indexes.forEachIndexed { position, index -> outcomes[index] = applied.outcomes[position] }
        if (applied.undos.isNotEmpty()) {
            activity.record(null, proposal.client, proposal.title, applied.undos.size, LibraryUndos(applied.undos))
        }
        // What a client made, it may change: allowed with it
        if (!access.grants.value.allDocuments) applied.created.forEach { access.grant(it) }
        library.resolve(proposalId, outcomes, applied.created)
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
    public suspend fun undo(batchId: String): UndoOutcome {
        restore()
        return decisions.withLock { undone(batchId) }.also { persist() }
    }

    private suspend fun undone(batchId: String): UndoOutcome {
        val batch = activity.find(batchId)?.takeUnless { it.undone } ?: return UndoOutcome.GONE
        val undo = when (val kept = activity.undoOf(batchId)) {
            null -> return UndoOutcome.GONE
            is LibraryUndos -> {
                val catalog = documents.store
                // All of it as the batch left it, or nothing is done
                if (!kept.steps.all { it.isAsLeft(documents) }) return UndoOutcome.CHANGED
                kept.steps.asReversed().forEach { it.undo(catalog) }
                activity.markUndone(batchId)
                return UndoOutcome.UNDONE
            }
            is DocumentUndo -> kept
        }
        val documentId = batch.documentId ?: return UndoOutcome.GONE
        val open = documents.open(documentId) ?: return UndoOutcome.UNAVAILABLE
        val outcome = try {
            open.edit { session ->
                when {
                    session.isReadOnly -> UndoOutcome.UNAVAILABLE
                    else -> {
                        // What is being written is finished first: written on the batch, it is a change since
                        engines.editors?.settle(documentId)
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
        return outcome
    }

    /** A new [proposal] for the library: as [received]. */
    private suspend fun receivedLibrary(proposal: LibraryProposal, review: Boolean) {
        // Reviewed in the sheet of the library otherwise
        if (access.grants.value.applyDirectly && !review) decideLibrary(proposal.id, proposal.items.indices.toSet()) else persist()
    }

    /**
     * A new [proposal]: applied at once when the user chose so and its client did not ask for a
     * [review], its document in an editor or not (it is in the [activity], to be undone); shown for
     * its review otherwise.
     */
    private suspend fun received(proposal: Proposal, review: Boolean) {
        if (access.grants.value.applyDirectly && !review) {
            decide(proposal.id, proposal.entries.indices.toSet())
            return
        }
        persist()
        engines.editors?.openForReview(proposal.documentId)
    }
}
