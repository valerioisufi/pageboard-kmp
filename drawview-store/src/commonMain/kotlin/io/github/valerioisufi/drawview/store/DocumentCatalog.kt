package io.github.valerioisufi.drawview.store

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.PageBackground
import kotlinx.coroutines.flow.Flow

/**
 * A document as shown by the document list ([id]: [DrawDocument.id], the same on every device;
 * [isShared]: shared with other devices, see [SharingStore.sharingSecrets]; [pageCount]: its
 * pages; [hasPdf]: one of its pages holds a PDF).
 */
public data class DocumentSummary(
    val id: String,
    val name: String,
    val folderId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val lastOpenedAt: Long,
    val defaultWidthMm: Float = 210f,
    val defaultHeightMm: Float = 297f,
    val defaultBackground: PageBackground = PageBackground.Solid(),
    val isShared: Boolean = false,
    val pageCount: Int = 1,
    val hasPdf: Boolean = false
)

/**
 * A folder of the document library (`parentId == null` for the folders of the root).
 */
public data class FolderSummary(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long
)

/**
 * The documents and the folders of a library, as a library shows and organizes them (see
 * [DocumentStore]).
 */
public interface DocumentCatalog {
    /** Every document, most recently modified first (updated on every change). */
    public val documents: Flow<List<DocumentSummary>>

    /** Every folder, at any depth (updated on every change). */
    public val folders: Flow<List<FolderSummary>>

    /** Creates a document with one page of the given format in [folderId] and returns it. */
    public suspend fun createDocument(
        name: String,
        widthMm: Float = 210f,
        heightMm: Float = 297f,
        background: PageBackground = PageBackground.Solid(),
        folderId: String? = null
    ): DrawDocument

    /**
     * Whether the document [documentId] can be named [name]: no other document of its folder has
     * that name. A document is renamed by a change of it (see [OpenDocuments.rename]), so that the
     * devices it is shared with rename it too.
     */
    public suspend fun isDocumentNameAvailable(documentId: String, name: String): Boolean

    /** Deletes a document together with its resources (imported images and PDFs). */
    public suspend fun deleteDocument(documentId: String): Boolean

    /**
     * Moves a document into [folderId] (`null`: the root); `false` when the folder already holds
     * a document with the same name.
     */
    public suspend fun moveDocument(documentId: String, folderId: String?): Boolean

    public suspend fun updateLastOpened(documentId: String)

    /** Creates a folder in [parentId]; `false` when the parent already holds a folder with the same name. */
    public suspend fun createFolder(name: String, parentId: String?): Boolean

    /** Renames a folder; `false` when the parent already holds a folder with the same name. */
    public suspend fun renameFolder(folderId: String, newName: String): Boolean

    /** Deletes a folder with its whole content (sub-folders and documents). */
    public suspend fun deleteFolder(folderId: String): Boolean

    /**
     * Moves a folder into [parentId] (`null`: the root); `false` when the target is the folder
     * itself or one of its sub-folders, or already holds a folder with the same name.
     */
    public suspend fun moveFolder(folderId: String, parentId: String?): Boolean
}
