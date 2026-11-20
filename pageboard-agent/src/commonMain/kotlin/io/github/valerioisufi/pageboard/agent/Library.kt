package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.store.DocumentCatalog
import io.github.valerioisufi.pageboard.store.OpenDocuments
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import io.github.valerioisufi.pageboard.model.change.CompositeChange
import io.github.valerioisufi.pageboard.model.change.RemovePage
import io.github.valerioisufi.pageboard.model.change.copyPages
import io.github.valerioisufi.pageboard.model.change.pasteInto
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a model proposes to do to the library: its documents and folders, by meaning. Nothing is
 * deleted for good: what is removed goes to the trash of the library (see `docs/plans/trash.md`).
 */
@Serializable
public sealed interface LibraryItem {
    /**
     * Creates a document of one page named [title] in the folder [folder] (`null`: the root), its pages
     * of [widthMm] by [heightMm] on the [background] given (`plain`, `ruled`, `grid` or `dotted`, with
     * [spacingMm] and [backgroundColor]).
     */
    @Serializable
    @SerialName("create_document")
    public data class CreateDocument(
        val title: String,
        val folder: String? = null,
        @SerialName("width_mm") val widthMm: Float = 210f,
        @SerialName("height_mm") val heightMm: Float = 297f,
        val background: String? = null,
        @SerialName("spacing_mm") val spacingMm: Float? = null,
        @SerialName("background_color") val backgroundColor: String? = null
    ) : LibraryItem {
        /** The background of its pages. */
        internal val pageBackground: PageBackground
            get() = PageItems.backgroundFrom(PageBackground.Solid(), background, spacingMm, backgroundColor)
    }

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

    /**
     * Makes a copy of the document [document] with all its pages, named [title] (`null`: its name
     * with a number), in the folder [folder] (`null`: the folder of the document).
     */
    @Serializable
    @SerialName("duplicate_document")
    public data class DuplicateDocument(val document: String, val title: String? = null, val folder: String? = null) : LibraryItem

    /**
     * Brings the notes of the archive of the application at [path] (`.pageboard`) into a new folder of
     * the folder [folder] (`null`: the root), as new documents.
     */
    @Serializable
    @SerialName("import_archive")
    public data class ImportArchive(val path: String, val folder: String? = null) : LibraryItem
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
 * The proposals of the clients for the library, like the ones of the documents ([ProposalBook]).
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

    /** Puts back the proposals kept before the application quit, the most recent ones. */
    internal fun restore(kept: List<LibraryProposal>) {
        state.value = kept.filter { !it.isWaiting }.takeLast(maxDecided) + kept.filter { it.isWaiting }.takeLast(maxWaiting)
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
 * A step undoing an item of the library, as a value so that it is kept with the activity (see
 * [AgentJournal]): [isAsLeft] tells whether what the item did is still as it left it, [undo] puts it back.
 */
@Serializable
internal sealed interface LibraryUndo {
    suspend fun isAsLeft(documents: OpenDocuments): Boolean

    suspend fun undo(catalog: DocumentCatalog): Boolean

    /** A document made: out of the trash and not written in since ([versions]), trashing it loses nothing of the user's. */
    @Serializable
    @SerialName("created_document")
    class CreatedDocument(val document: String, val versions: Map<String, Long>?) : LibraryUndo {
        override suspend fun isAsLeft(documents: OpenDocuments): Boolean =
            documents.store.documents.first().any { it.id == document } && documents.versionsOf(document) == versions

        override suspend fun undo(catalog: DocumentCatalog): Boolean = catalog.trashDocument(document)
    }

    /**
     * A folder made by an import of an archive, with its documents: still there, and none written in
     * since ([versions]), trashing it loses nothing of the user's.
     */
    @Serializable
    @SerialName("imported_archive")
    class ImportedArchive(val folder: String, val versions: Map<String, Map<String, Long>?>) : LibraryUndo {
        override suspend fun isAsLeft(documents: OpenDocuments): Boolean {
            val catalog = documents.store
            if (catalog.folder(folder) == null) return false
            val inside = documentsUnder(catalog, folder)
            return inside.toSet() == versions.keys && inside.all { documents.versionsOf(it) == versions[it] }
        }

        override suspend fun undo(catalog: DocumentCatalog): Boolean = catalog.trashFolder(folder)
    }

    /** A document moved into [to] from [from]. */
    @Serializable
    @SerialName("moved_document")
    class MovedDocument(val document: String, val to: String?, val from: String?) : LibraryUndo {
        override suspend fun isAsLeft(documents: OpenDocuments): Boolean =
            documents.store.documents.first().firstOrNull { it.id == document }?.let { it.folderId == to } == true

        override suspend fun undo(catalog: DocumentCatalog): Boolean = catalog.moveDocument(document, from)
    }

    /** A document or a folder sent to the trash. */
    @Serializable
    @SerialName("trashed")
    class Trashed(val item: String) : LibraryUndo {
        override suspend fun isAsLeft(documents: OpenDocuments): Boolean = documents.store.inTrash(item)

        override suspend fun undo(catalog: DocumentCatalog): Boolean = catalog.restore(item) != null
    }

    /** A folder made: still as made, and empty, trashing it takes nothing else. */
    @Serializable
    @SerialName("created_folder")
    class CreatedFolder(val folder: String, val name: String, val parent: String?) : LibraryUndo {
        override suspend fun isAsLeft(documents: OpenDocuments): Boolean {
            val catalog = documents.store
            val made = catalog.folder(folder)
            return made != null && made.name == name && made.parentId == parent &&
                catalog.documents.first().none { it.folderId == folder } && catalog.folders.first().none { it.parentId == folder }
        }

        override suspend fun undo(catalog: DocumentCatalog): Boolean = catalog.trashFolder(folder)
    }

    /** A folder renamed [name], from [previous]. */
    @Serializable
    @SerialName("renamed_folder")
    class RenamedFolder(val folder: String, val name: String, val previous: String) : LibraryUndo {
        override suspend fun isAsLeft(documents: OpenDocuments): Boolean = documents.store.folder(folder)?.name == name

        override suspend fun undo(catalog: DocumentCatalog): Boolean = catalog.renameFolder(folder, previous)
    }

    /** A folder moved into [to] from [from]. */
    @Serializable
    @SerialName("moved_folder")
    class MovedFolder(val folder: String, val to: String?, val from: String?) : LibraryUndo {
        override suspend fun isAsLeft(documents: OpenDocuments): Boolean = documents.store.folder(folder)?.let { it.parentId == to } == true

        override suspend fun undo(catalog: DocumentCatalog): Boolean = catalog.moveFolder(folder, from)
    }

    /** An item of the trash put back; [document]: it is a document, not a folder. */
    @Serializable
    @SerialName("restored")
    class Restored(val item: String, val document: Boolean) : LibraryUndo {
        override suspend fun isAsLeft(documents: OpenDocuments): Boolean = !documents.store.inTrash(item)

        override suspend fun undo(catalog: DocumentCatalog): Boolean = if (document) catalog.trashDocument(item) else catalog.trashFolder(item)
    }
}

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
internal class LibraryApplier(private val engines: AgentEngines = AgentEngines()) {

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
                val id = catalog.createDocument(item.title, item.widthMm, item.heightMm, item.pageBackground, item.folder).id
                created += id
                LibraryUndo.CreatedDocument(id, documents.versionsOf(id))
            }
        }
        is LibraryItem.MoveDocument -> {
            val document = catalog.documents.first().firstOrNull { it.id == item.document }
            if (document == null || (item.folder != null && catalog.folder(item.folder) == null) || !catalog.moveDocument(item.document, item.folder)) null
            else LibraryUndo.MovedDocument(item.document, item.folder, document.folderId)
        }
        is LibraryItem.TrashDocument -> {
            if (!catalog.trashDocument(item.document)) null
            else LibraryUndo.Trashed(item.document)
        }
        is LibraryItem.CreateFolder -> {
            if ((item.parent != null && catalog.folder(item.parent) == null) || !catalog.createFolder(item.name, item.parent)) null
            else {
                val id = catalog.folders.first().first { it.parentId == item.parent && it.name == item.name }.id
                LibraryUndo.CreatedFolder(id, item.name, item.parent)
            }
        }
        is LibraryItem.RenameFolder -> {
            val folder = catalog.folder(item.folder)
            if (folder == null || !catalog.renameFolder(item.folder, item.name)) null
            else LibraryUndo.RenamedFolder(item.folder, item.name, folder.name)
        }
        is LibraryItem.MoveFolder -> {
            val folder = catalog.folder(item.folder)
            if (folder == null || (item.parent != null && catalog.folder(item.parent) == null) || !catalog.moveFolder(item.folder, item.parent)) null
            else LibraryUndo.MovedFolder(item.folder, item.parent, folder.parentId)
        }
        is LibraryItem.TrashFolder -> {
            if (!catalog.trashFolder(item.folder)) null
            else LibraryUndo.Trashed(item.folder)
        }
        is LibraryItem.Restore -> {
            val trashed = catalog.trash.first().firstOrNull { it.id == item.item }
            if (trashed == null || catalog.restore(item.item) == null) null
            else LibraryUndo.Restored(item.item, document = trashed.kind == io.github.valerioisufi.pageboard.store.TrashedItem.Kind.DOCUMENT)
        }
        is LibraryItem.DuplicateDocument -> duplicate(documents, catalog, item, created)
        is LibraryItem.ImportArchive -> {
            val made = if (item.folder != null && catalog.folder(item.folder) == null) null else engines.files?.importArchive(item.path, item.folder)
            if (made == null) null
            else {
                val inside = documentsUnder(catalog, made)
                created += inside
                LibraryUndo.ImportedArchive(made, inside.associateWith { documents.versionsOf(it) })
            }
        }
    }

    /** A copy of the document of [item] with all its pages, as the editor pastes copied pages: new ids. */
    private suspend fun duplicate(documents: OpenDocuments, catalog: DocumentCatalog, item: LibraryItem.DuplicateDocument, created: MutableList<String>): LibraryUndo? {
        val source = catalog.documents.first().firstOrNull { it.id == item.document } ?: return null
        val folder = item.folder ?: source.folderId
        if (folder != null && catalog.folder(folder) == null) return null
        val taken = catalog.documents.first().filter { it.folderId == folder }.mapTo(HashSet()) { it.name }
        val title = item.title?.trim()?.takeIf { it !in taken } ?: if (item.title != null) return null else freeName(source.name, taken)
        val from = documents.open(source.id) ?: return null
        val copied = try {
            from.edit { session -> session.document.let { it to it.copyPages(it.pages) } }.second
        } finally {
            documents.close(from)
        }
        val made = catalog.createDocument(title, source.defaultWidthMm, source.defaultHeightMm, source.defaultBackground, folder)
        val open = documents.open(made.id) ?: return null
        try {
            open.edit { session ->
                val document = session.document
                val blank = document.pages.single()
                val pasted = copied.pasteInto(document, 1).change ?: return@edit
                session.submit(CompositeChange(listOf(pasted, RemovePage(blank.id))))
            }
        } finally {
            documents.close(open)
        }
        created += made.id
        return LibraryUndo.CreatedDocument(made.id, documents.versionsOf(made.id))
    }

    /** [name] with the first number that makes it free among [taken]: "Name (2)", "Name (3)"... */
    private fun freeName(name: String, taken: Set<String>): String {
        var n = 2
        while ("$name ($n)" in taken) n++
        return "$name ($n)"
    }
}

/** The documents in the folder [folder] and in the folders under it. */
private suspend fun documentsUnder(catalog: DocumentCatalog, folder: String): List<String> {
    val folders = catalog.folders.first()
    val under = mutableSetOf(folder)
    var grew = true
    while (grew) {
        grew = false
        for (one in folders) if (one.parentId in under && under.add(one.id)) grew = true
    }
    return catalog.documents.first().filter { it.folderId in under }.map { it.id }
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
