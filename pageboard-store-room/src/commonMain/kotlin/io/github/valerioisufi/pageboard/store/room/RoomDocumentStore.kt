package io.github.valerioisufi.pageboard.store.room

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.StrokeModeller
import io.github.valerioisufi.pageboard.model.change.DocumentPersistence
import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.OperationId
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import io.github.valerioisufi.pageboard.store.DocumentInk
import io.github.valerioisufi.pageboard.store.DocumentOutline
import io.github.valerioisufi.pageboard.store.DocumentStore
import io.github.valerioisufi.pageboard.store.DocumentSummary
import io.github.valerioisufi.pageboard.store.FolderSummary
import io.github.valerioisufi.pageboard.store.LoadedPage
import io.github.valerioisufi.pageboard.store.PreferenceStore
import io.github.valerioisufi.pageboard.store.TrashedItem
import io.github.valerioisufi.pageboard.store.room.db.DocumentEntity
import io.github.valerioisufi.pageboard.store.room.db.PageboardDatabase
import io.github.valerioisufi.pageboard.store.room.db.FolderEntity
import io.github.valerioisufi.pageboard.store.room.db.SharedDocumentEntity
import io.github.valerioisufi.pageboard.store.room.repository.DocumentRepository
import io.github.valerioisufi.pageboard.store.room.repository.FileRepository
import io.github.valerioisufi.pageboard.store.room.repository.SpaceRepository
import io.github.valerioisufi.pageboard.store.space.SpaceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext

/**
 * [DocumentStore] and [SpaceStore] backed by the Room database (Android, desktop and iOS), its strokes modeled by
 * [strokeModeller] when they are loaded (the one of the editor, e.g. `InkStrokeModeller` of
 * `pageboard-ink`). One per database and per process: the application creates it once.
 */
public class RoomDocumentStore(
    internal val database: PageboardDatabase,
    override val strokeModeller: StrokeModeller
) : DocumentStore, PreferenceStore, SpaceStore by SpaceRepository(database) {
    internal val repository = DocumentRepository(database, strokeModeller)
    internal val fileRepository = FileRepository(database)

    private val sharedDocuments = database.sharedDocumentDao()

    /** Where the queries of the library run once for all their collectors. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val allDocumentsWithContent = fileRepository.getAllDocumentsWithContentFlow().sharedQuery()
    private val allDocuments = fileRepository.getAllDocumentsFlow().sharedQuery()
    private val allFolders = fileRepository.getAllFoldersFlow().sharedQuery()
    private val sharedIds = sharedDocuments.getIdsFlow().sharedQuery()
    private val spaceRoots = database.spaceDao().rootFoldersFlow().sharedQuery()

    override val documents: Flow<List<DocumentSummary>> =
        combine(allDocumentsWithContent, allFolders, sharedIds) { list, folders, shared ->
            val sharedIds = shared.toSet()
            val trash = FileRepository.trashOf(list.map { it.document }, folders, sharedIds)
            list.filter { trash.isDocumentLive(it.document.id) }
                .map { it.document.toSummary(isShared = it.document.id in sharedIds, pageCount = it.pageCount, hasPdf = it.hasPdf) }
        }.distinctUntilChanged()

    override val folders: Flow<List<FolderSummary>> =
        combine(allDocuments, allFolders, spaceRoots) { documents, list, roots ->
            val trash = FileRepository.trashOf(documents, list, emptySet())
            val shared = roots.toSet()
            list.filter { trash.isFolderLive(it.id) }.map { it.toSummary().copy(isShared = it.id in shared) }
        }.distinctUntilChanged()

    override val trash: Flow<List<TrashedItem>> =
        combine(allDocuments, allFolders, sharedIds) { documents, folders, shared ->
            FileRepository.trashOf(documents, folders, shared.toSet()).items
        }.distinctUntilChanged()

    /**
     * This query run once for all its collectors, again as its tables change. A collector gets a
     * value read for it first: what it reads after a write has the write.
     */
    private fun <T> Flow<T>.sharedQuery(): Flow<T> {
        val query = this
        return shareIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS)).onSubscription { emit(query.first()) }
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
    ): Document = repository.createDocument(name, widthMm, heightMm, background, folderId)

    override suspend fun insertDocument(document: Document, folderId: String?, versionVector: Map<String, Long>): Unit =
        repository.insertDocument(document, folderId, versionVector)

    override suspend fun loadDocument(documentId: String): Document? = repository.loadDocument(documentId)

    override suspend fun loadOutline(documentId: String): DocumentOutline? = repository.loadOutline(documentId)

    override suspend fun loadInk(documentId: String): DocumentInk? = repository.loadInk(documentId)

    override suspend fun pageFingerprint(documentId: String, index: Int): String? = repository.pageFingerprint(documentId, index)

    override suspend fun loadPage(documentId: String, index: Int): LoadedPage? = repository.loadPage(documentId, index)

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

    override suspend fun versionVectors(documentIds: Collection<String>): Map<String, Map<String, Long>> =
        repository.versionVectors(documentIds)

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

/** How long a query of the library keeps running after its last collector left (a screen shown again). */
private const val STOP_AFTER_MS = 5_000L
