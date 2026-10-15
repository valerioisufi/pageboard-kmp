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

    /** Writes the pending changes (stores that write lazily). */
    public suspend fun flush() {}

    /** Called when the editing session of a document ends (after [flush]). */
    public fun closeDocument(documentId: String) {}
}
