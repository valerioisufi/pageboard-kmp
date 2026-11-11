package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.newElementId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The proposals of the clients, in memory: waiting for the user, then kept for a while so that their
 * client can read what became of them. Quitting the application drops them: the documents were not
 * touched, and a client can propose again.
 *
 * @param maxWaiting how many may wait at once, whoever they come from.
 * @param maxDecided how many decided ones are kept, the most recent ones.
 */
public class ProposalBook(
    private val maxWaiting: Int = DEFAULT_MAX_WAITING,
    private val maxDecided: Int = DEFAULT_MAX_DECIDED
) {
    private val state = MutableStateFlow<List<Proposal>>(emptyList())

    /** The proposals, the oldest first. */
    public val proposals: StateFlow<List<Proposal>> = state.asStateFlow()

    /** The proposal [id], or `null`. */
    public fun find(id: String): Proposal? = state.value.firstOrNull { it.id == id }

    /** The proposals waiting for a decision on the document [documentId]. */
    public fun waiting(documentId: String): List<Proposal> =
        state.value.filter { it.documentId == documentId && it.isWaiting }

    internal fun add(documentId: String, client: String, title: String, entries: List<ProposalEntry>): Proposal {
        val proposal = Proposal(newElementId(), documentId, client, title, entries)
        var refused = false
        state.update { current ->
            refused = current.count { it.isWaiting } >= maxWaiting
            if (refused) current else current + proposal
        }
        if (refused) throw AgentToolException(AgentErrorCode.REFUSED, "Too many proposals are waiting for the user: wait until they are reviewed.")
        return proposal
    }

    /** Writes the [outcomes] of the items of the proposal [id], all of them, where their text went and what they changed. */
    internal fun resolve(
        id: String,
        outcomes: List<ItemOutcome>,
        placed: Map<Int, List<PlannedPiece>> = emptyMap(),
        changed: Map<Int, List<ElementRef>> = emptyMap()
    ) {
        state.update { current ->
            val updated = current.map { proposal ->
                if (proposal.id != id) return@map proposal
                proposal.copy(entries = proposal.entries.mapIndexed { index, entry -> entry.copy(outcome = outcomes[index], placed = placed[index].orEmpty(), changed = changed[index].orEmpty()) })
            }
            val decided = updated.count { !it.isWaiting }
            if (decided <= maxDecided) updated else {
                var toDrop = decided - maxDecided
                updated.filter { proposal ->
                    val drop = toDrop > 0 && !proposal.isWaiting
                    if (drop) toDrop--
                    !drop
                }
            }
        }
    }

    public companion object {
        public const val DEFAULT_MAX_WAITING: Int = 32
        public const val DEFAULT_MAX_DECIDED: Int = 64
    }
}
