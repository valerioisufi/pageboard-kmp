package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Where an application keeps the proposals of the clients and what the models changed, so that they
 * outlive it (Protected Variations): one text, written by [ExternalAgents] and given back as it was.
 * Without one they are in memory only. See `docs/plans/agent-ergonomics.md`.
 *
 * What is kept is a stored format of its own, with a version: a text this version does not understand
 * is never written over, it is put aside ([setAside]).
 */
public interface AgentJournal {
    /** The text kept, or `null` when there is none. */
    public suspend fun load(): String?

    /** Keeps [text] in place of what was kept, all of it or nothing. */
    public suspend fun save(text: String)

    /** Moves what is kept to another name, as it is: it was not understood, and is not to be lost. */
    public suspend fun setAside()

    public companion object {
        /** The longest text read back, in characters. */
        public const val MAX_LENGTH: Int = 256 * 1024 * 1024
    }
}

/**
 * What a journal holds, read back.
 *
 * @param undos how the batches still to undo are undone, by their id.
 * @param unread the entries that were not understood, to be written back as they are.
 */
internal class JournalContent(
    val proposals: List<Proposal> = emptyList(),
    val library: List<LibraryProposal> = emptyList(),
    val batches: List<AgentBatch> = emptyList(),
    val undos: Map<String, BatchUndo> = emptyMap(),
    val unread: JournalUnread = JournalUnread()
)

/**
 * What a journal held that this version did not understand: whole entries, and the batches whose way
 * back was not read ([undos]: by the id of the batch).
 */
internal class JournalUnread(
    val proposals: List<JsonElement> = emptyList(),
    val library: List<JsonElement> = emptyList(),
    val activity: List<JsonElement> = emptyList(),
    val undos: Map<String, JsonElement> = emptyMap()
)

/**
 * The text of a journal (Pure Fabrication): version [VERSION], one JSON object.
 *
 * ```
 * {"version": 1, "proposals": [...], "library": [...], "activity": [...]}
 * ```
 *
 * It is read as what a file may hold, not as what this class wrote: bounded, its nesting checked, each
 * entry by itself, each item through the limits of a proposal again. An entry that is not understood
 * is skipped and kept to be written back; a text that is not understood as a whole answers `null`.
 */
internal object JournalCodec {
    const val VERSION = 1

    private val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
    }

    /** The changes of a document, as the stores write the operations. */
    private val changes = Json

    fun encode(
        proposals: List<Proposal>,
        library: List<LibraryProposal>,
        batches: List<AgentBatch>,
        undos: (String) -> BatchUndo?,
        unread: JournalUnread
    ): String = buildJsonObject {
        put("version", VERSION)
        put("proposals", buildJsonArray {
            unread.proposals.forEach { add(it) }
            proposals.forEach { add(json.encodeToJsonElement(StoredProposal.serializer(), it.stored())) }
        })
        put("library", buildJsonArray {
            unread.library.forEach { add(it) }
            library.forEach { add(json.encodeToJsonElement(StoredLibraryProposal.serializer(), it.stored())) }
        })
        put("activity", buildJsonArray {
            unread.activity.forEach { add(it) }
            for (batch in batches) {
                val undo = undos(batch.id)
                // A way back that was not read stays as it was written
                val kept = unread.undos[batch.id]?.takeIf { undo == null && !batch.undone }
                val stored = json.encodeToJsonElement(StoredBatch.serializer(), batch.stored(undo)) as JsonObject
                add(if (kept == null) stored else JsonObject(stored + ("undo" to kept)))
            }
        })
    }.toString()

    /** What [text] holds, or `null` when it is not a journal of this version. */
    fun decode(text: String, limits: ProposalLimits = ProposalLimits()): JournalContent? {
        if (text.length > AgentJournal.MAX_LENGTH || !jsonNestsWithin(text, AgentEndpoint.MAX_NESTING)) return null
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        if ((root["version"] as? JsonPrimitive)?.intOrNull != VERSION) return null
        val sections = listOf("proposals", "library", "activity").map { root[it] ?: JsonArray(emptyList()) }
        if (sections.any { it !is JsonArray }) return null
        val (storedProposals, storedLibrary, storedActivity) = sections.map { it as JsonArray }

        val proposals = mutableListOf<Proposal>()
        val unreadProposals = mutableListOf<JsonElement>()
        for (entry in storedProposals) {
            val proposal = read { json.decodeFromJsonElement(StoredProposal.serializer(), entry).proposal() }
                ?.takeIf { proposal -> proposal.entries.isNotEmpty() && read { limits.checkItems(proposal.entries.map { it.item }) } != null }
            if (proposal == null) unreadProposals += entry else proposals += proposal
        }

        val library = mutableListOf<LibraryProposal>()
        val unreadLibrary = mutableListOf<JsonElement>()
        for (entry in storedLibrary) {
            val proposal = read { json.decodeFromJsonElement(StoredLibraryProposal.serializer(), entry).proposal() }
                ?.takeIf { proposal -> read { limits.checkLibrary(proposal.title, proposal.items) } != null }
            if (proposal == null) unreadLibrary += entry else library += proposal
        }

        val batches = mutableListOf<AgentBatch>()
        val undos = mutableMapOf<String, BatchUndo>()
        val unreadActivity = mutableListOf<JsonElement>()
        val unreadUndos = mutableMapOf<String, JsonElement>()
        for (entry in storedActivity) {
            // The batch without its way back first: it is listed even when that is not read
            val fields = entry as? JsonObject
            val batch = fields?.let { read { json.decodeFromJsonElement(StoredBatch.serializer(), JsonObject(it - "undo")).batch() } }
            if (batch == null) {
                unreadActivity += entry
                continue
            }
            batches += batch
            val kept = fields["undo"] ?: continue
            val undo = read { json.decodeFromJsonElement(StoredUndo.serializer(), kept).undo() }
            if (undo == null) unreadUndos[batch.id] = kept else undos[batch.id] = undo
        }
        return JournalContent(proposals, library, batches, undos, JournalUnread(unreadProposals, unreadLibrary, unreadActivity, unreadUndos))
    }

    /** What [block] answers, or `null` when what it reads is not what this version writes. */
    private inline fun <T : Any> read(block: () -> T): T? = try {
        block()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: AgentToolException) {
        null
    }

    private fun Proposal.stored() = StoredProposal(
        id, documentId, client, title,
        entries.map { entry ->
            StoredEntry(entry.item, entry.before, entry.outcome.wireName, entry.placed.map { it.stored() }, entry.page, entry.changed)
        }
    )

    private fun StoredProposal.proposal() = Proposal(
        id, document, client, title,
        entries.map { entry ->
            ProposalEntry(entry.item, entry.before, outcomeOf(entry.outcome), entry.placed.map { it.piece() }, entry.page, entry.changed)
        }
    )

    private fun PlannedPiece.stored() =
        StoredPiece(textId, markdown, pageId, isOnNewPage, box.left, box.top, box.right, box.bottom, fontSize, next, color)

    private fun StoredPiece.piece() = PlannedPiece(id, markdown, page, newPage, Rect(left, top, right, bottom), fontSize, next, color)

    private fun LibraryProposal.stored() = StoredLibraryProposal(id, client, title, items, outcomes.map { it.wireName }, created)

    private fun StoredLibraryProposal.proposal(): LibraryProposal {
        require(outcomes.size == items.size) { "An outcome for each item" }
        return LibraryProposal(id, client, title, items, outcomes.map { outcomeOf(it) }, created)
    }

    private fun AgentBatch.stored(undo: BatchUndo?) = StoredBatch(
        id, documentId, client, title, items, at, undone,
        when (undo) {
            is DocumentUndo -> StoredUndo(change = changes.encodeToString(DocumentChange.serializer(), undo.inverse), stamp = undo.stamp)
            is LibraryUndos -> StoredUndo(steps = undo.steps)
            null -> null
        }
    )

    private fun StoredBatch.batch() = AgentBatch(id, document, client, title, items, at, undone)

    private fun StoredUndo.undo(): BatchUndo = when {
        change != null && stamp != null -> {
            require(jsonNestsWithin(change, AgentEndpoint.MAX_NESTING)) { "A change nested too deep" }
            DocumentUndo(changes.decodeFromString(DocumentChange.serializer(), change), stamp)
        }
        steps != null -> LibraryUndos(steps)
        else -> throw IllegalArgumentException("A way back is a change or steps")
    }

    private fun outcomeOf(name: String): ItemOutcome =
        ItemOutcome.entries.firstOrNull { it.wireName == name } ?: throw IllegalArgumentException("No outcome named $name")
}

@Serializable
private class StoredProposal(
    val id: String,
    val document: String,
    val client: String,
    val title: String,
    val entries: List<StoredEntry>
)

@Serializable
private class StoredEntry(
    val item: ProposalItem,
    val before: String? = null,
    val outcome: String,
    val placed: List<StoredPiece> = emptyList(),
    val page: Int? = null,
    val changed: List<ElementRef> = emptyList()
)

@Serializable
private class StoredPiece(
    val id: String,
    val markdown: String,
    val page: String,
    @SerialName("new_page") val newPage: Boolean,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    @SerialName("font_size") val fontSize: Float,
    val next: String? = null,
    val color: Int
)

@Serializable
private class StoredLibraryProposal(
    val id: String,
    val client: String,
    val title: String,
    val items: List<LibraryItem>,
    val outcomes: List<String>,
    val created: List<String> = emptyList()
)

@Serializable
private class StoredBatch(
    val id: String,
    val document: String? = null,
    val client: String,
    val title: String,
    val items: Int,
    val at: Long,
    val undone: Boolean,
    val undo: StoredUndo? = null
)

/**
 * How a batch is undone: the [change] undoing it on its document, as an operation is stored, with the
 * [stamp] of the operation of the batch; or the [steps] undoing it in the library.
 */
@Serializable
private class StoredUndo(
    val change: String? = null,
    val stamp: Stamp? = null,
    val steps: List<LibraryUndo>? = null
)
