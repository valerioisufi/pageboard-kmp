package io.github.valerioisufi.drawview.data

import io.github.valerioisufi.drawview.core.controller.DrawPersistence
import io.github.valerioisufi.drawview.core.controller.DrawPreferences
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.PageBackground
import kotlinx.coroutines.flow.Flow

/**
 * A document as shown by the document list.
 */
data class DocumentSummary(
    val id: Int,
    val name: String,
    val folderId: Int? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val lastOpenedAt: Long,
    val defaultWidthMm: Float = 210f,
    val defaultHeightMm: Float = 297f,
    val defaultBackground: PageBackground = PageBackground.Solid()
)

/**
 * Storage of the documents and of the user preferences used by the applications.
 *
 * Backed by Room (SQLite) on Android, desktop and iOS, and by the browser `localStorage` on the
 * web, where Room is not available.
 */
interface DocumentStore {
    /** Every document, most recently modified first (updated on every change). */
    val documents: Flow<List<DocumentSummary>>

    /** Global user preferences of the editor (defaults when nothing is stored yet). */
    val preferences: Flow<DrawPreferences>

    /** Persistence port of the editor: saves every change of the open documents. */
    val persistence: DrawPersistence

    /** Creates a document with one page of the given format and returns it. */
    suspend fun createDocument(
        name: String,
        widthMm: Float = 210f,
        heightMm: Float = 297f,
        background: PageBackground = PageBackground.Solid()
    ): DrawDocument

    /** Loads the whole content of a document, or `null` when it does not exist. */
    suspend fun loadDocument(documentId: Int): DrawDocument?

    /** Renames a document; `false` when the name is already used by another document. */
    suspend fun renameDocument(documentId: Int, newName: String): Boolean

    /** Deletes a document together with its resources (imported images and PDFs). */
    suspend fun deleteDocument(documentId: Int): Boolean

    suspend fun updateLastOpened(documentId: Int)

    /** Writes the pending changes (stores that write lazily). */
    suspend fun flush() {}

    /** Called when the editing session of a document ends (after [flush]). */
    fun closeDocument(documentId: Int) {}
}

/**
 * Creates the default [DocumentStore] of the platform.
 */
expect fun createPlatformDocumentStore(): DocumentStore

/**
 * Process-wide document store: the database must not be opened more than once.
 */
object DrawViewStore {
    val default: DocumentStore by lazy { createPlatformDocumentStore() }
}
