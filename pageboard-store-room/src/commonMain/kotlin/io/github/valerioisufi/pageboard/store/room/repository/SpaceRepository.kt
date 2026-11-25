package io.github.valerioisufi.pageboard.store.room.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.valerioisufi.pageboard.model.change.OperationId
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import io.github.valerioisufi.pageboard.store.LibraryTrash
import io.github.valerioisufi.pageboard.store.space.CatalogChange
import io.github.valerioisufi.pageboard.store.space.CatalogItem
import io.github.valerioisufi.pageboard.store.space.CatalogOperation
import io.github.valerioisufi.pageboard.store.space.SpaceStore
import io.github.valerioisufi.pageboard.store.space.StoredSpace
import io.github.valerioisufi.pageboard.store.room.db.CatalogItemEntity
import io.github.valerioisufi.pageboard.store.room.db.CatalogOperationEntity
import io.github.valerioisufi.pageboard.store.room.db.CatalogProofEntity
import io.github.valerioisufi.pageboard.store.room.db.PageboardDatabase
import io.github.valerioisufi.pageboard.store.room.db.FolderEntity
import io.github.valerioisufi.pageboard.store.room.db.SpaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** The shared folders in the database (see [SpaceStore]): the spaces, their catalogs, logs and proofs. */
internal class SpaceRepository(private val database: PageboardDatabase) : SpaceStore {
    private val spaceDao = database.spaceDao()
    private val folderDao = database.folderDao()
    private val documentDao = database.documentDao()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override suspend fun spaces(): List<StoredSpace> = withContext(Dispatchers.IO) {
        spaceDao.spaces().map { StoredSpace(it.id, it.rootFolderId, it.sharing, it.readOnly) }
    }

    override suspend fun saveSpace(space: StoredSpace): Unit = withContext(Dispatchers.IO) {
        spaceDao.upsertSpace(SpaceEntity(space.id, space.rootFolderId, space.sharing, space.readOnly))
    }

    override suspend fun removeSpace(spaceId: String): Unit = withContext(Dispatchers.IO) { spaceDao.deleteSpace(spaceId) }

    override suspend fun catalogItems(spaceId: String): List<CatalogItem> = withContext(Dispatchers.IO) {
        spaceDao.items(spaceId).mapNotNull { decodeOrNull { json.decodeFromString(CatalogItem.serializer(), it.item) } }
    }

    override suspend fun saveCatalogChange(spaceId: String, items: Collection<CatalogItem>, operation: CatalogOperation): Unit =
        withContext(Dispatchers.IO) {
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    if (spaceDao.space(spaceId) == null) return@immediateTransaction
                    val change = json.encodeToString(CatalogChange.serializer(), operation.change)
                    val logged = spaceDao.insertOperation(CatalogOperationEntity(spaceId, operation.id.replica, operation.id.sequence, operation.stamp, change))
                    if (logged == -1L) return@immediateTransaction
                    spaceDao.upsertItems(items.map { CatalogItemEntity(spaceId, it.id, json.encodeToString(CatalogItem.serializer(), it)) })
                }
            }
        }

    override suspend fun saveCatalogItems(spaceId: String, items: Collection<CatalogItem>): Unit = withContext(Dispatchers.IO) {
        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (spaceDao.space(spaceId) == null) return@immediateTransaction
                spaceDao.upsertItems(items.map { CatalogItemEntity(spaceId, it.id, json.encodeToString(CatalogItem.serializer(), it)) })
            }
        }
    }

    override suspend fun catalogVersionVector(spaceId: String): Map<String, Long> = withContext(Dispatchers.IO) {
        spaceDao.lastSequences(spaceId).associate { it.replica to it.sequence }
    }

    override suspend fun catalogOperationsAfter(spaceId: String, known: Map<String, Long>): List<CatalogOperation> = withContext(Dispatchers.IO) {
        spaceDao.lastSequences(spaceId).flatMap { spaceDao.operationsAfter(spaceId, it.replica, known[it.replica] ?: 0L) }
            .mapNotNull { entity ->
                decodeOrNull { json.decodeFromString(CatalogChange.serializer(), entity.change) }
                    ?.let { CatalogOperation(OperationId(entity.replica, entity.sequence), entity.stamp, it) }
            }
            .sortedBy { it.stamp }
    }

    override suspend fun catalogProofs(spaceId: String, ids: Collection<OperationId>): Map<OperationId, ByteArray> = withContext(Dispatchers.IO) {
        ids.groupBy({ it.replica }, { it.sequence }).flatMap { (replica, sequences) ->
            sequences.chunked(MAX_SEQUENCES_PER_QUERY).flatMap { spaceDao.proofs(spaceId, replica, it) }
        }.associate { OperationId(it.replica, it.sequence) to it.proof }
    }

    override suspend fun saveCatalogProofs(spaceId: String, proofs: Map<OperationId, ByteArray>): Unit = withContext(Dispatchers.IO) {
        if (proofs.isEmpty() || spaceDao.space(spaceId) == null) return@withContext
        spaceDao.upsertProofs(proofs.map { (id, proof) -> CatalogProofEntity(spaceId, id.replica, id.sequence, proof) })
    }

    override suspend fun putFolder(folderId: String, name: String, parentId: String?): Unit = withContext(Dispatchers.IO) {
        val folder = folderDao.getFolderById(folderId)
        if (folder == null) {
            folderDao.insert(FolderEntity(id = folderId, name = name, parentId = parentId))
        } else {
            if (folder.name != name) folderDao.renameFolder(folderId, name)
            if (folder.parentId != parentId) folderDao.moveFolder(folderId, parentId)
        }
    }

    override suspend fun library(): LibraryTrash = withContext(Dispatchers.IO) {
        val shared = database.sharedDocumentDao().getAll().mapTo(mutableSetOf()) { it.documentId }
        FileRepository.trashOf(documentDao.getAllDocuments(), folderDao.getAllFolders(), shared)
    }

    override suspend fun putDocumentFolder(documentId: String, folderId: String?): Boolean = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId) ?: return@withContext false
        if (document.folderId != folderId) documentDao.moveDocument(documentId, folderId)
        true
    }

    override suspend fun putTrashed(itemId: String, trashed: Boolean): Unit = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(itemId)
        val trashedAt = if (trashed) currentTimeMillis() else null
        if (document != null) {
            if (!trashed || document.trashedAt == null) documentDao.setTrashedAt(itemId, trashedAt)
            return@withContext
        }
        val folder = folderDao.getFolderById(itemId) ?: return@withContext
        if (!trashed || folder.trashedAt == null) folderDao.setTrashedAt(itemId, trashedAt)
    }

    private inline fun <T> decodeOrNull(decode: () -> T): T? = try {
        decode()
    } catch (e: Exception) {
        println("Pageboard: unreadable stored change of a space (${e.message})")
        null
    }

    private companion object {
        /** The sequences per query at most (the variables of SQLite). */
        const val MAX_SEQUENCES_PER_QUERY = 500
    }
}
