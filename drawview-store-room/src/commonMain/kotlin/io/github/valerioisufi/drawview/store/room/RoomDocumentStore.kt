package io.github.valerioisufi.drawview.store.room

import io.github.valerioisufi.drawview.ink.InkStrokeModeller
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.StrokeModeller
import io.github.valerioisufi.drawview.model.change.DocumentPersistence
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import io.github.valerioisufi.drawview.store.DocumentOutline
import io.github.valerioisufi.drawview.store.DocumentStore
import io.github.valerioisufi.drawview.store.DocumentSummary
import io.github.valerioisufi.drawview.store.FolderSummary
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.store.PreferenceStore
import io.github.valerioisufi.drawview.store.TrashedItem
import io.github.valerioisufi.drawview.store.room.db.DocumentEntity
import io.github.valerioisufi.drawview.store.room.db.DrawDatabase
import io.github.valerioisufi.drawview.store.room.db.DrawViewDatabase
import io.github.valerioisufi.drawview.store.room.db.FolderEntity
import io.github.valerioisufi.drawview.store.room.db.SharedDocumentEntity
import io.github.valerioisufi.drawview.store.room.repository.DrawDocumentRepository
import io.github.valerioisufi.drawview.store.room.repository.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [DocumentStore] backed by the Room database (Android, desktop and iOS).
 */
public class RoomDocumentStore(
    public val database: DrawDatabase,
    override val strokeModeller: StrokeModeller = InkStrokeModeller
) : DocumentStore, PreferenceStore {
    internal val repository = DrawDocumentRepository(database, strokeModeller)
    internal val fileRepository = FileRepository(database)

    private val sharedDocuments = database.sharedDocumentDao()

    override val documents: Flow<List<DocumentSummary>> =
        combine(fileRepository.getAllDocumentsWithContentFlow(), fileRepository.getAllFoldersFlow(), sharedDocuments.getIdsFlow()) { list, folders, shared ->
            val sharedIds = shared.toSet()
            val trash = FileRepository.trashOf(list.map { it.document }, folders, sharedIds)
            list.filter { trash.isDocumentLive(it.document.id) }
                .map { it.document.toSummary(isShared = it.document.id in sharedIds, pageCount = it.pageCount, hasPdf = it.hasPdf) }
        }

    override val folders: Flow<List<FolderSummary>> =
        combine(fileRepository.getAllDocumentsFlow(), fileRepository.getAllFoldersFlow()) { documents, list ->
            val trash = FileRepository.trashOf(documents, list, emptySet())
            list.filter { trash.isFolderLive(it.id) }.map { it.toSummary() }
        }

    override val trash: Flow<List<TrashedItem>> =
        combine(fileRepository.getAllDocumentsFlow(), fileRepository.getAllFoldersFlow(), sharedDocuments.getIdsFlow()) { documents, folders, shared ->
            FileRepository.trashOf(documents, folders, shared.toSet()).items
        }


    override val persistence: DocumentPersistence get() = repository

    override suspend fun preference(key: String): String? = repository.preference(key)

    override suspend fun setPreference(key: String, value: String?): Unit = repository.setPreference(key, value)

    override suspend fun createDocument(
        name: String,
        widthMm: Float,
        heightMm: Float,
        background: PageBackground,
        folderId: String?
    ): DrawDocument = repository.createDocument(name, widthMm, heightMm, background, folderId)

    override suspend fun insertDocument(document: DrawDocument, folderId: String?, versionVector: Map<String, Long>): Unit =
        repository.insertDocument(document, folderId, versionVector)

    override suspend fun loadDocument(documentId: String): DrawDocument? = repository.loadDocument(documentId)

    override suspend fun loadOutline(documentId: String): DocumentOutline? = repository.loadOutline(documentId)

    override suspend fun isDocumentNameAvailable(documentId: String, name: String): Boolean =
        fileRepository.isDocumentNameAvailable(documentId, name)

    override suspend fun trashDocument(documentId: String): Boolean = fileRepository.trashDocument(documentId)

    override suspend fun trashFolder(folderId: String): Boolean = fileRepository.trashFolder(folderId)

    override suspend fun restore(itemId: String): String? = fileRepository.restore(itemId)

    override suspend fun purgeTrash(before: Long): Unit = fileRepository.purgeTrash(before)

    override suspend fun deleteDocument(documentId: String): Boolean = fileRepository.deleteDocument(documentId)

    override suspend fun moveDocument(documentId: String, folderId: String?): Boolean =
        fileRepository.moveDocument(documentId, folderId)

    override suspend fun updateLastOpened(documentId: String): Unit =
        fileRepository.updateLastOpened(documentId, currentTimeMillis())

    override suspend fun replicaId(): String = repository.replicaId()

    override suspend fun versionVector(documentId: String): Map<String, Long> = repository.versionVector(documentId)

    override suspend fun operationsAfter(documentId: String, known: Map<String, Long>): List<Operation> =
        repository.operationsAfter(documentId, known)

    override suspend fun compactOperations(documentId: String, upTo: Map<String, Long>): Map<String, Stamp> =
        repository.compactOperations(documentId, upTo)

    override suspend fun compactedOperations(documentId: String): Map<String, Long> = repository.compactedOperations(documentId)

    override suspend fun proofs(documentId: String, ids: Collection<OperationId>): Map<OperationId, ByteArray> = repository.proofs(documentId, ids)

    override suspend fun saveProofs(documentId: String, proofs: Map<OperationId, ByteArray>): Unit = repository.saveProofs(documentId, proofs)

    override suspend fun sharingSecrets(): Map<String, String> = withContext(Dispatchers.IO) {
        sharedDocuments.getAll().associate { it.documentId to it.secret }
    }

    override suspend fun setSharingSecret(documentId: String, secret: String?, readOnly: Boolean): Unit = withContext(Dispatchers.IO) {
        if (secret == null) sharedDocuments.delete(documentId) else sharedDocuments.upsert(SharedDocumentEntity(documentId, secret, readOnly))
    }

    override suspend fun isReadOnly(documentId: String): Boolean = withContext(Dispatchers.IO) { sharedDocuments.isReadOnly(documentId) == true }

    override suspend fun createFolder(name: String, parentId: String?): Boolean = fileRepository.createFolder(name, parentId)

    override suspend fun renameFolder(folderId: String, newName: String): Boolean =
        fileRepository.renameFolder(folderId, newName)

    override suspend fun deleteFolder(folderId: String): Boolean = fileRepository.deleteFolder(folderId)

    override suspend fun moveFolder(folderId: String, parentId: String?): Boolean = fileRepository.moveFolder(folderId, parentId)
}

internal fun DocumentEntity.toSummary(isShared: Boolean = false, pageCount: Int = 1, hasPdf: Boolean = false) = DocumentSummary(
    id = id,
    name = name,
    folderId = folderId,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    lastOpenedAt = lastOpenedAt ?: modifiedAt,
    defaultWidthMm = defaultWidth,
    defaultHeightMm = defaultHeight,
    defaultBackground = defaultBackground,
    isShared = isShared,
    pageCount = pageCount,
    hasPdf = hasPdf
)

internal fun FolderEntity.toSummary() = FolderSummary(
    id = id,
    name = name,
    parentId = parentId,
    createdAt = createdAt,
    modifiedAt = modifiedAt
)

/**
 * Creates the default [DocumentStore] of the platform.
 */

/**
 * Process-wide document store: the database must not be opened more than once.
 */
public object DrawViewStore {
    public val default: RoomDocumentStore by lazy { RoomDocumentStore(DrawViewDatabase.instance) }

    /**
     * The documents of [default] being changed (see [OpenDocuments]), on the main thread: shared
     * by the editor and the synchronization with the other devices.
     */
    public val documents: OpenDocuments by lazy { OpenDocuments(default) }
}
