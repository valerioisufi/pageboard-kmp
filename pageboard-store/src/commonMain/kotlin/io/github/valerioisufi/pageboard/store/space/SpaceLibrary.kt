package io.github.valerioisufi.pageboard.store.space

import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.change.OperationId
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.store.DocumentStore
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
 * of "organize the library"; see `docs/plans/shared-folders.md`):
 *
 * * what the library holds under the root of a space, as anything changes it (the explorer, an
 *   agent, an import), becomes changes of the space ([localOperations], to send);
 * * the changes of the other devices ([receive]) are written into the library ([SpaceStore.putFolder]
 *   and the others), so that it shows what the space holds.
 *
 * Both directions compare the library with the tree of the catalog ([SpaceCatalog.view]), so that
 * a write of one is no change of the other. A document shared on its own (a secret in
 * [DocumentStore.sharingSecrets]) is in the space as any other, with its own sharing besides (see
 * `docs/plans/shared-access.md`), unless [keptApart] keeps it out when it would be brought in (the
 * application's choice, or what the sharing does not allow): a document in the space already is
 * never taken out for having a sharing of its own.
 *
 * Nothing of the library is deleted: an item leaving a shared folder on another device goes, here,
 * to the folder holding the root of the space; an item deleted for good here (not in the library)
 * changes nothing of the space.
 *
 * A shared folder may be inside another one (see `docs/plans/shared-access.md`), up to [maxNesting]
 * spaces around an item: what it holds is in the catalog of each, a change of the library is a
 * change of each, and a change received in one is written into the library with, for each piece of
 * an item (name, place, trash), the latest stamp of the catalogs holding it, so that the others
 * take it in turn and nothing moves back and forth.
 */
public class SpaceLibrary(
    private val store: DocumentStore,
    private val spaces: SpaceStore,
    private val scope: CoroutineScope,
    private val wallClock: (() -> Long)? = null,
    private val settleMs: Long = 200L,
    private val keptApart: suspend (spaceId: String, documentId: String, secret: String) -> Boolean = { _, _, _ -> false },
    /** The most spaces around an item: a space deeper is refused (see `docs/plans/shared-access.md`). */
    private val maxNesting: Int = MAX_NESTING
) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, Space>()

    /** The items of each space whose log did not explain who brought them (see [CatalogMigration]). */
    private val unexplained = mutableMapOf<String, Set<String>>()
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
     * @return the space, or `null` when the folder is the root of a space already, or the spaces
     *   around an item would be more than [maxNesting].
     */
    public suspend fun share(rootFolderId: String?, spaceId: String = newElementId()): StoredSpace? = mutex.withLock {
        if (replica.isEmpty()) replica = store.replicaId()
        if (refusedLocked(rootFolderId)) return@withLock null
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
        if (refusedLocked(rootFolderId)) return@withLock null
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
        unexplained.remove(spaceId)
        spaces.removeSpace(spaceId)
    }

    /** The version vector of the changes of [spaceId] applied here. */
    public suspend fun versionVector(spaceId: String): Map<String, Long> = mutex.withLock { sessions[spaceId]?.session?.versionVector.orEmpty() }

    /** Whether this device keeps the space [spaceId] (shared, or joined). */
    public suspend fun has(spaceId: String): Boolean = mutex.withLock { spaceId in sessions }

    /** The changes of [spaceId] after [known] (a version vector), in the order of their stamps: to give to another member. */
    public suspend fun operationsAfter(spaceId: String, known: Map<String, Long>): List<CatalogOperation> =
        spaces.catalogOperationsAfter(spaceId, known)

    /** The proofs kept of the changes [ids] of [spaceId] (see [SpaceStore.catalogProofs]). */
    public suspend fun proofs(spaceId: String, ids: Collection<OperationId>): Map<OperationId, ByteArray> = spaces.catalogProofs(spaceId, ids)

    /** Keeps the proofs of changes of [spaceId] made by other devices, to give them on. */
    public suspend fun saveProofs(spaceId: String, proofs: Map<OperationId, ByteArray>): Unit = spaces.saveCatalogProofs(spaceId, proofs)

    /** The last change of [replica] applied to [spaceId] (0: none). */
    public suspend fun lastApplied(spaceId: String, replica: String): Long = mutex.withLock { sessions[spaceId]?.session?.lastApplied(replica) ?: 0L }

    /**
     * A folder named [name] at the root of the library, for the root of a space joined (another one
     * of the same name may be there: the user renames it as it likes).
     */
    public suspend fun newRootFolder(name: String): String {
        val id = newElementId()
        spaces.putFolder(id, name, null)
        return id
    }

    /**
     * The items of [spaceId] kept before who brought an item followed its arrivals, whose log did
     * not explain it: they keep who brought them as it was kept (see `docs/plans/brought-by.md`).
     */
    public suspend fun unexplainedItems(spaceId: String): Set<String> = mutex.withLock { unexplained[spaceId].orEmpty() }

    /** What the space [spaceId] holds, as the library shows it. */
    public suspend fun view(spaceId: String): List<CatalogEntry> = mutex.withLock { sessions[spaceId]?.session?.catalog?.view().orEmpty() }

    /**
     * Confirms who gives each document of [spaceId] that this device has and that has no giver yet:
     * the replica that brought it (see [ConfirmGiver]), a change of this device. Made by the owner of
     * the space only (the sharing of `pageboard-sync` decides it): the other devices refuse it from
     * anyone else.
     *
     * @return how many documents were confirmed.
     */
    public suspend fun confirmGivers(spaceId: String): Int = mutex.withLock {
        val space = sessions[spaceId] ?: return@withLock 0
        val unconfirmed = space.session.catalog.view().filter { it.kind == CatalogItemKind.DOCUMENT && it.giver.isEmpty() && it.broughtBy.isNotEmpty() }
        if (unconfirmed.isEmpty()) return@withLock 0
        val here = libraryItems()
        val changes = unconfirmed.filter { it.id in here }.map { ConfirmGiver(it.id, it.broughtBy) }
        submitLocked(space, changes)
        changes.size
    }

    /**
     * Names in [spaceId] the sharing of its own of the item [itemId] ([sharing], owned by [owner],
     * see [ShareItem]), a change of this device; nothing when it is named so already.
     *
     * @return whether a change was made.
     */
    public suspend fun shareItem(spaceId: String, itemId: String, sharing: String, owner: String): Boolean = mutex.withLock {
        val space = sessions[spaceId] ?: return@withLock false
        if (space.session.catalog[itemId]?.sharedAs == SharedAs(sharing, owner)) return@withLock false
        submitLocked(space, listOf(ShareItem(itemId, sharing, owner)))
        true
    }

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
        // The changes of the library made here first: writing what the others did would put them back
        reconcileLocked(space)
        val before = space.session.catalog.view()
        var applied = 0
        try {
            for (operation in operations) {
                if (!space.session.receive(operation)) continue
                applied++
                spaces.saveCatalogChange(spaceId, changedItems(space, operation.change), operation)
            }
        } finally {
            if (applied > 0) {
                writeLibraryLocked(space, before)
                // The other spaces holding what changed take it at once: what a folder in the trash
                // holds changes without the library showing it, which following it would not see
                for (other in sessions.values) if (other !== space) reconcileLocked(other)
            }
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
        // A catalog of before: who brought each item recomputed from the log, and kept so
        val completed = CatalogMigration.complete(spaces.catalogItems(stored.id)) { spaces.catalogOperationsAfter(stored.id, emptyMap()) }
        if (completed.changed.isNotEmpty()) spaces.saveCatalogItems(stored.id, completed.changed)
        unexplained[stored.id] = completed.unexplained
        val items = completed.items
        val vector = spaces.catalogVersionVector(stored.id)
        val session = CatalogSession(stored.id, SpaceCatalog(items), replica, vector, wallClock)
        session.isReadOnly = stored.readOnly
        return Space(stored, session).also { sessions[stored.id] = it }
    }

    /**
     * Whether a space with its root at [rootFolderId] is refused: another one has the same root, or
     * an item would be in more than [maxNesting] spaces.
     */
    private suspend fun refusedLocked(rootFolderId: String?): Boolean {
        if (sessions.values.any { it.stored.rootFolderId == rootFolderId }) return true
        val parents = libraryParents()
        val roots = sessions.values.map { it.stored.rootFolderId } + rootFolderId
        // The spaces around the root of each one, itself included
        return roots.any { root -> roots.count { other -> isUnder(root, other, parents) } > maxNesting }
    }

    /**
     * The spaces holding the item [itemId] of the library (a folder or a document): the ones whose
     * root is above it, innermost first; a folder that is the root of a space is held by the ones
     * around it, not by its own.
     */
    public suspend fun spacesOf(itemId: String): List<StoredSpace> = mutex.withLock {
        val items = libraryItems()
        val parents = items.filterValues { it.kind == CatalogItemKind.FOLDER }.mapValues { it.value.parent }
        val parent = items[itemId]?.parent
        sessions.values.map { it.stored }
            .filter { isUnder(parent, it.rootFolderId, parents) }
            .sortedByDescending { space -> sessions.values.count { other -> isUnder(space.rootFolderId, other.stored.rootFolderId, parents) } }
    }

    /** The folder that is the root of the space [spaceId] inside another space of this library, as that one names it ([ShareItem]); `null` when none does. */
    public suspend fun namedRootOf(spaceId: String): String? = mutex.withLock {
        sessions.values.firstNotNullOfOrNull { space ->
            space.session.catalog.view().firstOrNull { it.kind == CatalogItemKind.FOLDER && it.sharedAs?.sharing == spaceId }?.id
        }
    }

    /** The items of the catalog [change] names, as they are now (to keep with the operation). */
    private fun changedItems(space: Space, change: CatalogChange): List<CatalogItem> =
        change.ids.distinct().mapNotNull { space.session.catalog[it] }

    // --- The library seen by the spaces ---

    /** An item of the library as a space compares it: where it is, its name (a folder), whether it is in the trash itself. */
    private data class LocalItem(val id: String, val kind: CatalogItemKind, val name: String, val parent: String?, val trashed: Boolean)

    /**
     * Every document and folder of the library, what a folder in the trash holds included (it is
     * in the spaces still, and other devices go on changing it): each where it is, and whether it
     * is in the trash itself.
     */
    private suspend fun libraryItems(): Map<String, LocalItem> {
        val library = spaces.library()
        val items = LinkedHashMap<String, LocalItem>()
        for (folder in library.folders) items[folder.id] = LocalItem(folder.id, CatalogItemKind.FOLDER, folder.name, folder.parentId, folder.trashedAt != null)
        for (document in library.documents) items[document.id] = LocalItem(document.id, CatalogItemKind.DOCUMENT, "", document.parentId, document.trashedAt != null)
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
        val local = libraryItems()
        val differences = differencesLocked(space, local) ?: return
        submitLocked(space, differences.changes)
        // Taken in from another space: each with the stamp it has there
        for ((change, at) in differences.relayed.sortedBy { it.second }) submitLocked(space, listOf(change), at)
        // Shown again by these changes, and not in the library: what other devices made in a folder
        // while it was out of the space here (written as a change received, nothing taken out)
        val shown = space.session.catalog.view()
        if (shown.any { it.id !in differences.view && it.id !in local }) writeLibraryLocked(space, shown)
    }

    /**
     * What the library ([local]) holds under the root of [space] otherwise than its catalog: the
     * changes made here ([Differences.changes]), and the pieces taken in from another space holding
     * the item, with the stamps they have there ([Differences.relayed]); `null` when the root is gone.
     */
    private class Differences(val view: Map<String, CatalogEntry>, val changes: List<CatalogChange>, val relayed: List<Pair<CatalogChange, Stamp>>) {
        /** The items changed (the first one each change names: the folder it goes into is not changed). */
        val ids: Set<String> get() = (changes + relayed.map { it.first }).mapNotNullTo(HashSet()) { it.ids.firstOrNull() }
    }

    private suspend fun differencesLocked(space: Space, local: Map<String, LocalItem>): Differences? {
        val root = space.stored.rootFolderId
        val parents = local.filterValues { it.kind == CatalogItemKind.FOLDER }.mapValues { it.value.parent }
        if (root != null && root !in local) return null // The root is gone (deleted for good): nothing to compare
        val catalog = space.session.catalog
        val view = catalog.view().associateBy { it.id }
        // Kept out when they would be brought in only: in the space, a sharing of its own changes nothing
        val secrets = store.sharingSecrets()
        val apart = secrets.filter { (id, secret) -> id !in view && keptApart(space.stored.id, id, secret) }.keys
        val changes = mutableListOf<CatalogChange>()
        // A piece the library has from another space holding the item, newer there: taken in with its stamp
        val others = sessions.values.filter { it !== space }.map { NestedPieces.Held(it.stored.rootFolderId, it.session.catalog) }
        val relayed = mutableListOf<Pair<CatalogChange, Stamp>>()
        fun relay(id: String, piece: (CatalogItem) -> Stamp, holds: (NestedPieces.Held, CatalogEntry) -> Boolean): Stamp? {
            val mine = catalog[id]?.let(piece) ?: Stamp.ZERO
            val source = others.mapNotNull { held -> held.view[id]?.takeIf { holds(held, it) }?.let { held.catalog[id]?.let(piece) } }.filter { it > mine }.maxOrNull() ?: return null
            // With the replica of this device: still after the piece it replaces (or it would change nothing, again and again)
            val stamp = Stamp(source.time, source.counter, replica)
            return if (stamp > mine) stamp else Stamp(mine.time, mine.counter + 1, replica)
        }
        fun change(change: CatalogChange, at: Stamp?) {
            if (at == null) changes += change else relayed += change to at
        }

        val inSpace = local.values.filter { item ->
            item.id != root && isUnder(item.parent, root, parents) && !(item.kind == CatalogItemKind.DOCUMENT && item.id in apart)
        }.associateBy { it.id }
        for (item in inSpace.values) {
            val parent = if (item.parent == root) null else item.parent
            val entry = view[item.id]
            if (entry == null || entry.kind != item.kind) {
                changes += if (item.kind == CatalogItemKind.FOLDER) AddFolder(item.id, item.name, parent) else AddDocument(item.id, parent)
                if (item.trashed != (catalog[item.id]?.trashed ?: false)) changes += if (item.trashed) TrashItem(item.id) else RestoreItem(item.id)
                continue
            }
            if (item.kind == CatalogItemKind.FOLDER && entry.name != item.name) {
                change(RenameFolder(item.id, item.name), relay(item.id, { it.named }) { _, other -> other.name == item.name })
            }
            if (entry.parent != parent) change(MoveItem(item.id, parent), relay(item.id, { it.placed }) { held, other -> (other.parent ?: held.root) == item.parent })
            if (entry.trashed != item.trashed) {
                change(if (item.trashed) TrashItem(item.id) else RestoreItem(item.id), relay(item.id, { it.trashedVersion }) { _, other -> other.trashed == item.trashed })
            }
        }
        // Moved out of the shared folder here: out of the space (an item not in the library, deleted
        // for good here or never received, changes nothing)
        for (entry in view.values) {
            if (entry.id in inSpace || entry.id !in local) continue
            changes += RemoveFromSpace(entry.id)
        }
        return Differences(view, changes, relayed)
    }

    /**
     * For a space this device may only read: the items the library holds otherwise than its
     * catalog, made or changed here, or received through another space this device writes in (a
     * folder inside it, see `docs/plans/shared-access.md`). This device passes nothing into the
     * space, so its other members do not have them, unless a device that writes in both does.
     * Empty for a space this device writes in, or does not keep.
     */
    public suspend fun notPassedOn(spaceId: String): Set<String> = mutex.withLock {
        val space = sessions[spaceId]?.takeIf { it.session.isReadOnly } ?: return@withLock emptySet()
        differencesLocked(space, libraryItems())?.ids.orEmpty()
    }

    private suspend fun submitLocked(space: Space, changes: List<CatalogChange>, at: Stamp? = null) {
        for (chunk in changes.chunked(CompositeCatalogChange.MAX_CHANGES)) {
            val change = chunk.singleOrNull() ?: CompositeCatalogChange(chunk)
            val committed = space.session.submit(change, at) ?: continue
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
        val local = libraryItems()
        if (root != null && root !in local) return
        val view = space.session.catalog.view()
        fun local(parent: String?) = parent ?: root
        // Each piece of an item held by other spaces too: the latest of their catalogs
        val nested = NestedPieces((listOf(space) + sessions.values.filter { it !== space }).map { NestedPieces.Held(it.stored.rootFolderId, it.session.catalog) })
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
        // The places of the folders of the library as they are written, to refuse a cycle
        val placed = local.filterValues { it.kind == CatalogItemKind.FOLDER }.mapValuesTo(HashMap()) { it.value.parent }
        for (entry in folders.values.sortedBy(::depth)) {
            val item = local[entry.id]
            val name = nested.name(entry)
            var parent = nested.parent(entry)
            // Two catalogs placing a folder inside itself (one moved the root of the other, the other
            // moved a folder of it): where it is, which they take in turn from the library
            if (item != null && (parent == entry.id || isUnder(parent, entry.id, placed))) parent = item.parent
            if (item == null || item.name != name || item.parent != parent) spaces.putFolder(entry.id, name, parent)
            placed[entry.id] = parent
        }
        for (entry in view.filter { it.kind == CatalogItemKind.DOCUMENT }) {
            val item = local[entry.id]
            val parent = nested.parent(entry)
            if (item == null || item.parent != parent) spaces.putDocumentFolder(entry.id, parent)
        }
        val trashed = libraryItems()
        for (entry in view) {
            val item = trashed[entry.id] ?: continue
            val inTrash = nested.trashed(entry)
            if (item.trashed != inTrash) spaces.putTrashed(entry.id, inTrash)
        }
        if (root == null) return
        // Out of the space: kept, out of its root (the items above first, what they hold follows)
        val shown = view.mapTo(HashSet()) { it.id }
        val gone = before.filter { it.id !in shown }.associateBy { it.id }
        val outside = local[root]?.parent
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

    public companion object {
        /** The most spaces around an item, by default (see `docs/plans/shared-access.md`). */
        public const val MAX_NESTING: Int = 8
    }
}

/**
 * The value the library takes for each piece of an item held by several spaces (GRASP Pure
 * Fabrication, see `docs/plans/shared-access.md`): the one of the catalog with the latest stamp for
 * that piece, the place read as a folder of the library (the root of a space for an item at its
 * root). The other catalogs take it in turn, from the library, with stamps of their own.
 */
private class NestedPieces(held: List<Held>) {
    /** A catalog of a space, its root in the library ([root]: `null` for the library itself). */
    class Held(val root: String?, val catalog: SpaceCatalog) {
        val view: Map<String, CatalogEntry> = catalog.view().associateBy { it.id }
    }

    private val all = held

    /** The catalogs showing the item [id], with their item. */
    private fun holding(id: String): List<Pair<Held, CatalogItem>> = all.mapNotNull { held -> held.catalog[id]?.takeIf { id in held.view }?.let { held to it } }

    fun name(entry: CatalogEntry): String = holding(entry.id).maxByOrNull { it.second.named }?.let { (held, _) -> held.view.getValue(entry.id).name } ?: entry.name

    /** The folder of the library the item goes in: `null` for the root of the library. */
    fun parent(entry: CatalogEntry): String? = holding(entry.id).maxByOrNull { it.second.placed }?.let { (held, _) -> held.view.getValue(entry.id).parent ?: held.root }

    fun trashed(entry: CatalogEntry): Boolean = holding(entry.id).maxByOrNull { it.second.trashedVersion }?.let { (held, _) -> held.view.getValue(entry.id).trashed } ?: entry.trashed
}

/** A change of the space [spaceId] made on this device (see [SpaceLibrary.localOperations]). */
public data class SpaceOperation(val spaceId: String, val operation: CatalogOperation)
