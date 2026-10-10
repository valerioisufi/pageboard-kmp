package io.github.valerioisufi.drawview.data

import io.github.valerioisufi.drawview.core.change.Operation
import io.github.valerioisufi.drawview.core.controller.DrawPersistence
import io.github.valerioisufi.drawview.core.controller.DrawPreferences
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.Stamp
import kotlinx.coroutines.flow.Flow

/**
 * A document as shown by the document list ([id]: [DrawDocument.id], the same on every device;
 * [isShared]: shared with other devices, see [DocumentStore.sharingSecrets]; [pageCount]: its
 * pages; [hasPdf]: one of its pages holds a PDF).
 */
data class DocumentSummary(
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
data class FolderSummary(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long
)

/**
 * Storage of the documents, of their folders and of the user preferences used by the
 * applications.
 *
 * Backed by Room (SQLite): see [RoomDocumentStore].
 */
interface DocumentStore {
    /** Every document, most recently modified first (updated on every change). */
    val documents: Flow<List<DocumentSummary>>

    /** Every folder, at any depth (updated on every change). */
    val folders: Flow<List<FolderSummary>>

    /** Global user preferences of the editor (defaults when nothing is stored yet). */
    val preferences: Flow<DrawPreferences>

    /** Persistence port of the editor: saves every change of the open documents. */
    val persistence: DrawPersistence

    /** Creates a document with one page of the given format in [folderId] and returns it. */
    suspend fun createDocument(
        name: String,
        widthMm: Float = 210f,
        heightMm: Float = 297f,
        background: PageBackground = PageBackground.Solid(),
        folderId: String? = null
    ): DrawDocument

    /**
     * Stores [document] with its whole content (removed pages and elements included) in
     * [folderId]: a document received from another device (see `DocumentSnapshot`).
     */
    suspend fun insertDocument(document: DrawDocument, folderId: String? = null, versionVector: Map<String, Long> = emptyMap())

    /** Loads the whole content of a document, or `null` when it does not exist. */
    suspend fun loadDocument(documentId: String): DrawDocument?

    /**
     * Whether the document [documentId] can be named [name]: no other document of its folder has
     * that name. A document is renamed by a change of it (see [OpenDocuments.rename]), so that the
     * devices it is shared with rename it too.
     */
    suspend fun isDocumentNameAvailable(documentId: String, name: String): Boolean

    /** Deletes a document together with its resources (imported images and PDFs). */
    suspend fun deleteDocument(documentId: String): Boolean

    /**
     * Moves a document into [folderId] (`null`: the root); `false` when the folder already holds
     * a document with the same name.
     */
    suspend fun moveDocument(documentId: String, folderId: String?): Boolean

    suspend fun updateLastOpened(documentId: String)

    /** Creates a folder in [parentId]; `false` when the parent already holds a folder with the same name. */
    suspend fun createFolder(name: String, parentId: String?): Boolean

    /** Renames a folder; `false` when the parent already holds a folder with the same name. */
    suspend fun renameFolder(folderId: String, newName: String): Boolean

    /** Deletes a folder with its whole content (sub-folders and documents). */
    suspend fun deleteFolder(folderId: String): Boolean

    /**
     * Moves a folder into [parentId] (`null`: the root); `false` when the target is the folder
     * itself or one of its sub-folders, or already holds a folder with the same name.
     */
    suspend fun moveFolder(folderId: String, parentId: String?): Boolean

    /** This device in the changes of the documents (see `DocumentSession.replica`). */
    suspend fun replicaId(): String

    /** The last operation of every replica in the log of the document [documentId]. */
    suspend fun versionVector(documentId: String): Map<String, Long>

    /** The operations of the document [documentId] after [known] (a version vector), in stamp order. */
    suspend fun operationsAfter(documentId: String, known: Map<String, Long>): List<Operation>

    /**
     * Removes from the log of [documentId] its operations up to [upTo] (a version vector: the ones
     * every device working on it has), still counted in its [versionVector]: the stamps of the last
     * operations removed, by replica.
     */
    suspend fun compactOperations(documentId: String, upTo: Map<String, Long>): Map<String, Stamp>

    /** The last operation of every replica no longer in the log of [documentId] (see [compactOperations]). */
    suspend fun compactedOperations(documentId: String): Map<String, Long>

    /**
     * The secrets of the documents shared with other devices (written as text by `drawview-sync`),
     * by document: whoever has the secret of a document can read and change it.
     */
    suspend fun sharingSecrets(): Map<String, String>

    /**
     * Shares the document [documentId] with [secret], or stops sharing it (`null`); [readOnly]:
     * this device may only read it (see `DocumentSession.isReadOnly`).
     */
    suspend fun setSharingSecret(documentId: String, secret: String?, readOnly: Boolean = false)

    /** Whether this device may only read the document [documentId] (see [setSharingSecret]). */
    suspend fun isReadOnly(documentId: String): Boolean

    /** Writes the pending changes (stores that write lazily). */
    suspend fun flush() {}

    /** Called when the editing session of a document ends (after [flush]). */
    fun closeDocument(documentId: String) {}
}

/**
 * Process-wide document store: the database must not be opened more than once.
 */
object DrawViewStore {
    val default: DocumentStore by lazy { createPlatformDocumentStore() }

    /**
     * The documents of [default] being changed (see [OpenDocuments]), on the main thread: shared
     * by the editor and the synchronization with the other devices.
     */
    val documents: OpenDocuments by lazy { OpenDocuments(default) }
}
