package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.store.DocumentCatalog
import io.github.valerioisufi.drawview.store.OpenDocuments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a model proposes to do to the library: its documents and folders, by meaning. Nothing is
 * deleted for good: what is removed goes to the trash of the library (see `docs/trash.md`).
 */
@Serializable
public sealed interface LibraryItem {
    /** Creates a document of one page named [title] in the folder [folder] (`null`: the root). */
    @Serializable
    @SerialName("create_document")
    public data class CreateDocument(
        val title: String,
        val folder: String? = null,
        @SerialName("width_mm") val widthMm: Float = 210f,
        @SerialName("height_mm") val heightMm: Float = 297f
    ) : LibraryItem

    /** Moves the document [document] into the folder [folder] (`null`: the root). */
    @Serializable
    @SerialName("move_document")
    public data class MoveDocument(val document: String, val folder: String? = null) : LibraryItem

    /** Moves the document [document] to the trash. */
    @Serializable
    @SerialName("trash_document")
    public data class TrashDocument(val document: String) : LibraryItem

    /** Creates a folder named [name] in the folder [parent] (`null`: the root). */
    @Serializable
    @SerialName("create_folder")
    public data class CreateFolder(val name: String, val parent: String? = null) : LibraryItem

    /** Renames the folder [folder]. */
    @Serializable
    @SerialName("rename_folder")
    public data class RenameFolder(val folder: String, val name: String) : LibraryItem

    /** Moves the folder [folder] into the folder [parent] (`null`: the root). */
    @Serializable
    @SerialName("move_folder")
    public data class MoveFolder(val folder: String, val parent: String? = null) : LibraryItem

    /** Moves the folder [folder] to the trash, with all it holds. */
    @Serializable
    @SerialName("trash_folder")
    public data class TrashFolder(val folder: String) : LibraryItem

    /** Puts the item [item] of the trash back where it was. */
    @Serializable
    @SerialName("restore")
    public data class Restore(val item: String) : LibraryItem
}

/** Whether [this] item sends something to the trash. */
internal val LibraryItem.trashes: Boolean get() = this is LibraryItem.TrashDocument || this is LibraryItem.TrashFolder

/**
 * What a client proposed for the library, waiting for the user or decided.
 *
 * @param client the name the client gave itself: for the user to read, not proof of anything.
 * @param outcomes the outcome of each item: stale when the library changed so that it could not be done.
 */
public data class LibraryProposal(
    val id: String,
    val client: String,
    val title: String,
    val items: List<LibraryItem>,
    val outcomes: List<ItemOutcome> = items.map { ItemOutcome.WAITING },
    val created: List<String> = emptyList()
) {
    /** Whether the user has still to decide. */
    public val isWaiting: Boolean get() = outcomes.any { it == ItemOutcome.WAITING }
}

/**
 * The proposals of the clients for the library, in memory, like the ones of the documents
 * ([ProposalBook]).
 */
public class LibraryBook(
    private val maxWaiting: Int = ProposalBook.DEFAULT_MAX_WAITING,
    private val maxDecided: Int = ProposalBook.DEFAULT_MAX_DECIDED
) {
    private val state = MutableStateFlow<List<LibraryProposal>>(emptyList())

    /** The proposals, the oldest first. */
    public val proposals: StateFlow<List<LibraryProposal>> = state.asStateFlow()

    /** The proposal [id], or `null`. */
    public fun find(id: String): LibraryProposal? = state.value.firstOrNull { it.id == id }

    internal fun add(client: String, title: String, items: List<LibraryItem>): LibraryProposal {
        val proposal = LibraryProposal(newElementId(), client, title, items)
        var refused = false
        state.update { current ->
            refused = current.count { it.isWaiting } >= maxWaiting
            if (refused) current else current + proposal
        }
        if (refused) throw AgentToolException(AgentErrorCode.REFUSED, "Too many proposals are waiting for the user: wait until they are reviewed.")
        return proposal
    }

    internal fun resolve(id: String, outcomes: List<ItemOutcome>, created: List<String> = emptyList()) {
        state.update { current ->
            val updated = current.map { if (it.id == id) it.copy(outcomes = outcomes, created = created) else it }
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
}

/**
 * A step undoing an item of the library: [isAsLeft] tells whether what the item did is still as it
 * left it, [undo] puts it back.
 */
internal class LibraryUndo(val isAsLeft: suspend (DocumentCatalog) -> Boolean, val undo: suspend (DocumentCatalog) -> Boolean)

/** The operations applied to the document [id] so far, those not stored yet included; `null` when it cannot be opened. */
internal suspend fun OpenDocuments.versionsOf(id: String): Map<String, Long>? {
    val open = open(id) ?: return null
    try {
        return open.edit { it.versionVector }
    } finally {
        close(open)
    }
}

/** What [LibraryApplier.apply] did: the outcome of each item, the steps undoing the ones done, the documents made. */
internal class AppliedLibrary(val outcomes: List<ItemOutcome>, val undos: List<LibraryUndo>, val created: List<String>)

/**
 * Applies the items of the library one after the other on the catalog of the store (Pure Fabrication),
 * each one with the step undoing it. An item that cannot be done as the library is now (a document
 * gone, a name taken) is stale; the others are done.
 */
internal class LibraryApplier {

    suspend fun apply(documents: OpenDocuments, items: List<LibraryItem>): AppliedLibrary {
        val catalog = documents.store
        val undos = mutableListOf<LibraryUndo>()
        val created = mutableListOf<String>()
        val outcomes = items.map { item ->
            val undo = applyOne(documents, catalog, item, created)
            if (undo == null) ItemOutcome.STALE else ItemOutcome.ACCEPTED.also { undos += undo }
        }
        return AppliedLibrary(outcomes, undos, created)
    }

    private suspend fun applyOne(documents: OpenDocuments, catalog: DocumentCatalog, item: LibraryItem, created: MutableList<String>): LibraryUndo? = when (item) {
        is LibraryItem.CreateDocument -> {
            if (item.folder != null && catalog.folder(item.folder) == null) null
            else if (catalog.documents.first().any { it.folderId == item.folder && it.name == item.title }) null
            else {
                val id = catalog.createDocument(item.title, item.widthMm, item.heightMm, PageBackground.Solid(), item.folder).id
                created += id
                val made = documents.versionsOf(id)
                LibraryUndo(
                    // Out of the trash and not written in since: trashing it loses nothing of the user's
                    isAsLeft = { catalog -> catalog.documents.first().any { it.id == id } && documents.versionsOf(id) == made },
                    undo = { it.trashDocument(id) }
                )
            }
        }
        is LibraryItem.MoveDocument -> {
            val document = catalog.documents.first().firstOrNull { it.id == item.document }
            if (document == null || (item.folder != null && catalog.folder(item.folder) == null) || !catalog.moveDocument(item.document, item.folder)) null
            else LibraryUndo(
                isAsLeft = { it.documents.first().firstOrNull { d -> d.id == item.document }?.let { d -> d.folderId == item.folder } == true },
                undo = { it.moveDocument(item.document, document.folderId) }
            )
        }
        is LibraryItem.TrashDocument -> {
            if (!catalog.trashDocument(item.document)) null
            else LibraryUndo(isAsLeft = { it.inTrash(item.document) }, undo = { it.restore(item.document) != null })
        }
        is LibraryItem.CreateFolder -> {
            if ((item.parent != null && catalog.folder(item.parent) == null) || !catalog.createFolder(item.name, item.parent)) null
            else {
                val id = catalog.folders.first().first { it.parentId == item.parent && it.name == item.name }.id
                LibraryUndo(
                    // Still as made, and empty: trashing it takes nothing else
                    isAsLeft = { catalog ->
                        val folder = catalog.folder(id)
                        folder != null && folder.name == item.name && folder.parentId == item.parent &&
                            catalog.documents.first().none { it.folderId == id } && catalog.folders.first().none { it.parentId == id }
                    },
                    undo = { it.trashFolder(id) }
                )
            }
        }
        is LibraryItem.RenameFolder -> {
            val folder = catalog.folder(item.folder)
            if (folder == null || !catalog.renameFolder(item.folder, item.name)) null
            else LibraryUndo(isAsLeft = { it.folder(item.folder)?.name == item.name }, undo = { it.renameFolder(item.folder, folder.name) })
        }
        is LibraryItem.MoveFolder -> {
            val folder = catalog.folder(item.folder)
            if (folder == null || (item.parent != null && catalog.folder(item.parent) == null) || !catalog.moveFolder(item.folder, item.parent)) null
            else LibraryUndo(
                isAsLeft = { it.folder(item.folder)?.let { f -> f.parentId == item.parent } == true },
                undo = { it.moveFolder(item.folder, folder.parentId) }
            )
        }
        is LibraryItem.TrashFolder -> {
            if (!catalog.trashFolder(item.folder)) null
            else LibraryUndo(isAsLeft = { it.inTrash(item.folder) }, undo = { it.restore(item.folder) != null })
        }
        is LibraryItem.Restore -> {
            val trashed = catalog.trash.first().firstOrNull { it.id == item.item }
            if (trashed == null || catalog.restore(item.item) == null) null
            else LibraryUndo(
                isAsLeft = { !it.inTrash(item.item) },
                undo = { catalog ->
                    if (trashed.kind == io.github.valerioisufi.drawview.store.TrashedItem.Kind.DOCUMENT) catalog.trashDocument(item.item)
                    else catalog.trashFolder(item.item)
                }
            )
        }
    }
}

/**
 * How much the models may send to the trash (Protected Variations): at most [perBatch] items in a
 * proposal and [perMinute] in a minute, whoever proposes them, so that a model in a loop cannot empty
 * the library into the trash before the user looks.
 */
internal class TrashLimiter(
    private val perBatch: Int = 20,
    private val perMinute: Int = 50,
    private val clock: () -> Long = ::currentTimeMillis
) {
    private val mutex = Mutex()
    private val times = ArrayDeque<Long>()

    /** Counts [count] items sent to the trash now; refuses them beyond the limits. */
    suspend fun take(count: Int) {
        if (count == 0) return
        if (count > perBatch) throw AgentToolException(AgentErrorCode.REFUSED, "A proposal sends at most $perBatch items to the trash.")
        mutex.withLock {
            val now = clock()
            while (times.isNotEmpty() && times.first() <= now - 60_000L) times.removeFirst()
            if (times.size + count > perMinute) {
                throw AgentToolException(AgentErrorCode.REFUSED, "At most $perMinute items go to the trash in a minute: wait, or ask the user.")
            }
            repeat(count) { times.addLast(now) }
        }
    }
}

private suspend fun DocumentCatalog.folder(id: String) = folders.first().firstOrNull { it.id == id }

private suspend fun DocumentCatalog.inTrash(id: String) = trash.first().any { it.id == id }
