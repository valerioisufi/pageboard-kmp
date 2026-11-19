package io.github.valerioisufi.drawview.store.space

import io.github.valerioisufi.drawview.model.change.OperationId

/**
 * A shared folder (a space, see `docs/plans/shared-folders.md`) as this device keeps it: its root in
 * the library ([rootFolderId]: a folder, `null` for the library itself), its sharing ([sharing]:
 * opaque text, the `SharingState` of `pageboard-sync`, a secret) and whether it may only be read
 * here ([readOnly]).
 */
public data class StoredSpace(
    val id: String,
    val rootFolderId: String?,
    val sharing: String = "",
    val readOnly: Boolean = false
)

/**
 * Where the spaces, their catalogs and the logs of their changes are kept (GRASP Protected
 * Variations: the Room store and the key-value store implement it), and the writes of the library
 * made by other devices through a space.
 *
 * Removing a space ([removeSpace]) forgets its catalog and its log, never the folders and the
 * documents of the library: every device keeps its copies.
 */
public interface SpaceStore {
    /** Every space this device shares or has joined. */
    public suspend fun spaces(): List<StoredSpace>

    /** Keeps [space] (a new one, or its root, sharing or role changed). */
    public suspend fun saveSpace(space: StoredSpace)

    /** Forgets the space [spaceId], its catalog, its log and its proofs. */
    public suspend fun removeSpace(spaceId: String)

    /** The items of the catalog of [spaceId], in the space or not. */
    public suspend fun catalogItems(spaceId: String): List<CatalogItem>

    /**
     * Keeps the change [operation] of [spaceId] (made here or received) with the [items] it
     * changed, both or neither.
     */
    public suspend fun saveCatalogChange(spaceId: String, items: Collection<CatalogItem>, operation: CatalogOperation)

    /** The last operation kept of every replica, for [spaceId]. */
    public suspend fun catalogVersionVector(spaceId: String): Map<String, Long>

    /** The operations of [spaceId] after [known] (a version vector), in the order of their stamps. */
    public suspend fun catalogOperationsAfter(spaceId: String, known: Map<String, Long>): List<CatalogOperation>

    /** The proofs kept of the operations [ids] of [spaceId] (see `OperationLog.proofs`). */
    public suspend fun catalogProofs(spaceId: String, ids: Collection<OperationId>): Map<OperationId, ByteArray>

    /** Keeps the proofs of operations of [spaceId], to give them on (see `OperationLog.saveProofs`). */
    public suspend fun saveCatalogProofs(spaceId: String, proofs: Map<OperationId, ByteArray>)

    /**
     * Makes the folder [folderId] named [name] in [parentId], or names and places it so: a write
     * of another device, taken as it is (two folders of the same name may then be side by side).
     */
    public suspend fun putFolder(folderId: String, name: String, parentId: String?)

    /** Places the document [documentId] in [folderId], as another device did; `false`: no such document here. */
    public suspend fun putDocumentFolder(documentId: String, folderId: String?): Boolean

    /** Puts the item [itemId] (a folder or a document) in the trash or takes it out, in its place, as another device did. */
    public suspend fun putTrashed(itemId: String, trashed: Boolean)
}
