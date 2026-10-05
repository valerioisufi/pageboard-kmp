package io.github.valerioisufi.drawview.data

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [DocumentStore] backed by the Room database (Android, desktop and iOS).
 */
class RoomDocumentStore(val database: DrawDatabase) : DocumentStore {
    val repository = DrawDocumentRepository(database)
    val fileRepository = FileRepository(database)

    override val documents: Flow<List<DocumentSummary>> =
        fileRepository.getAllDocumentsFlow().map { list -> list.map { it.toSummary() } }

    override val folders: Flow<List<FolderSummary>> =
        fileRepository.getAllFoldersFlow().map { list -> list.map { it.toSummary() } }

    override val preferences: Flow<DrawPreferences> = repository.drawPreferencesFlow

    override val persistence: DrawPersistence get() = repository

    override suspend fun createDocument(
        name: String,
        widthMm: Float,
        heightMm: Float,
        background: PageBackground,
        folderId: Int?
    ): DrawDocument = repository.createDocument(name, widthMm, heightMm, background, folderId)

    override suspend fun loadDocument(documentId: Int): DrawDocument? = repository.loadDocument(documentId)

    override suspend fun renameDocument(documentId: Int, newName: String): Boolean =
        fileRepository.renameDocument(documentId, newName)

    override suspend fun deleteDocument(documentId: Int): Boolean = fileRepository.deleteDocument(documentId)

    override suspend fun moveDocument(documentId: Int, folderId: Int?): Boolean =
        fileRepository.moveDocument(documentId, folderId)

    override suspend fun updateLastOpened(documentId: Int) =
        repository.updateLastOpened(documentId, currentTimeMillis())

    override suspend fun createFolder(name: String, parentId: Int?): Boolean = fileRepository.createFolder(name, parentId)

    override suspend fun renameFolder(folderId: Int, newName: String): Boolean =
        fileRepository.renameFolder(folderId, newName)

    override suspend fun deleteFolder(folderId: Int): Boolean = fileRepository.deleteFolder(folderId)

    override suspend fun moveFolder(folderId: Int, parentId: Int?): Boolean = fileRepository.moveFolder(folderId, parentId)
}

internal fun DocumentEntity.toSummary() = DocumentSummary(
    id = id,
    name = name,
    folderId = folderId,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    lastOpenedAt = lastOpenedAt ?: modifiedAt,
    defaultWidthMm = defaultWidth,
    defaultHeightMm = defaultHeight,
    defaultBackground = defaultBackground
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
