package io.github.valerioisufi.pageboard.store.room.repository

import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.platform.PlatformFiles
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import io.github.valerioisufi.pageboard.store.LibraryTrash
import io.github.valerioisufi.pageboard.store.TrashedItem
import io.github.valerioisufi.pageboard.store.room.db.DocumentEntity
import io.github.valerioisufi.pageboard.store.room.db.DocumentWithContent
import io.github.valerioisufi.pageboard.store.room.db.DrawDatabase
import io.github.valerioisufi.pageboard.store.room.db.FolderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The document library: folders, and the documents as items of the folders (their content is
 * handled by [DrawDocumentRepository]).
 */
internal class FileRepository(
    val database: DrawDatabase
) {
    private val folderDao = database.folderDao()
    private val documentDao = database.documentDao()
    private val resourceDao = database.resourceDao()
    private val sharedDocumentDao = database.sharedDocumentDao()

    /** What is in the trash and what is not, as stored now. */
    private suspend fun trash(): LibraryTrash =
        trashOf(documentDao.getAllDocuments(), folderDao.getAllFolders(), sharedDocumentDao.getAll().mapTo(mutableSetOf()) { it.documentId })

    /** Whether a folder out of the trash of [parentId] is named [name] (but [folderId]). */
    private fun LibraryTrash.folderNameTaken(folders: List<FolderEntity>, name: String, parentId: String?, folderId: String? = null) =
        folders.any { it.id != folderId && it.parentId == parentId && it.name == name && isFolderLive(it.id) }

    // --- FOLDER OPERATIONS ---
    suspend fun createFolder(name: String, parentId: String?): Boolean = withContext(Dispatchers.IO) {
        val trash = trash()
        if (!trash.isFolderLive(parentId) || trash.folderNameTaken(folderDao.getAllFolders(), name, parentId)) return@withContext false

        folderDao.insert(FolderEntity(id = newElementId(), name = name, parentId = parentId))
        true
    }

    suspend fun renameFolder(folderId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val folder = folderDao.getFolderById(folderId) ?: return@withContext false
        if (trash().folderNameTaken(folderDao.getAllFolders(), newName, folder.parentId, folderId)) return@withContext false

        folderDao.renameFolder(folderId, newName)
        true
    }

    suspend fun deleteFolder(folderId: String): Boolean = withContext(Dispatchers.IO) {
        folderDao.getFolderById(folderId) ?: return@withContext false
        // What is in the trash on its own stays there, at its root
        for (item in trash().itemsInside(folderId)) {
            when (item.kind) {
                TrashedItem.Kind.DOCUMENT -> documentDao.moveDocument(item.id, null)
                TrashedItem.Kind.FOLDER -> folderDao.moveFolder(item.id, null)
            }
        }
        deleteFolderWithContent(folderId)
        true
    }

    private suspend fun deleteFolderWithContent(folderId: String) {
        for (subFolder in folderDao.getSubFolders(folderId)) {
            deleteFolderWithContent(subFolder.id)
        }
        for (document in documentDao.getDocumentsInFolder(folderId)) {
            deleteDocument(document.id)
        }

        folderDao.deleteById(folderId)
    }

    suspend fun moveFolder(folderId: String, newParentId: String?): Boolean = withContext(Dispatchers.IO) {
        val folder = folderDao.getFolderById(folderId) ?: return@withContext false
        val trash = trash()
        if (!trash.isFolderLive(folderId) || !trash.isFolderLive(newParentId)) return@withContext false
        // A folder cannot be moved into itself or into one of its sub-folders
        var ancestorId = newParentId
        while (ancestorId != null) {
            if (ancestorId == folderId) return@withContext false
            ancestorId = folderDao.getFolderById(ancestorId)?.parentId
        }
        if (trash.folderNameTaken(folderDao.getAllFolders(), folder.name, newParentId)) return@withContext false

        folderDao.moveFolder(folderId, newParentId)
        true
    }

    // --- DOCUMENT OPERATIONS ---
    suspend fun isDocumentNameAvailable(documentId: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId) ?: return@withContext false
        val trash = trash()
        documentDao.getAllDocuments().none { it.id != documentId && it.folderId == document.folderId && it.name == name && trash.isDocumentLive(it.id) }
    }

    suspend fun moveDocument(documentId: String, newFolderId: String?): Boolean = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId) ?: return@withContext false
        val trash = trash()
        if (!trash.isDocumentLive(documentId) || !trash.isFolderLive(newFolderId)) return@withContext false
        if (documentDao.getAllDocuments().any { it.folderId == newFolderId && it.name == document.name && trash.isDocumentLive(it.id) }) return@withContext false

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
        // Not the files kept for other documents too (the same content is kept once)
        for (path in files.distinct().filter { resourceDao.countByUri(it) == 0 }) {
            try {
                PlatformFiles.delete(path)
            } catch (_: Throwable) {
            }
        }
        true
    }

    // --- TRASH ---
    suspend fun trashDocument(documentId: String): Boolean = withContext(Dispatchers.IO) {
        if (!trash().isDocumentLive(documentId)) return@withContext false
        documentDao.setTrashedAt(documentId, currentTimeMillis())
        true
    }

    suspend fun trashFolder(folderId: String): Boolean = withContext(Dispatchers.IO) {
        if (!trash().isFolderLive(folderId)) return@withContext false
        folderDao.setTrashedAt(folderId, currentTimeMillis())
        true
    }

    suspend fun restore(itemId: String): String? = withContext(Dispatchers.IO) {
        val trash = trash()
        val item = trash.item(itemId) ?: return@withContext null
        val target = trash.restoreTarget(item)
        when (item.kind) {
            TrashedItem.Kind.DOCUMENT -> trash.availableDocumentName(item.name, target, item.id).also { documentDao.restore(item.id, it, target) }
            TrashedItem.Kind.FOLDER -> trash.availableFolderName(item.name, target, item.id).also { folderDao.restore(item.id, it, target) }
        }
    }

    suspend fun purgeTrash(before: Long): Unit = withContext(Dispatchers.IO) {
        for (item in trash().items.filter { it.trashedAt < before }.sortedBy { it.trashedAt }) {
            when (item.kind) {
                TrashedItem.Kind.DOCUMENT -> deleteDocument(item.id)
                TrashedItem.Kind.FOLDER -> deleteFolder(item.id)
            }
        }
    }

    suspend fun updateLastOpened(documentId: String, timestamp: Long) = withContext(Dispatchers.IO) {
        documentDao.updateLastOpened(documentId, timestamp)
    }

    /** Every document, most recently modified first (updated on every database change). */
    fun getAllDocumentsFlow(): Flow<List<DocumentEntity>> = documentDao.getAllDocumentsFlow()

    /** Every document with its number of pages and its PDFs (updated on every database change). */
    fun getAllDocumentsWithContentFlow(): Flow<List<DocumentWithContent>> = documentDao.getAllDocumentsWithContentFlow()

    /** Every folder, at any depth (updated on every database change). */
    fun getAllFoldersFlow(): Flow<List<FolderEntity>> = folderDao.getAllFoldersFlow()

    companion object {
        /** What is in the trash and what is not, of [documents] and [folders] ([shared]: the documents shared). */
        fun trashOf(documents: List<DocumentEntity>, folders: List<FolderEntity>, shared: Set<String>): LibraryTrash = LibraryTrash(
            documents = documents.map { LibraryTrash.Entry(it.id, it.name, it.folderId, it.trashedAt, isShared = it.id in shared) },
            folders = folders.map { LibraryTrash.Entry(it.id, it.name, it.parentId, it.trashedAt) }
        )
    }
}
