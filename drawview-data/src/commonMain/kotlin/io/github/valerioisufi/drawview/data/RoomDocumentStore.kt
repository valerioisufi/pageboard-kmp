package io.github.valerioisufi.drawview.data

import io.github.valerioisufi.drawview.data.db.DocumentEntity
import io.github.valerioisufi.drawview.data.db.DrawDatabase
import io.github.valerioisufi.drawview.data.db.DrawViewDatabase
import io.github.valerioisufi.drawview.data.db.FolderEntity
import io.github.valerioisufi.drawview.data.db.SharedDocumentEntity
import io.github.valerioisufi.drawview.data.repository.DrawDocumentRepository
import io.github.valerioisufi.drawview.data.repository.FileRepository
import io.github.valerioisufi.drawview.ink.InkStrokeModeller
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.StrokeModeller
import io.github.valerioisufi.drawview.model.change.DocumentPersistence
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [DocumentStore] backed by the Room database (Android, desktop and iOS).
 */
class RoomDocumentStore(
    val database: DrawDatabase,
    override val strokeModeller: StrokeModeller = InkStrokeModeller
) : DocumentStore, PreferenceStore {
    val repository = DrawDocumentRepository(database, strokeModeller)
    val fileRepository = FileRepository(database)

    private val sharedDocuments = database.sharedDocumentDao()

    override val documents: Flow<List<DocumentSummary>> =
        combine(fileRepository.getAllDocumentsWithContentFlow(), sharedDocuments.getIdsFlow()) { list, shared ->
            val sharedIds = shared.toSet()
            list.map { it.document.toSummary(isShared = it.document.id in sharedIds, pageCount = it.pageCount, hasPdf = it.hasPdf) }
        }

    override val folders: Flow<List<FolderSummary>> =
        fileRepository.getAllFoldersFlow().map { list -> list.map { it.toSummary() } }


    override val persistence: DocumentPersistence get() = repository

    override suspend fun preference(key: String): String? = repository.preference(key)

    override suspend fun setPreference(key: String, value: String?) = repository.setPreference(key, value)

    override suspend fun createDocument(
        name: String,
        widthMm: Float,
        heightMm: Float,
        background: PageBackground,
        folderId: String?
    ): DrawDocument = repository.createDocument(name, widthMm, heightMm, background, folderId)

    override suspend fun insertDocument(document: DrawDocument, folderId: String?, versionVector: Map<String, Long>) =
        repository.insertDocument(document, folderId, versionVector)

    override suspend fun loadDocument(documentId: String): DrawDocument? = repository.loadDocument(documentId)

    override suspend fun isDocumentNameAvailable(documentId: String, name: String): Boolean =
        fileRepository.isDocumentNameAvailable(documentId, name)

    override suspend fun deleteDocument(documentId: String): Boolean = fileRepository.deleteDocument(documentId)

    override suspend fun moveDocument(documentId: String, folderId: String?): Boolean =
        fileRepository.moveDocument(documentId, folderId)

    override suspend fun updateLastOpened(documentId: String) =
        fileRepository.updateLastOpened(documentId, currentTimeMillis())

    override suspend fun replicaId(): String = repository.replicaId()

    override suspend fun versionVector(documentId: String): Map<String, Long> = repository.versionVector(documentId)

    override suspend fun operationsAfter(documentId: String, known: Map<String, Long>): List<Operation> =
        repository.operationsAfter(documentId, known)

    override suspend fun compactOperations(documentId: String, upTo: Map<String, Long>): Map<String, Stamp> =
        repository.compactOperations(documentId, upTo)

    override suspend fun compactedOperations(documentId: String): Map<String, Long> = repository.compactedOperations(documentId)

    override suspend fun sharingSecrets(): Map<String, String> = withContext(Dispatchers.IO) {
        sharedDocuments.getAll().associate { it.documentId to it.secret }
    }

    override suspend fun setSharingSecret(documentId: String, secret: String?, readOnly: Boolean) = withContext(Dispatchers.IO) {
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
fun createPlatformDocumentStore(): DocumentStore = RoomDocumentStore(DrawViewDatabase.instance)
