package io.github.valerioisufi.drawview.store.space

import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.store.DocumentStore
import io.github.valerioisufi.drawview.store.TrashedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the library and the shared folders of this device in step (GRASP Indirection, Controller
 * of "organize the library"; see `docs/shared-folders.md`):
 *
 * * what the library holds under the root of a space, as anything changes it (the explorer, an
 *   agent, an import), becomes changes of the space ([localOperations], to send);
 * * the changes of the other devices ([receive]) are written into the library ([SpaceStore.putFolder]
 *   and the others), so that it shows what the space holds.
 *
 * Both directions compare the library with the tree of the catalog ([SpaceCatalog.view]), so that
 * a write of one is no change of the other. The documents shared on their own (a secret of their
 * own in [DocumentStore.sharingSecrets], owned by this device or by others) stay out of the spaces.
 *
 * Nothing of the library is deleted: an item leaving a shared folder on another device goes, here,
 * to the folder holding the root of the space; an item deleted for good here (not in the library)
 * changes nothing of the space.
 */
public class SpaceLibrary(
    private val store: DocumentStore,
    private val spaces: SpaceStore,
    private val scope: CoroutineScope,
    private val wallClock: (() -> Long)? = null,
    private val settleMs: Long = 200L
) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, Space>()
    private var follow: Job? = null
    private var replica = ""

    private val operations = MutableSharedFlow<SpaceOperation>(extraBufferCapacity = 256)

    /** The changes made on this device, as they are made: to send to the other members of their space. */
    public val localOperations: SharedFlow<SpaceOperation> = operations.asSharedFlow()

    /** A space open here: what this device keeps of it, and the session of its changes. */
    private class Space(var stored: StoredSpace, val session: CatalogSession)

    /**
     * Opens the spaces kept, takes in the changes of the library made while they were closed, and
     * follows the library from then on.
     */
    @OptIn(FlowPreview::class)
    public suspend fun start() {
        mutex.withLock {
            replica = store.replicaId()
            for (stored in spaces.spaces()) open(stored)
            for (space in sessions.values) reconcileLocked(space)
        }
        follow?.cancel()
        follow = scope.launch {
            // What the spaces depend on: where the items are, the names of the folders, the trash
            // (not the content of the documents, which changes all the time)
            combine(
                store.documents,
                store.folders,
                store.trash
            ) { documents, folders, trash ->
                Triple(
                    documents.map { it.id to it.folderId },
                    folders.map { Triple(it.id, it.name, it.parentId) },
                    trash.map { Triple(it.id, it.name, it.folderId) }
                )
            }.distinctUntilChanged().debounce(settleMs).collect {
                mutex.withLock { for (space in sessions.values) reconcileLocked(space) }
            }
        }
    }

    /**
     * Takes in the changes of the library now, as following it does once it settles (e.g. when the
     * application comes back to the foreground).
     */
    public suspend fun reconcile(): Unit = mutex.withLock { for (space in sessions.values) reconcileLocked(space) }

    /** Stops following the library. */
    public fun stop() {
        follow?.cancel()
        follow = null
    }

    /** The spaces open here. */
    public suspend fun spaces(): List<StoredSpace> = mutex.withLock { sessions.values.map { it.stored } }

    /**
     * Shares [rootFolderId] (a folder, `null`: the library) as a new space: what it holds becomes
     * the catalog, a change made by this device.
     *
     * @return the space, or `null` when the folder is in a space already or holds one (a space in
     *   a space is refused).
     */
    public suspend fun share(rootFolderId: String?, spaceId: String = newElementId()): StoredSpace? = mutex.withLock {
        if (replica.isEmpty()) replica = store.replicaId()
        if (overlapsLocked(rootFolderId)) return@withLock null
        val stored = StoredSpace(spaceId, rootFolderId)
        spaces.saveSpace(stored)
        val space = open(stored)
        reconcileLocked(space)
        stored
    }

    /**
     * Joins the space [spaceId] with its root at [rootFolderId] (a folder of this library, `null`:
     * the library itself, whose items then go into the space). Its catalog comes with [receive].
     */
    public suspend fun join(spaceId: String, rootFolderId: String?, readOnly: Boolean = false): StoredSpace? = mutex.withLock {
        if (replica.isEmpty()) replica = store.replicaId()
        sessions[spaceId]?.let { return@withLock it.stored }
        if (overlapsLocked(rootFolderId)) return@withLock null
        val stored = StoredSpace(spaceId, rootFolderId, readOnly = readOnly)
        spaces.saveSpace(stored)
        open(stored)
        stored
    }

    /** Keeps [stored] (its sharing, its role) for a space open here. */
    public suspend fun update(stored: StoredSpace): Unit = mutex.withLock {
        val space = sessions[stored.id] ?: return@withLock
        space.stored = stored
        space.session.isReadOnly = stored.readOnly
        spaces.saveSpace(stored)
    }

    /** Forgets the space [spaceId] (left, removed, ended): the library keeps everything it holds. */
    public suspend fun leave(spaceId: String): Unit = mutex.withLock {
        sessions.remove(spaceId) ?: return@withLock
        spaces.removeSpace(spaceId)
    }

    /** The version vector of the changes of [spaceId] applied here. */
    public suspend fun versionVector(spaceId: String): Map<String, Long> = mutex.withLock { sessions[spaceId]?.session?.versionVector.orEmpty() }

    /** What the space [spaceId] holds, as the library shows it. */
    public suspend fun view(spaceId: String): List<CatalogEntry> = mutex.withLock { sessions[spaceId]?.session?.catalog?.view().orEmpty() }

    /** Whether [operation] of another device may be received in [spaceId] (see [CatalogSession.admits]). */
    public suspend fun admits(spaceId: String, operation: CatalogOperation): Boolean =
        mutex.withLock { sessions[spaceId]?.session?.admits(operation) ?: false }

    /**
     * Applies [operations] of other devices to [spaceId] (each once, in the order of its replica),
     * keeps them, and writes what they change into the library.
     *
     * @return the operations applied.
     * @throws IllegalArgumentException for an operation not admitted (see [CatalogSession.admits]):
     *   the ones before it are applied.
     */
    public suspend fun receive(spaceId: String, operations: List<CatalogOperation>): Int = mutex.withLock {
        val space = sessions[spaceId] ?: return@withLock 0
        val before = space.session.catalog.view()
        var applied = 0
        try {
            for (operation in operations) {
                if (!space.session.receive(operation)) continue
                applied++
                spaces.saveCatalogChange(spaceId, changedItems(space, operation.change), operation)
            }
        } finally {
            if (applied > 0) writeLibraryLocked(space, before)
        }
        applied
    }

    /**
     * The folder of the library where the document [documentId] of a space goes when it arrives
     * (see `DocumentStore.insertDocument`), or `null` when no space holds it (`Result.success(null)`
     * for the root of the library).
     */
    public suspend fun folderOf(documentId: String): Result<String?>? = mutex.withLock {
        for (space in sessions.values) {
            val entry = space.session.catalog.view().firstOrNull { it.id == documentId && it.kind == CatalogItemKind.DOCUMENT } ?: continue
            return@withLock Result.success(entry.parent ?: space.stored.rootFolderId)
        }
        null
    }

    /** The space holding the document [documentId], if any. */
    public suspend fun spaceOf(documentId: String): String? = mutex.withLock {
        sessions.values.firstOrNull { space -> space.session.catalog.view().any { it.id == documentId } }?.stored?.id
    }

    private suspend fun open(stored: StoredSpace): Space {
        val items = spaces.catalogItems(stored.id)
        val vector = spaces.catalogVersionVector(stored.id)
        val session = CatalogSession(stored.id, SpaceCatalog(items), replica, vector, wallClock)
        session.isReadOnly = stored.readOnly
        return Space(stored, session).also { sessions[stored.id] = it }
    }

    /** Whether a space with its root at [rootFolderId] would be in another one or hold one. */
    private suspend fun overlapsLocked(rootFolderId: String?): Boolean {
        if (sessions.isEmpty()) return false
        if (rootFolderId == null) return true
        val parents = libraryParents()
        for (space in sessions.values) {
            val other = space.stored.rootFolderId ?: return true
            if (isUnder(rootFolderId, other, parents) || isUnder(other, rootFolderId, parents)) return true
        }
        return false
    }

    /** The items of the catalog [change] names, as they are now (to keep with the operation). */
    private fun changedItems(space: Space, change: CatalogChange): List<CatalogItem> =
        change.ids.distinct().mapNotNull { space.session.catalog[it] }

    // --- The library seen by the spaces ---

    /** An item of the library as a space compares it: where it is, its name (a folder), whether it is in the trash itself. */
    private data class LocalItem(val id: String, val kind: CatalogItemKind, val name: String, val parent: String?, val trashed: Boolean)

    /** What the library shows: the documents and folders out of the trash, and the items in it (not what they hold). */
    private suspend fun libraryItems(): Map<String, LocalItem> {
        val items = LinkedHashMap<String, LocalItem>()
        for (folder in store.folders.first()) items[folder.id] = LocalItem(folder.id, CatalogItemKind.FOLDER, folder.name, folder.parentId, false)
        for (document in store.documents.first()) items[document.id] = LocalItem(document.id, CatalogItemKind.DOCUMENT, "", document.folderId, false)
        for (item in store.trash.first()) {
            val kind = if (item.kind == TrashedItem.Kind.FOLDER) CatalogItemKind.FOLDER else CatalogItemKind.DOCUMENT
            items[item.id] = LocalItem(item.id, kind, if (kind == CatalogItemKind.FOLDER) item.name else "", item.folderId, true)
        }
        return items
    }

    private suspend fun libraryParents(): Map<String, String?> = libraryItems().filterValues { it.kind == CatalogItemKind.FOLDER }.mapValues { it.value.parent }

    /**
     * Whether an item in [parent] is under [ancestor] (`null`: the root of the library), [parent]
     * being [ancestor] or one of the folders under it in [parents].
     */
    private fun isUnder(parent: String?, ancestor: String?, parents: Map<String, String?>): Boolean {
        if (ancestor == null) return true
        var current: String? = parent
        var steps = 0
        while (current != null && steps++ <= parents.size) {
            if (current == ancestor) return true
            current = parents[current]
        }
        return false
    }

    /**
     * Brings [space] and the library in step: the changes of the library under its root become a
     * change of the space (here, or, read only, the library is put back as the space holds it).
     */
    private suspend fun reconcileLocked(space: Space) {
        if (space.session.isReadOnly) {
            writeLibraryLocked(space, space.session.catalog.view())
            return
        }
        val root = space.stored.rootFolderId
        val local = libraryItems()
        val parents = local.filterValues { it.kind == CatalogItemKind.FOLDER }.mapValues { it.value.parent }
        if (root != null && root !in local) return // The root is gone (deleted, or in a folder in the trash): nothing to compare
        val ownSharing = store.sharingSecrets().keys
        val catalog = space.session.catalog
        val view = catalog.view().associateBy { it.id }
        val changes = mutableListOf<CatalogChange>()

        val inSpace = local.values.filter { item ->
            item.id != root && isUnder(item.parent, root, parents) && !(item.kind == CatalogItemKind.DOCUMENT && item.id in ownSharing)
        }.associateBy { it.id }
        for (item in inSpace.values) {
            val parent = if (item.parent == root) null else item.parent
            val entry = view[item.id]
            if (entry == null || entry.kind != item.kind) {
                changes += if (item.kind == CatalogItemKind.FOLDER) AddFolder(item.id, item.name, parent) else AddDocument(item.id, parent)
                if (item.trashed != (catalog[item.id]?.trashed ?: false)) changes += if (item.trashed) TrashItem(item.id) else RestoreItem(item.id)
                continue
            }
            if (item.kind == CatalogItemKind.FOLDER && entry.name != item.name) changes += RenameFolder(item.id, item.name)
            if (entry.parent != parent) changes += MoveItem(item.id, parent)
            if (entry.trashed != item.trashed) changes += if (item.trashed) TrashItem(item.id) else RestoreItem(item.id)
        }
        // Moved out of the shared folder here, or shared on its own: out of the space (an item not
        // in the library, deleted for good here or never received, changes nothing)
        for (entry in view.values) {
            if (entry.id in inSpace || entry.id !in local) continue
            changes += RemoveFromSpace(entry.id)
        }
        submitLocked(space, changes)
    }

    private suspend fun submitLocked(space: Space, changes: List<CatalogChange>) {
        for (chunk in changes.chunked(CompositeCatalogChange.MAX_CHANGES)) {
            val change = chunk.singleOrNull() ?: CompositeCatalogChange(chunk)
            val committed = space.session.submit(change) ?: continue
            spaces.saveCatalogChange(space.stored.id, changedItems(space, change), committed.operation)
            operations.emit(SpaceOperation(space.stored.id, committed.operation))
        }
    }

    /**
     * Writes what the space holds into the library: the folders made, named and placed as the
     * catalog has them, the documents placed, the trash; the items gone from the space since
     * [before] taken out of its root (to the folder holding it), or left where they are for a
     * shared library (it holds everything).
     */
    private suspend fun writeLibraryLocked(space: Space, before: List<CatalogEntry>) {
        val root = space.stored.rootFolderId
        if (root != null && store.folders.first().none { it.id == root } && store.trash.first().none { it.id == root }) return
        val view = space.session.catalog.view()
        val local = libraryItems()
        fun local(parent: String?) = parent ?: root
        // Folders from the top down, so that each one goes into a folder there already
        val folders = view.filter { it.kind == CatalogItemKind.FOLDER }.associateBy { it.id }
        fun depth(entry: CatalogEntry): Int {
            var depth = 0
            var parent = entry.parent
            while (parent != null && depth <= folders.size) {
                depth++
                parent = folders[parent]?.parent
            }
            return depth
        }
        for (entry in folders.values.sortedBy(::depth)) {
            val item = local[entry.id]
            if (item == null || item.name != entry.name || item.parent != local(entry.parent)) {
                spaces.putFolder(entry.id, entry.name, local(entry.parent))
            }
        }
        for (entry in view.filter { it.kind == CatalogItemKind.DOCUMENT }) {
            val item = local[entry.id]
            if (item == null || item.parent != local(entry.parent)) spaces.putDocumentFolder(entry.id, local(entry.parent))
        }
        val trashed = libraryItems()
        for (entry in view) {
            val item = trashed[entry.id] ?: continue
            if (item.trashed != entry.trashed) spaces.putTrashed(entry.id, entry.trashed)
        }
        if (root == null) return
        // Out of the space: kept, out of its root (the items above first, what they hold follows)
        val shown = view.mapTo(HashSet()) { it.id }
        val gone = before.filter { it.id !in shown }.associateBy { it.id }
        val outside = store.folders.first().firstOrNull { it.id == root }?.parentId
            ?: store.trash.first().firstOrNull { it.id == root }?.folderId
        for (entry in gone.values) {
            if (entry.parent != null && entry.parent in gone) continue
            val item = trashed[entry.id] ?: continue
            if (item.parent != local(entry.parent)) continue // Moved here since: where the user put it
            when (entry.kind) {
                CatalogItemKind.FOLDER -> spaces.putFolder(entry.id, item.name, outside)
                CatalogItemKind.DOCUMENT -> spaces.putDocumentFolder(entry.id, outside)
            }
        }
    }
}

/** A change of the space [spaceId] made on this device (see [SpaceLibrary.localOperations]). */
public data class SpaceOperation(val spaceId: String, val operation: CatalogOperation)
