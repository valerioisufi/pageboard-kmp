package io.github.valerioisufi.drawview.store.space

import io.github.valerioisufi.drawview.model.Stamp
import kotlinx.serialization.Serializable

/** What an item of a shared folder is. */
@Serializable
public enum class CatalogItemKind { FOLDER, DOCUMENT }

/**
 * An item of a shared folder (a space, see `docs/plans/shared-folders.md`) with every piece of it and
 * the stamp of its last change (last-writer-wins, as the pieces of a document):
 *
 * * its presence in the space ([inSpace], [kind], [broughtBy]: the replica that added it, the one
 *   the others take a document from), changed by [AddFolder], [AddDocument] and [RemoveFromSpace];
 * * its [name] (a folder: the title of a document stays in the document), changed by
 *   [RenameFolder] and [AddFolder];
 * * its [parent] (a folder of the space, `null` for the root of the space), changed by [MoveItem]
 *   and the additions;
 * * whether it is in the trash ([trashed]), changed by [TrashItem] and [RestoreItem].
 *
 * An item a change names before its addition arrives (the changes of two devices travel apart)
 * is kept with the pieces it changed: [kind] is `null` until it is added, and it is not shown. An
 * item out of the space has no [kind] nor [broughtBy] either: the presence is one piece.
 */
@Serializable
public data class CatalogItem(
    val id: String,
    val kind: CatalogItemKind? = null,
    val inSpace: Boolean = false,
    val broughtBy: String = "",
    val presence: Stamp = Stamp.ZERO,
    val name: String = "",
    val named: Stamp = Stamp.ZERO,
    val parent: String? = null,
    val placed: Stamp = Stamp.ZERO,
    val trashed: Boolean = false,
    val trashedVersion: Stamp = Stamp.ZERO
)

/**
 * An item as the library shows it: in the space, its [parent] as the tree of the space puts it
 * (see [SpaceCatalog.view]), [trashed] when it is in the trash itself (not through a folder).
 */
public data class CatalogEntry(
    val id: String,
    val kind: CatalogItemKind,
    val name: String,
    val parent: String?,
    val trashed: Boolean,
    val broughtBy: String
)

/**
 * What a shared folder holds (GRASP Information Expert), replicated on every member: its folders
 * and its documents as [CatalogItem]s, changed piece by piece by [CatalogChange]s, so that the
 * devices applying the same changes in any order end up with the same items.
 *
 * Nothing is destroyed: an item out of the space stays here, hidden, with the stamp of its
 * removal.
 */
public class SpaceCatalog(items: Collection<CatalogItem> = emptyList()) {
    private val byId = items.associateByTo(LinkedHashMap()) { it.id }

    /** Every item, in the space or not, by identifier. */
    public val items: Map<String, CatalogItem> get() = byId.toMap()

    public operator fun get(id: String): CatalogItem? = byId[id]

    /** The item [id], made when a change names it first (see [CatalogItem]). */
    internal fun itemOrNew(id: String): CatalogItem = byId[id] ?: CatalogItem(id)

    internal fun put(item: CatalogItem) {
        byId[item.id] = item
    }

    /** The latest stamp of the catalog: a session stamps its next changes after it. */
    public fun latestStamp(): Stamp = byId.values.maxOfOrNull { maxOf(it.presence, it.named, it.placed, it.trashedVersion) } ?: Stamp.ZERO

    /**
     * The items in the space as the library shows them, the same on every device with the same
     * items:
     *
     * * an item is shown when it is in the space and every folder above it is too, up to the
     *   root of the space (an item in a folder moved out of the space, or not arrived yet, is not);
     * * moves made apart may make a cycle (A in B on a device, B in A on another): the folder of
     *   the cycle moved most recently is put at the root of the space;
     * * an item in the trash keeps its place: what it holds is in the trash with it.
     */
    public fun view(): List<CatalogEntry> {
        val folders = byId.values.filter { it.inSpace && it.kind == CatalogItemKind.FOLDER }.associateBy { it.id }
        val parents = HashMap<String, String?>()
        for (folder in folders.values) parents[folder.id] = folder.parent
        breakCycles(parents, folders)

        // Whether a folder reaches the root of the space through folders of the space
        val reaches = HashMap<String, Boolean>()
        fun reachesRoot(id: String): Boolean {
            reaches[id]?.let { return it }
            val chain = mutableListOf<String>()
            var current: String? = id
            var result = false
            while (true) {
                val known = current?.let { reaches[it] }
                if (current == null) { result = true; break }
                if (known != null) { result = known; break }
                if (current !in folders) { result = false; break }
                chain.add(current)
                current = parents[current]
            }
            for (it in chain) reaches[it] = result
            return result
        }

        return byId.values.mapNotNull { item ->
            val kind = item.kind ?: return@mapNotNull null
            if (!item.inSpace) return@mapNotNull null
            val parent = if (kind == CatalogItemKind.FOLDER) parents[item.id] else item.parent
            if (parent != null && !reachesRoot(parent)) return@mapNotNull null
            CatalogEntry(item.id, kind, item.name, parent, item.trashed, item.broughtBy)
        }.sortedBy { it.id }
    }

    /** Puts at the root the folder moved most recently of each cycle of [parents], until none is left. */
    private fun breakCycles(parents: HashMap<String, String?>, folders: Map<String, CatalogItem>) {
        val done = HashSet<String>()
        for (start in folders.keys.sorted()) {
            if (start in done) continue
            val path = LinkedHashSet<String>()
            var current: String? = start
            while (current != null && current in folders && current !in done) {
                if (!path.add(current)) {
                    // A cycle, from current back to current
                    val cycle = path.dropWhile { it != current }
                    val latest = cycle.maxBy { folders.getValue(it).placed }
                    parents[latest] = null
                    // Walk again from the start, the cycle broken
                    path.clear()
                    current = start
                    continue
                }
                current = parents[current]
            }
            done.addAll(path)
        }
    }

    public companion object {
        /** The longest name of a folder taken from another device. */
        public const val MAX_NAME_LENGTH: Int = 512

        /** The longest identifier of an item taken from another device. */
        public const val MAX_ID_LENGTH: Int = 64
    }
}
