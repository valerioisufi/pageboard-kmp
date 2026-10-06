package io.github.valerioisufi.drawview.data

import io.github.valerioisufi.drawview.core.change.Operation
import io.github.valerioisufi.drawview.core.controller.DrawPersistence
import io.github.valerioisufi.drawview.core.controller.DrawPreferences
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.data.db.DocumentEntity
import io.github.valerioisufi.drawview.data.db.DrawDatabase
import io.github.valerioisufi.drawview.data.db.DrawViewDatabase
import io.github.valerioisufi.drawview.data.db.FolderEntity
import io.github.valerioisufi.drawview.data.db.currentTimeMillis
import io.github.valerioisufi.drawview.data.repository.DrawDocumentRepository
import io.github.valerioisufi.drawview.data.repository.FileRepository
import io.github.valerioisufi.drawview.data.db.SharedDocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [DocumentStore] backed by the Room database (Android, desktop and iOS).
 */
class RoomDocumentStore(val database: DrawDatabase) : DocumentStore {
    val repository = DrawDocumentRepository(database)
    val fileRepository = FileRepository(database)

    private val sharedDocuments = database.sharedDocumentDao()

    override val documents: Flow<List<DocumentSummary>> =
        combine(fileRepository.getAllDocumentsFlow(), sharedDocuments.getIdsFlow()) { list, shared ->
            val sharedIds = shared.toSet()
            list.map { it.toSummary(isShared = it.id in sharedIds) }
        }

    override val folders: Flow<List<FolderSummary>> =
        fileRepository.getAllFoldersFlow().map { list -> list.map { it.toSummary() } }

    override val preferences: Flow<DrawPreferences> = repository.drawPreferencesFlow

    override val persistence: DrawPersistence get() = repository

    override suspend fun createDocument(
        name: String,
        widthMm: Float,
        heightMm: Float,
        background: PageBackground,
        folderId: String?
    ): DrawDocument = repository.createDocument(name, widthMm, heightMm, background, folderId)

    override suspend fun insertDocument(document: DrawDocument, folderId: String?) = repository.insertDocument(document, folderId)

    override suspend fun loadDocument(documentId: String): DrawDocument? = repository.loadDocument(documentId)

    override suspend fun renameDocument(documentId: String, newName: String): Boolean =
        fileRepository.renameDocument(documentId, newName)

    override suspend fun deleteDocument(documentId: String): Boolean = fileRepository.deleteDocument(documentId)

    override suspend fun moveDocument(documentId: String, folderId: String?): Boolean =
        fileRepository.moveDocument(documentId, folderId)

    override suspend fun updateLastOpened(documentId: String) =
        fileRepository.updateLastOpened(documentId, currentTimeMillis())

    override suspend fun replicaId(): String = repository.replicaId()

    override suspend fun versionVector(documentId: String): Map<String, Long> = repository.versionVector(documentId)

    override suspend fun operationsAfter(documentId: String, known: Map<String, Long>): List<Operation> =
        repository.operationsAfter(documentId, known)

    override suspend fun sharingSecrets(): Map<String, String> = withContext(Dispatchers.IO) {
        sharedDocuments.getAll().associate { it.documentId to it.secret }
    }

    override suspend fun setSharingSecret(documentId: String, secret: String?) = withContext(Dispatchers.IO) {
        if (secret == null) sharedDocuments.delete(documentId) else sharedDocuments.upsert(SharedDocumentEntity(documentId, secret))
    }

    override suspend fun createFolder(name: String, parentId: String?): Boolean = fileRepository.createFolder(name, parentId)

    override suspend fun renameFolder(folderId: String, newName: String): Boolean =
        fileRepository.renameFolder(folderId, newName)

    override suspend fun deleteFolder(folderId: String): Boolean = fileRepository.deleteFolder(folderId)

    override suspend fun moveFolder(folderId: String, parentId: String?): Boolean = fileRepository.moveFolder(folderId, parentId)
}

internal fun DocumentEntity.toSummary(isShared: Boolean = false) = DocumentSummary(
    id = id,
    name = name,
    folderId = folderId,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    lastOpenedAt = lastOpenedAt ?: modifiedAt,
    defaultWidthMm = defaultWidth,
    defaultHeightMm = defaultHeight,
    defaultBackground = defaultBackground,
    isShared = isShared
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
