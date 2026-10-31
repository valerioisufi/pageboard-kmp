package io.github.valerioisufi.drawview.store

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.StrokeModeller
import io.github.valerioisufi.drawview.model.change.DocumentPersistence

/**
 * Storage of the documents: their library ([DocumentCatalog]), their content and the persistence
 * of their changes, the log of their operations ([OperationLog]), their sharing ([SharingStore]).
 * The stores of DrawView keep the preferences of the applications too ([PreferenceStore]).
 *
 * Implemented in memory or on any key-value storage by [KeyValueDocumentStore], with Room by
 * `RoomDocumentStore` (`drawview-store-room`); [OpenDocuments] opens their documents for the
 * editor and the synchronization.
 */
public interface DocumentStore : DocumentCatalog, OperationLog, SharingStore {
    /** Persistence port of the documents: saves every change of the open documents. */
    public val persistence: DocumentPersistence

    /** Models the strokes of the documents loaded or received (see [DrawDocument.strokeModeller]). */
    public val strokeModeller: StrokeModeller

    /**
     * Stores [document] with its whole content (removed pages and elements included) in
     * [folderId]: a document received from another device (see `DocumentSnapshot`).
     */
    public suspend fun insertDocument(document: DrawDocument, folderId: String? = null, versionVector: Map<String, Long> = emptyMap())

    /** Loads the whole content of a document, or `null` when it does not exist. */
    public suspend fun loadDocument(documentId: String): DrawDocument?

    /**
     * What can be read of a document without modeling its strokes (see [DocumentOutline]), or
     * `null` when it does not exist: from [loadDocument] by default, from its texts, PDF pages and
     * elements of the applications alone in the stores of DrawView.
     */
    public suspend fun loadOutline(documentId: String): DocumentOutline? =
        loadDocument(documentId)?.let { DocumentOutline.of(it) }

    /**
     * A text that changes whenever the page at [index] of the document [documentId] (among its
     * pages shown, see [DrawDocument.pages]) changes as it is drawn, or `null` when there is no
     * such page: the hash of its identifier, format and background, of the stamps of every element
     * shown on it and of the files they show. From [loadDocument] by default, read without
     * modeling anything in the stores of DrawView.
     */
    public suspend fun pageFingerprint(documentId: String, index: Int): String? =
        loadDocument(documentId)?.pageFingerprint(index)

    /**
     * The page at [index] of the document [documentId] (among its pages shown) loaded alone, to be
     * drawn (see [LoadedPage]), or `null` when there is no such page: from [loadDocument] by
     * default, without the other pages and the elements removed in the stores of DrawView.
     */
    public suspend fun loadPage(documentId: String, index: Int): LoadedPage? =
        loadDocument(documentId)?.let { LoadedPage.of(it, index) }

    /** Writes the pending changes (stores that write lazily). */
    public suspend fun flush() {}

    /** Called when the editing session of a document ends (after [flush]). */
    public fun closeDocument(documentId: String) {}
}
