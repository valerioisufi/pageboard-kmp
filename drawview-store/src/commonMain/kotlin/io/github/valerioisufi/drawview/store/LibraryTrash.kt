package io.github.valerioisufi.drawview.store

/**
 * A document or a folder in the trash, as the Trash of a library shows it (see
 * [DocumentCatalog.trash]).
 *
 * [folderId] and [folderName] are the folder it was in (`null`: the root); [trashedAt] the time it
 * went to the trash, in milliseconds; [documentIds] the documents it holds, at any depth (itself
 * for a document), but the ones in the trash on their own, which are items of their own;
 * [isShared]: one of them is shared with other devices.
 */
public data class TrashedItem(
    val id: String,
    val kind: Kind,
    val name: String,
    val folderId: String?,
    val folderName: String?,
    val trashedAt: Long,
    val documentIds: Set<String>,
    val isShared: Boolean = false
) {
    /** What the item is. */
    public enum class Kind { DOCUMENT, FOLDER }
}

/** How long the trash keeps what is deleted before it is deleted for good. */
public object TrashRetention {
    /** 30 days, in milliseconds. */
    public const val MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    /** The time before which what went to the trash is deleted for good, at [now]. */
    public fun expiredBefore(now: Long): Long = now - MILLIS
}

/**
 * How the trash splits a library: what is out of it, the items in it, where an item goes back and
 * under which name. Built from every document and folder of a store, each with the time it went
 * to the trash (`null`: not itself in it); shared by the stores.
 *
 * A folder in the trash keeps its content as it is: what it holds is out of the library because
 * of it, and goes back with it. A document or a folder that went to the trash on its own, before
 * its folder, stays an item of its own with its own date.
 */
public class LibraryTrash(documents: Collection<Entry>, folders: Collection<Entry>) {

    /**
     * A document or a folder of the library: [parentId] is its folder (`null`: the root),
     * [trashedAt] the time it went to the trash itself, [isShared] for a document shared.
     */
    public data class Entry(
        val id: String,
        val name: String,
        val parentId: String?,
        val trashedAt: Long? = null,
        val isShared: Boolean = false
    )

    private val documentsById = documents.associateBy { it.id }
    private val foldersById = folders.associateBy { it.id }

    /** The folders out of the trash: neither themselves nor one of their parents in it. */
    private val liveFolders: Set<String> = buildSet {
        val known = mutableMapOf<String, Boolean>()
        fun live(id: String, depth: Int): Boolean = known.getOrPut(id) {
            val folder = foldersById[id] ?: return@getOrPut false
            // A cycle cannot be made by moving, but a store read cannot be trusted to be without one
            folder.trashedAt == null && depth < foldersById.size &&
                (folder.parentId == null || live(folder.parentId, depth + 1))
        }
        for (id in foldersById.keys) if (live(id, 0)) add(id)
    }

    /** Whether [folderId] is out of the trash (`null`, the root, always is; an unknown folder is not). */
    public fun isFolderLive(folderId: String?): Boolean = folderId == null || folderId in liveFolders

    /** Whether the document [documentId] is out of the trash. */
    public fun isDocumentLive(documentId: String): Boolean {
        val document = documentsById[documentId] ?: return false
        return document.trashedAt == null && isFolderLive(document.parentId)
    }

    /** What is in the trash, the most recent first. */
    public val items: List<TrashedItem> by lazy {
        val children = foldersById.values.groupBy { it.parentId }
        val documentsIn = documentsById.values.groupBy { it.parentId }
        fun heldBy(folderId: String, into: MutableSet<String>, depth: Int) {
            if (depth > foldersById.size) return
            documentsIn[folderId].orEmpty().filter { it.trashedAt == null }.mapTo(into) { it.id }
            children[folderId].orEmpty().filter { it.trashedAt == null }.forEach { heldBy(it.id, into, depth + 1) }
        }
        val trashedDocuments = documentsById.values.filter { it.trashedAt != null }.map { document ->
            item(document, TrashedItem.Kind.DOCUMENT, setOf(document.id))
        }
        val trashedFolders = foldersById.values.filter { it.trashedAt != null }.map { folder ->
            item(folder, TrashedItem.Kind.FOLDER, mutableSetOf<String>().also { heldBy(folder.id, it, 0) })
        }
        (trashedDocuments + trashedFolders).sortedWith(compareByDescending<TrashedItem> { it.trashedAt }.thenBy { it.id })
    }

    private fun item(entry: Entry, kind: TrashedItem.Kind, documentIds: Set<String>) = TrashedItem(
        id = entry.id,
        kind = kind,
        name = entry.name,
        folderId = entry.parentId,
        folderName = entry.parentId?.let { foldersById[it]?.name },
        trashedAt = entry.trashedAt ?: 0L,
        documentIds = documentIds,
        isShared = documentIds.any { documentsById[it]?.isShared == true }
    )

    /** The item [id] of the trash, if it is one. */
    public fun item(id: String): TrashedItem? = items.firstOrNull { it.id == id }

    /**
     * The items inside the folder [folderId], at any depth, not inside another item: when the
     * folder is deleted for good they stay in the trash, at its root.
     */
    public fun itemsInside(folderId: String): List<TrashedItem> = items.filter { item ->
        var parent = item.folderId
        var depth = 0
        while (parent != null && depth++ <= foldersById.size) {
            if (parent == folderId) return@filter true
            val folder = foldersById[parent] ?: return@filter false
            if (folder.trashedAt != null) return@filter false
            parent = folder.parentId
        }
        false
    }

    /** Where [item] goes back: its folder when it is out of the trash, the root otherwise. */
    public fun restoreTarget(item: TrashedItem): String? = item.folderId?.takeIf { isFolderLive(it) }

    /**
     * [name], or the first of "name (2)", "name (3)"… not taken in [folderId] by another document
     * out of the trash than [documentId].
     */
    public fun availableDocumentName(name: String, folderId: String?, documentId: String): String =
        availableName(name, documentsById.values.filter { it.id != documentId && it.parentId == folderId && isDocumentLive(it.id) }.map { it.name })

    /**
     * [name], or the first of "name (2)", "name (3)"… not taken in [parentId] by another folder out
     * of the trash than [folderId].
     */
    public fun availableFolderName(name: String, parentId: String?, folderId: String): String =
        availableName(name, foldersById.values.filter { it.id != folderId && it.parentId == parentId && it.id in liveFolders }.map { it.name })

    private fun availableName(name: String, taken: List<String>): String {
        val names = taken.toSet()
        if (name !in names) return name
        var number = 2
        while ("$name ($number)" in names) number++
        return "$name ($number)"
    }
}
