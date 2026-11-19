package io.github.valerioisufi.drawview.store

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.change.PageSnapshot
import io.github.valerioisufi.drawview.model.change.snapshot
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
// What a KeyValueDocumentStore keeps in its storage: the index, the folders, the documents, the proofs

@Serializable
internal data class StoredIndex(
    /** This device in the changes of the documents (see `DocumentSession.replica`). */
    val replicaId: String = "",
    val documents: List<StoredSummary> = emptyList(),
    val folders: List<StoredFolder> = emptyList(),
    /** The secrets of the documents shared, by document (see [DocumentStore.sharingSecrets]). */
    val sharingSecrets: Map<String, String> = emptyMap(),
    /** The documents shared to be read only here. */
    val readOnly: Set<String> = emptySet(),
    /** The operations no longer in the logs, by document (see [DocumentStore.compactOperations]), and their stamps. */
    val operationBases: Map<String, Map<String, Long>> = emptyMap(),
    val operationBaseStamps: Map<String, Map<String, Stamp>> = emptyMap()
) {
    /** What is in the trash and what is not (see [DocumentCatalog.trash]). */
    fun trash(): LibraryTrash = LibraryTrash(
        documents = documents.map { LibraryTrash.Entry(it.id, it.name, it.folderId, it.trashedAt, isShared = it.id in sharingSecrets) },
        folders = folders.map { LibraryTrash.Entry(it.id, it.name, it.parentId, it.trashedAt) }
    )
}

@Serializable
internal data class StoredFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    /** When it went to the trash itself (`null`: it did not; see [DocumentCatalog.trash]). */
    val trashedAt: Long? = null
) {
    fun toSummary() = FolderSummary(id = id, name = name, parentId = parentId, createdAt = createdAt, modifiedAt = modifiedAt)
}

@Serializable
internal data class StoredSummary(
    val id: String,
    val name: String,
    val folderId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val lastOpenedAt: Long,
    val defaultWidth: Float = 210f,
    val defaultHeight: Float = 297f,
    val defaultBackground: PageBackground = PageBackground.Solid(),
    val pageCount: Int = 1,
    val hasPdf: Boolean = false,
    /** When it went to the trash itself (`null`: it did not; see [DocumentCatalog.trash]). */
    val trashedAt: Long? = null
) {
    fun toSummary(isShared: Boolean) = DocumentSummary(
        id = id,
        name = name,
        folderId = folderId,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        lastOpenedAt = lastOpenedAt,
        defaultWidthMm = defaultWidth,
        defaultHeightMm = defaultHeight,
        defaultBackground = defaultBackground,
        isShared = isShared,
        pageCount = pageCount,
        hasPdf = hasPdf
    )
}

@Serializable
internal data class StoredDocument(
    val pages: List<PageSnapshot> = emptyList(),
    val resources: List<StoredResource> = emptyList(),
    val defaultsVersion: Stamp = Stamp.ZERO,
    val removedPages: List<PageSnapshot> = emptyList(),
    val titleVersion: Stamp = Stamp.ZERO
)

@Serializable
internal data class StoredResource(val id: String, val type: String, val uri: String, val hash: String = "")

/** The proofs of the operations of a document (see [OperationLog.saveProofs]), format [version]. */
@Serializable
internal data class StoredProofs(val version: Int = 1, val proofs: List<StoredProof> = emptyList())

/** The proof of the operation [replica] / [sequence], in Base64. */
@Serializable
internal data class StoredProof(val replica: String, val sequence: Long, val proof: String)


internal fun DrawDocument.toStored(): StoredDocument = StoredDocument(
    pages = pages.map { it.snapshot() },
    resources = resources.map { StoredResource(it.id, it.type.name, it.path, it.hash) },
    defaultsVersion = defaultsVersion,
    removedPages = removedPages.values.map { it.snapshot() },
    titleVersion = titleVersion
)

