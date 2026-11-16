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
 * A folder of the document library (`parentId == null` for the folders of the root); [isShared]:
 * it is shared with other devices with what it holds (the root of a shared folder, see
 * `docs/plans/shared-folders.md`).
 */
public data class FolderSummary(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val isShared: Boolean = false
)

/**
 * The documents and the folders of a library, as a library shows and organizes them (see
 * [DocumentStore]).
 *
 * What is deleted goes to the trash first ([trashDocument], [trashFolder]): it is out of
 * [documents] and [folders], in [trash], and can be put back ([restore]) until it is deleted for
 * good ([deleteDocument], [deleteFolder], [purgeTrash]). See `docs/plans/trash.md`.
 */
public interface DocumentCatalog {
    /**
     * Every document out of the trash (neither itself nor one of its folders in it), most recently
     * modified first (updated on every change).
     */
    public val documents: Flow<List<DocumentSummary>>

    /** Every folder out of the trash, at any depth (updated on every change). */
    public val folders: Flow<List<FolderSummary>>

    /** What is in the trash, the most recent first: the items deleted, not what they hold (updated on every change). */
    public val trash: Flow<List<TrashedItem>>

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

    /**
     * Moves a document to the trash, as it is; `false` when it is unknown or already there (itself
     * or one of its folders).
     */
    public suspend fun trashDocument(documentId: String): Boolean

    /**
     * Moves a folder to the trash with its whole content, as one item; `false` when it is unknown
     * or already there (itself or one of its parents).
     */
    public suspend fun trashFolder(folderId: String): Boolean

    /**
     * Puts the item [itemId] of the trash back into its folder, at the root when that folder is
     * gone or in the trash, renamed "name (2)", "name (3)"… when its name is taken there. Its name
     * once back, `null` when it is not an item of the trash.
     */
    public suspend fun restore(itemId: String): String?

    /**
     * Deletes for good the items that went to the trash before [before] (milliseconds), as
     * [deleteDocument] and [deleteFolder] do.
     */
    public suspend fun purgeTrash(before: Long)

    /** Deletes a document for good, together with its resources (imported images and PDFs). */
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

    /**
     * Deletes a folder for good with its whole content (sub-folders and documents), but what is in
     * the trash on its own: that stays in the trash, at its root.
     */
    public suspend fun deleteFolder(folderId: String): Boolean

    /**
     * Moves a folder into [parentId] (`null`: the root); `false` when the target is the folder
     * itself or one of its sub-folders, or already holds a folder with the same name.
     */
    public suspend fun moveFolder(folderId: String, parentId: String?): Boolean
}
