package io.github.valerioisufi.drawview.data.repository

import io.github.valerioisufi.drawview.core.model.newElementId
import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.data.db.DocumentEntity
import io.github.valerioisufi.drawview.data.db.DrawDatabase
import io.github.valerioisufi.drawview.data.db.FolderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The document library: folders, and the documents as items of the folders (their content is
 * handled by [DrawDocumentRepository]).
 */
class FileRepository(
    val database: DrawDatabase
) {
    private val folderDao = database.folderDao()
    private val documentDao = database.documentDao()
    private val resourceDao = database.resourceDao()

    // --- FOLDER OPERATIONS ---
    suspend fun createFolder(name: String, parentId: String?): Boolean = withContext(Dispatchers.IO) {
        if (folderDao.getFolderByNameAndParent(name, parentId) != null) return@withContext false

        folderDao.insert(FolderEntity(id = newElementId(), name = name, parentId = parentId))
        true
    }

    suspend fun renameFolder(folderId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val folder = folderDao.getFolderById(folderId) ?: return@withContext false
        if (folderDao.getFolderByNameAndParent(newName, folder.parentId) != null) return@withContext false

        folderDao.renameFolder(folderId, newName)
        true
    }

    suspend fun deleteFolder(folderId: String): Boolean = withContext(Dispatchers.IO) {
        folderDao.getFolderById(folderId) ?: return@withContext false

        for (subFolder in folderDao.getSubFolders(folderId)) {
            deleteFolder(subFolder.id)
        }
        for (document in documentDao.getDocumentsInFolder(folderId)) {
            deleteDocument(document.id)
        }

        folderDao.deleteById(folderId)
        true
    }

    suspend fun moveFolder(folderId: String, newParentId: String?): Boolean = withContext(Dispatchers.IO) {
        val folder = folderDao.getFolderById(folderId) ?: return@withContext false
        // A folder cannot be moved into itself or into one of its sub-folders
        var ancestorId = newParentId
        while (ancestorId != null) {
            if (ancestorId == folderId) return@withContext false
            ancestorId = folderDao.getFolderById(ancestorId)?.parentId
        }
        if (folderDao.getFolderByNameAndParent(folder.name, newParentId) != null) return@withContext false

        folderDao.moveFolder(folderId, newParentId)
        true
    }

    // --- DOCUMENT OPERATIONS ---
    suspend fun renameDocument(documentId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId) ?: return@withContext false
        if (documentDao.getDocumentByNameAndFolder(newName, document.folderId) != null) return@withContext false

        documentDao.renameDocument(documentId, newName)
        true
    }

    suspend fun moveDocument(documentId: String, newFolderId: String?): Boolean = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId) ?: return@withContext false
        if (documentDao.getDocumentByNameAndFolder(document.name, newFolderId) != null) return@withContext false

        documentDao.moveDocument(documentId, newFolderId)
        true
    }

    /**
     * Deletes a document (pages and content are removed by the foreign keys cascade) together
     * with the files of its resources (imported images and PDFs).
     */
    suspend fun deleteDocument(documentId: String): Boolean = withContext(Dispatchers.IO) {
        documentDao.getDocumentById(documentId) ?: return@withContext false
        val files = resourceDao.getResourcesForDocument(documentId).map { it.uri }
        documentDao.deleteById(documentId)
        for (path in files) {
            try {
                PlatformFiles.delete(path)
            } catch (_: Throwable) {
            }
        }
        true
    }

    suspend fun updateLastOpened(documentId: String, timestamp: Long) = withContext(Dispatchers.IO) {
        documentDao.updateLastOpened(documentId, timestamp)
    }

    /** Every document, most recently modified first (updated on every database change). */
    fun getAllDocumentsFlow(): Flow<List<DocumentEntity>> = documentDao.getAllDocumentsFlow()

    /** Every folder, at any depth (updated on every database change). */
    fun getAllFoldersFlow(): Flow<List<FolderEntity>> = folderDao.getAllFoldersFlow()
}
