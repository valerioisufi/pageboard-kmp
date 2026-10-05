package io.github.valerioisufi.drawview.data.repository

import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class FileRepository(
    val database: DrawDatabase
) {
    private val folderDao = database.folderDao()
    private val documentDao = database.documentDao()
    private val pageDao = database.pageDao()
    private val resourceDao = database.resourceDao()

    // --- FOLDER OPERATIONS ---
    suspend fun createFolder(name: String, parentId: Int?): Boolean = withContext(Dispatchers.IO) {
        if (folderDao.getFolderByNameAndParent(name, parentId) != null) return@withContext false

        val folder = FolderEntity(name = name, parentId = parentId)
        folderDao.insert(folder)
        true
    }

    suspend fun getRootFolders(): List<FolderEntity> = withContext(Dispatchers.IO) {
        folderDao.getRootFolders()
    }

    suspend fun getSubFolders(parentId: Int): List<FolderEntity> = withContext(Dispatchers.IO) {
        folderDao.getSubFolders(parentId)
    }

    suspend fun renameFolder(folderId: Int, newName: String): Boolean = withContext(Dispatchers.IO) {
        val folder = folderDao.getFolderById(folderId) ?: return@withContext false
        if (folderDao.getFolderByNameAndParent(newName, folder.parentId) != null) return@withContext false

        folderDao.renameFolder(folderId, newName)
        true
    }

    suspend fun deleteFolder(folderId: Int): Boolean = withContext(Dispatchers.IO) {
        val folder = folderDao.getFolderById(folderId) ?: return@withContext false

        val subFolders = folderDao.getSubFolders(folderId)
        for (subFolder in subFolders) {
            deleteFolder(subFolder.id)
        }

        val documentsInFolder = documentDao.getDocumentsInFolder(folderId)
        for (doc in documentsInFolder) {
            deleteDocument(doc.id)
        }

        folderDao.deleteById(folderId)
        true
    }

    suspend fun moveFolder(folderId: Int, newParentId: Int?): Boolean = withContext(Dispatchers.IO) {
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
    suspend fun createDocument(name: String, folderId: Int?): Boolean = withContext(Dispatchers.IO) {
        val existing = if (folderId == null) documentDao.getRootDocumentByName(name)
        else documentDao.getDocumentByNameAndFolder(name, folderId)
        if (existing != null) return@withContext false

        val document = DocumentEntity(name = name, folderId = folderId)
        val newDocId = documentDao.insert(document).toInt()

        val dbPage = PageEntity(
            documentId = newDocId,
            pageNumber = 0,
            width = 210f,
            height = 297f
        )
        pageDao.insert(dbPage)

        true
    }

    suspend fun getDocumentsInFolder(folderId: Int?): List<DocumentEntity> = withContext(Dispatchers.IO) {
        if (folderId == null) documentDao.getRootDocuments()
        else documentDao.getDocumentsInFolder(folderId)
    }

    suspend fun renameDocument(documentId: Int, newName: String): Boolean = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId) ?: return@withContext false
        val existing = if (document.folderId == null) documentDao.getRootDocumentByName(newName)
        else documentDao.getDocumentByNameAndFolder(newName, document.folderId)
        if (existing != null) return@withContext false

        documentDao.renameDocument(documentId, newName)
        true
    }

    suspend fun moveDocument(documentId: Int, newFolderId: Int?): Boolean = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId) ?: return@withContext false
        val existing = if (newFolderId == null) documentDao.getRootDocumentByName(document.name)
        else documentDao.getDocumentByNameAndFolder(document.name, newFolderId)
        if (existing != null) return@withContext false

        documentDao.moveDocument(documentId, newFolderId)
        true
    }

    /**
     * Deletes a document (pages and content are removed by the foreign keys cascade) together
     * with the files of its resources (imported images and PDFs).
     */
    suspend fun deleteDocument(documentId: Int): Boolean = withContext(Dispatchers.IO) {
        val doc = documentDao.getDocumentById(documentId) ?: return@withContext false
        val files = resourceDao.getResourcesForDocument(documentId).map { it.uri }
        documentDao.delete(doc)
        for (path in files) {
            try {
                PlatformFiles.delete(path)
            } catch (_: Throwable) {
            }
        }
        true
    }

    suspend fun updateLastOpened(documentId: Int) = withContext(Dispatchers.IO) {
        documentDao.updateLastOpened(documentId)
    }

    suspend fun getRecentDocuments(limit: Int): List<DocumentEntity> = withContext(Dispatchers.IO) {
        documentDao.getRecentDocuments(limit)
    }

    fun getRecentDocumentsFlow(limit: Int): Flow<List<DocumentEntity>> {
        return documentDao.getRecentDocumentsFlow(limit)
    }

    /** Every document, most recently modified first (updated on every database change). */
    fun getAllDocumentsFlow(): Flow<List<DocumentEntity>> = documentDao.getAllDocumentsFlow()

    /** Every folder, at any depth (updated on every database change). */
    fun getAllFoldersFlow(): Flow<List<FolderEntity>> = folderDao.getAllFoldersFlow()

    suspend fun getFolderById(folderId: Int): FolderEntity? = withContext(Dispatchers.IO) {
        folderDao.getFolderById(folderId)
    }

    suspend fun getDocumentById(documentId: Int): DocumentEntity? = withContext(Dispatchers.IO) {
        documentDao.getDocumentById(documentId)
    }

    // --- COMBINED OPERATIONS (UI) ---
    data class FileItem(
        val id: Int,
        val name: String,
        val type: FileType,
        val parentId: Int?,
        val createdAt: Long,
        val modifiedAt: Long,
        val lastOpenedAt: Long?
    )

    enum class FileType { FOLDER, DOCUMENT }

    suspend fun getItemsInFolder(parentId: Int?): List<FileItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<FileItem>()

        val folders = if (parentId == null) folderDao.getRootFolders() else folderDao.getSubFolders(parentId)
        folders.forEach { f ->
            items.add(FileItem(f.id, f.name, FileType.FOLDER, f.parentId, f.createdAt, f.modifiedAt, null))
        }

        val docs = getDocumentsInFolder(parentId)
        docs.forEach { d ->
            items.add(FileItem(d.id, d.name, FileType.DOCUMENT, d.folderId, d.createdAt, d.modifiedAt, d.lastOpenedAt))
        }

        items.sortedWith(compareBy({ it.type }, { it.name }))
    }
}
