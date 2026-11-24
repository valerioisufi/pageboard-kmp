package io.github.valerioisufi.pageboard.store.space

import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.change.OperationId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A change of what a shared folder holds (see [SpaceCatalog]), as it is logged and exchanged.
 *
 * Each change applies itself piece by piece ([applyTo]): a piece takes it only when its stamp is
 * more recent than the one of the last change of that piece, so that the order the devices
 * receive the changes in does not matter. A piece takes the stamp even when its value stays the
 * same: otherwise a change between the two stamps would win on some devices only.
 */
@Serializable
public sealed interface CatalogChange {
    /** The items the change names. */
    public val ids: List<String>

    /**
     * Applies the change to [catalog] with [stamp] (the stamp of its operation).
     *
     * @return the change undoing it (which may change no value: the value was the same), or `null`
     *   when no piece took it (every piece had a more recent change).
     */
    public fun applyTo(catalog: SpaceCatalog, stamp: Stamp): CatalogChange?

    /** Whether the change is well formed: identifiers and names within bounds (what another device sends is checked). */
    public fun isWellFormed(): Boolean = ids.isNotEmpty() && ids.all { it.isNotEmpty() && it.length <= SpaceCatalog.MAX_ID_LENGTH }
}

/** Adds the folder [id], named [name], in [parent] (`null`: the root of the space), or brings it back in the space. */
@Serializable
@SerialName("addFolder")
public data class AddFolder(val id: String, val name: String, val parent: String?) : CatalogChange {
    override val ids: List<String> get() = listOfNotNull(id, parent)

    override fun applyTo(catalog: SpaceCatalog, stamp: Stamp): CatalogChange? =
        add(catalog, stamp, id, CatalogItemKind.FOLDER, name, parent)

    override fun isWellFormed(): Boolean = super.isWellFormed() && parent != id && name.length <= SpaceCatalog.MAX_NAME_LENGTH
}

/** Adds the document [id] in [folder] (`null`: the root of the space), or brings it back in the space. */
@Serializable
@SerialName("addDocument")
public data class AddDocument(val id: String, val folder: String?) : CatalogChange {
    override val ids: List<String> get() = listOfNotNull(id, folder)

    override fun applyTo(catalog: SpaceCatalog, stamp: Stamp): CatalogChange? =
        add(catalog, stamp, id, CatalogItemKind.DOCUMENT, null, folder)

    override fun isWellFormed(): Boolean = super.isWellFormed() && folder != id
}

/** Renames the folder [id]. */
@Serializable
@SerialName("renameFolder")
public data class RenameFolder(val id: String, val name: String) : CatalogChange {
    override val ids: List<String> get() = listOf(id)

    override fun applyTo(catalog: SpaceCatalog, stamp: Stamp): CatalogChange? {
        val item = catalog.itemOrNew(id)
        if (stamp <= item.named) return null
        catalog.put(item.copy(name = name, named = stamp))
        return RenameFolder(id, item.name)
    }

    override fun isWellFormed(): Boolean = super.isWellFormed() && name.length <= SpaceCatalog.MAX_NAME_LENGTH
}

/** Moves the item [id] (a folder or a document) into [parent] (`null`: the root of the space). */
@Serializable
@SerialName("moveItem")
public data class MoveItem(val id: String, val parent: String?) : CatalogChange {
    override val ids: List<String> get() = listOfNotNull(id, parent)

    override fun applyTo(catalog: SpaceCatalog, stamp: Stamp): CatalogChange? {
        val item = catalog.itemOrNew(id)
        if (stamp <= item.placed) return null
        catalog.put(item.copy(parent = parent, placed = stamp))
        return MoveItem(id, item.parent)
    }

    override fun isWellFormed(): Boolean = super.isWellFormed() && parent != id
}

/** Puts the item [id] in the trash, with what it holds. */
@Serializable
@SerialName("trashItem")
public data class TrashItem(val id: String) : CatalogChange {
    override val ids: List<String> get() = listOf(id)

    override fun applyTo(catalog: SpaceCatalog, stamp: Stamp): CatalogChange? {
        val before = catalog[id]?.trashed ?: false
        return if (!setTrashed(catalog, stamp, id, true)) null else if (before) TrashItem(id) else RestoreItem(id)
    }
}

/** Takes the item [id] out of the trash. */
@Serializable
@SerialName("restoreItem")
public data class RestoreItem(val id: String) : CatalogChange {
    override val ids: List<String> get() = listOf(id)

    override fun applyTo(catalog: SpaceCatalog, stamp: Stamp): CatalogChange? {
        val before = catalog[id]?.trashed ?: false
        return if (!setTrashed(catalog, stamp, id, false)) null else if (before) TrashItem(id) else RestoreItem(id)
    }
}

/**
 * Confirms [replica] as the one giving the item [id] to a device without it (see
 * [CatalogItem.giver] and `docs/plans/brought-by.md`): the replica that brought it, confirmed by the
 * owner of the space once it has the item. Taken from the owner only (the sharing of the space
 * says who it is). Adding the item again, or taking it out, does not change it.
 */
@Serializable
@SerialName("confirmGiver")
public data class ConfirmGiver(val id: String, val replica: String) : CatalogChange {
    override val ids: List<String> get() = listOf(id)

    override fun applyTo(catalog: SpaceCatalog, stamp: Stamp): CatalogChange? {
        val item = catalog.itemOrNew(id)
        if (stamp <= item.confirmed) return null
        catalog.put(item.copy(giver = replica, confirmed = stamp))
        return ConfirmGiver(id, item.giver)
    }

    override fun isWellFormed(): Boolean = super.isWellFormed() && replica.length <= SpaceCatalog.MAX_ID_LENGTH
}

/**
 * Takes the item [id] out of the space, with what it holds: every device keeps its copy, no longer
 * shared (see `docs/plans/shared-folders.md`).
 */
@Serializable
@SerialName("removeFromSpace")
public data class RemoveFromSpace(val id: String) : CatalogChange {
    override val ids: List<String> get() = listOf(id)

    override fun applyTo(catalog: SpaceCatalog, stamp: Stamp): CatalogChange? {
        val item = catalog.itemOrNew(id)
        var after = item
        // The latest removal: the additions up to it brought the item no more (an addition of this
        // operation, with the same stamp, came first: it counts, not this removal)
        if (stamp > item.removed && stamp !in item.arrivals) after = after.copy(removed = stamp, arrivals = item.arrivals.filter { it > stamp })
        var inverse: CatalogChange? = null
        if (stamp > item.presence) {
            // The whole presence, as one piece: a removal leaves no kind of an addition
            after = after.copy(kind = null, inSpace = false, presence = stamp)
            inverse = when {
                !item.inSpace -> RemoveFromSpace(id)
                item.kind == CatalogItemKind.FOLDER -> AddFolder(id, item.name, item.parent)
                else -> AddDocument(id, item.parent)
            }
        }
        after = after.withBringer()
        if (after != item) catalog.put(after)
        return inverse
    }
}

/**
 * Several changes made together (a folder added with what it holds), applied in their order with
 * the stamp of their operation: of two of them changing the same piece, the first one wins.
 */
@Serializable
@SerialName("catalogComposite")
public data class CompositeCatalogChange(val changes: List<CatalogChange>) : CatalogChange {
    override val ids: List<String> get() = changes.flatMap { it.ids }

    override fun applyTo(catalog: SpaceCatalog, stamp: Stamp): CatalogChange? {
        val inverses = changes.mapNotNull { it.applyTo(catalog, stamp) }
        return if (inverses.isEmpty()) null else CompositeCatalogChange(inverses.asReversed())
    }

    override fun isWellFormed(): Boolean =
        changes.isNotEmpty() && changes.size <= MAX_CHANGES && changes.all { it !is CompositeCatalogChange && it.isWellFormed() }

    public companion object {
        /** The most changes taken in one from another device. */
        public const val MAX_CHANGES: Int = 10_000
    }
}

/**
 * A change of a shared folder as it is logged and exchanged: who made it ([OperationId]), when
 * ([stamp]), and the change.
 */
@Serializable
public data class CatalogOperation(val id: OperationId, val stamp: Stamp, val change: CatalogChange)

/**
 * An addition: the presence (kind), the name of a folder and the place, each piece when the stamp
 * is more recent; an arrival when it is after the latest removal (who brought the item: the first
 * arrival, see [CatalogItem.arrivals]).
 */
private fun add(catalog: SpaceCatalog, stamp: Stamp, id: String, kind: CatalogItemKind, name: String?, parent: String?): CatalogChange? {
    val before = catalog.itemOrNew(id)
    var item = before
    val undo = mutableListOf<CatalogChange>()
    // Not after a removal of this operation (the same stamp): the removal came first
    if (stamp > item.removed && stamp !in item.arrivals) item = item.copy(arrivals = (item.arrivals + stamp).sorted())
    if (stamp > item.presence) {
        item = item.copy(kind = kind, inSpace = true, presence = stamp)
        undo.add(if (before.inSpace) before.addition() else RemoveFromSpace(id))
    }
    item = item.withBringer()
    if (name != null && stamp > item.named) {
        item = item.copy(name = name, named = stamp)
        undo.add(RenameFolder(id, before.name))
    }
    if (stamp > item.placed) {
        item = item.copy(parent = parent, placed = stamp)
        undo.add(MoveItem(id, before.parent))
    }
    if (item != before) catalog.put(item)
    // An arrival alone changes no piece the library shows: nothing to undo
    if (undo.isEmpty()) return null
    return undo.singleOrNull() ?: CompositeCatalogChange(undo.asReversed())
}

private fun setTrashed(catalog: SpaceCatalog, stamp: Stamp, id: String, trashed: Boolean): Boolean {
    val item = catalog.itemOrNew(id)
    if (stamp <= item.trashedVersion) return false
    catalog.put(item.copy(trashed = trashed, trashedVersion = stamp))
    return true
}

/** The change adding this item as it is (the inverse of a change of its presence). */
private fun CatalogItem.addition(): CatalogChange =
    if (kind == CatalogItemKind.FOLDER) AddFolder(id, name, parent) else AddDocument(id, parent)
