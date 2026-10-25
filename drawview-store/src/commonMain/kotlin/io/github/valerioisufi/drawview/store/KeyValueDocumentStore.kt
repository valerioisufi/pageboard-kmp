package io.github.valerioisufi.drawview.store

import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.KotlinStrokeModeller
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.StrokeModeller
import io.github.valerioisufi.drawview.model.change.DocumentPersistence
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.model.change.PageSnapshot
import io.github.valerioisufi.drawview.model.change.snapshot
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.platform.PlatformFiles
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import io.github.valerioisufi.drawview.platform.syncLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Minimal string key-value storage.
 */
public interface KeyValueStorage {
    public fun get(key: String): String?

    /** Stores [value]; `false` when it could not be written (e.g. storage quota exceeded). */
    public fun set(key: String, value: String): Boolean

    public fun remove(key: String)
}

/**
 * [KeyValueStorage] kept in memory (tests).
 */
public class InMemoryKeyValueStorage : KeyValueStorage {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun set(key: String, value: String): Boolean {
        values[key] = value
        return true
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    public val keys: Set<String> get() = values.keys.toSet()
}

/**
 * [DocumentStore] that keeps every document as a JSON value of a [KeyValueStorage] (the folders
 * and the document summaries are kept in a single index value). Lightweight alternative to
 * `RoomDocumentStore` (`drawview-store-room`), used in memory by the tests (with [InMemoryKeyValueStorage]).
 *
 * While a document is open, its in-memory model is the source of truth: every persistence
 * operation marks the document as modified and its whole content is written shortly after
 * (writes are coalesced). The files of the resources (imported images) are stored as Base64
 * values and restored into [PlatformFiles] when the document is loaded.
 *
 * @param clock the time of the changes, in milliseconds: a fixed time makes the dates shown by a
 *   test the same at every run.
 * @param newFolderId the id of a new folder (random): ids given make the folders shown by a test
 *   the same at every run (the tone of a folder follows its id).
 */
public class KeyValueDocumentStore(
    private val storage: KeyValueStorage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val saveDelayMs: Long = 400L,
    private val clock: () -> Long = ::currentTimeMillis,
    override val strokeModeller: StrokeModeller = KotlinStrokeModeller,
    private val newFolderId: () -> String = ::newElementId
) : DocumentStore, DocumentPersistence, PreferenceStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val lock = SyncLock()

    private var index: StoredIndex = readIndex()
    private val openDocuments = mutableMapOf<String, DrawDocument>()
    private val dirtyDocuments = mutableSetOf<String>()
    private var saveJob: Job? = null

    private val _documents = MutableStateFlow(sortedSummaries(index))
    override val documents: StateFlow<List<DocumentSummary>> = _documents.asStateFlow()

    private val _folders = MutableStateFlow(folderSummaries(index))
    override val folders: StateFlow<List<FolderSummary>> = _folders.asStateFlow()

    override val persistence: DocumentPersistence get() = this

    /** `false` after a write has been rejected by the storage (e.g. quota exceeded). */
    internal var lastWriteSucceeded: Boolean = true
        private set

    // ==========================================================
    // DocumentStore
    // ==========================================================

    override suspend fun createDocument(
        name: String,
        widthMm: Float,
        heightMm: Float,
        background: PageBackground,
        folderId: String?
    ): DrawDocument {
        val now = clock()
        val document = syncLock(lock) {
            val document = DrawDocument(
                title = name,
                defaultWidthMm = widthMm,
                defaultHeightMm = heightMm,
                defaultBackground = background,
                createdAt = now,
                modifiedAt = now,
                strokeModeller = strokeModeller
            )
            document.lastOpenedAt = now
            val summary = StoredSummary(
                id = document.id,
                name = name,
                folderId = folderId,
                createdAt = now,
                modifiedAt = now,
                lastOpenedAt = now,
                defaultWidth = widthMm,
                defaultHeight = heightMm,
                defaultBackground = background
            )
            index = index.copy(documents = index.documents + summary)
            openDocuments[document.id] = document
            writeDocumentLocked(document)
            writeIndexLocked()
            document
        }
        publishSummaries()
        return document
    }

    override suspend fun insertDocument(document: DrawDocument, folderId: String?, versionVector: Map<String, Long>) {
        val now = clock()
        syncLock(lock) {
            if (versionVector.isNotEmpty()) index = index.copy(operationBases = index.operationBases + (document.id to versionVector))
            val summary = StoredSummary(
                id = document.id,
                name = document.title,
                folderId = folderId,
                createdAt = now,
                modifiedAt = now,
                lastOpenedAt = now,
                defaultWidth = document.defaultWidthMm,
                defaultHeight = document.defaultHeightMm,
                defaultBackground = document.defaultBackground
            )
            index = index.copy(documents = index.documents.filter { it.id != document.id } + summary)
            openDocuments[document.id] = document
            writeDocumentLocked(document)
            writeIndexLocked()
        }
        publishSummaries()
    }

    override suspend fun loadDocument(documentId: String): DrawDocument? {
        val document = syncLock(lock) {
            val summary = index.documents.firstOrNull { it.id == documentId } ?: return null
            // Loaded again while open: with its last changes
            if (dirtyDocuments.remove(documentId)) openDocuments[documentId]?.let { writeDocumentLocked(it) }
            val stored = storage.get(documentKey(documentId))?.let { decodeOrNull<StoredDocument>(it) }
            val document = DrawDocument(
                id = documentId,
                title = summary.name,
                defaultWidthMm = summary.defaultWidth,
                defaultHeightMm = summary.defaultHeight,
                defaultBackground = summary.defaultBackground,
                createdAt = summary.createdAt,
                modifiedAt = summary.modifiedAt,
                addInitialPage = false,
                strokeModeller = strokeModeller
            )
            document.lastOpenedAt = summary.lastOpenedAt

            stored?.resources?.forEach { resource ->
                val type = DrawResource.ResourceType.entries.firstOrNull { it.name == resource.type }
                    ?: DrawResource.ResourceType.IMAGE
                restoreResourceFile(resource.uri)
                document.addResource(DrawResource(resource.id, type, resource.uri, resource.hash))
            }
            document.defaultsVersion = stored?.defaultsVersion ?: Stamp.ZERO
            document.titleVersion = stored?.titleVersion ?: Stamp.ZERO
            stored?.pages?.forEach { storedPage -> document.appendPage(storedPage.toPage(document)) }
            stored?.removedPages?.forEach { storedPage -> storedPage.toPage(document).let { document.removedPages[it.id] = it } }
            document.reindexPages()
            openDocuments[documentId] = document
            document
        }
        return document
    }

    override suspend fun isDocumentNameAvailable(documentId: String, name: String): Boolean = syncLock(lock) {
        val summary = index.documents.firstOrNull { it.id == documentId } ?: return false
        index.documents.none { it.id != documentId && it.folderId == summary.folderId && it.name == name }
    }

    override suspend fun updateDocumentTitle(document: DrawDocument) {
        syncLock(lock) {
            updateSummaryLocked(document.id) { it.copy(name = document.title) }
            writeIndexLocked()
        }
        markDocumentModified(document)
        publishSummaries()
    }

    override suspend fun deleteDocument(documentId: String): Boolean {
        syncLock(lock) {
            if (index.documents.none { it.id == documentId }) return false
            deleteDocumentLocked(documentId)
            writeIndexLocked()
        }
        publishSummaries()
        return true
    }

    override suspend fun moveDocument(documentId: String, folderId: String?): Boolean {
        syncLock(lock) {
            val summary = index.documents.firstOrNull { it.id == documentId } ?: return false
            if (folderId != null && index.folders.none { it.id == folderId }) return false
            if (index.documents.any { it.folderId == folderId && it.name == summary.name }) return false
            updateSummaryLocked(documentId) { it.copy(folderId = folderId, modifiedAt = clock()) }
            writeIndexLocked()
        }
        publishSummaries()
        return true
    }

    override suspend fun updateLastOpened(documentId: String) {
        syncLock(lock) {
            val now = clock()
            updateSummaryLocked(documentId) { it.copy(lastOpenedAt = now) }
            openDocuments[documentId]?.lastOpenedAt = now
            writeIndexLocked()
        }
        publishSummaries()
    }

    override suspend fun createFolder(name: String, parentId: String?): Boolean {
        syncLock(lock) {
            if (parentId != null && index.folders.none { it.id == parentId }) return false
            if (index.folders.any { it.parentId == parentId && it.name == name }) return false
            val now = clock()
            val folder = StoredFolder(newFolderId(), name, parentId, createdAt = now, modifiedAt = now)
            index = index.copy(folders = index.folders + folder)
            writeIndexLocked()
        }
        publishSummaries()
        return true
    }

    override suspend fun renameFolder(folderId: String, newName: String): Boolean {
        syncLock(lock) {
            val folder = index.folders.firstOrNull { it.id == folderId } ?: return false
            if (index.folders.any { it.parentId == folder.parentId && it.name == newName }) return false
            updateFolderLocked(folderId) { it.copy(name = newName, modifiedAt = clock()) }
            writeIndexLocked()
        }
        publishSummaries()
        return true
    }

    override suspend fun deleteFolder(folderId: String): Boolean {
        syncLock(lock) {
            if (index.folders.none { it.id == folderId }) return false
            // The folder and every sub-folder, at any depth
            val deleted = mutableSetOf(folderId)
            var added = true
            while (added) {
                added = index.folders.filter { it.parentId in deleted }.map { it.id }.let { deleted.addAll(it) }
            }
            index.documents.filter { it.folderId in deleted }.forEach { deleteDocumentLocked(it.id) }
            index = index.copy(folders = index.folders.filter { it.id !in deleted })
            writeIndexLocked()
        }
        publishSummaries()
        return true
    }

    override suspend fun moveFolder(folderId: String, parentId: String?): Boolean {
        syncLock(lock) {
            val folder = index.folders.firstOrNull { it.id == folderId } ?: return false
            if (parentId != null && index.folders.none { it.id == parentId }) return false
            // A folder cannot be moved into itself or into one of its sub-folders
            var ancestorId = parentId
            while (ancestorId != null) {
                if (ancestorId == folderId) return false
                ancestorId = index.folders.firstOrNull { it.id == ancestorId }?.parentId
            }
            if (index.folders.any { it.parentId == parentId && it.name == folder.name }) return false
            updateFolderLocked(folderId) { it.copy(parentId = parentId, modifiedAt = clock()) }
            writeIndexLocked()
        }
        publishSummaries()
        return true
    }

    override suspend fun flush(): Unit = flushNow()

    override fun closeDocument(documentId: String) {
        syncLock(lock) {
            openDocuments[documentId]?.let { writeDocumentLocked(it) }
            dirtyDocuments.remove(documentId)
            openDocuments.remove(documentId)
            writeIndexLocked()
        }
        publishSummaries()
    }

    /**
     * Writes the modified documents immediately.
     */
    internal fun flushNow() {
        syncLock(lock) {
            saveJob?.cancel()
            saveJob = null
            for (id in dirtyDocuments) {
                openDocuments[id]?.let { writeDocumentLocked(it) }
            }
            dirtyDocuments.clear()
            writeIndexLocked()
        }
        publishSummaries()
    }

    // ==========================================================
    // DocumentPersistence: every change rewrites the owning document
    // ==========================================================

    override suspend fun saveStrokes(page: DrawPage, strokes: List<DrawStroke>): Unit = markPageModified(page)

    override suspend fun saveText(page: DrawPage, text: DrawText): Unit = markPageModified(page)

    override suspend fun saveImage(page: DrawPage, image: DrawImage): Unit = markPageModified(page)

    override suspend fun savePdf(page: DrawPage, pdf: DrawPdf): Unit = markPageModified(page)

    override suspend fun saveTextMarkup(page: DrawPage, markup: DrawTextMarkup): Unit = markPageModified(page)

    override suspend fun saveCustomElement(page: DrawPage, element: DrawCustomElement): Unit = markPageModified(page)

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun saveResource(document: DrawDocument, resource: DrawResource) {
        syncLock(lock) {
            PlatformFiles.readBytes(resource.path)?.let { bytes ->
                if (!storage.set(resourceKey(resource.path), Base64.encode(bytes))) {
                    lastWriteSucceeded = false
                    println("DrawView: the storage quota does not allow saving the resource ${resource.path}")
                }
            }
        }
        markDocumentModified(document)
    }

    override suspend fun savePage(document: DrawDocument, page: DrawPage): Unit = markDocumentModified(document)

    override suspend fun appendOperation(document: DrawDocument, operation: Operation) {
        syncLock(lock) {
            val operations = operationsLocked(document.id)
            if (operations.none { it.id == operation.id }) {
                operations.add(operation)
                storage.set(operationsKey(document.id), json.encodeToString(ListSerializer(Operation.serializer()), operations))
            }
        }
    }

    // ==========================================================
    // Replication
    // ==========================================================

    override suspend fun replicaId(): String = syncLock(lock) {
        index.replicaId.ifEmpty {
            index = index.copy(replicaId = newElementId())
            writeIndexLocked()
            index.replicaId
        }
    }

    override suspend fun sharingSecrets(): Map<String, String> = syncLock(lock) { index.sharingSecrets }

    override suspend fun setSharingSecret(documentId: String, secret: String?, readOnly: Boolean) {
        syncLock(lock) {
            val secrets = if (secret == null) index.sharingSecrets - documentId else index.sharingSecrets + (documentId to secret)
            val readOnlyDocuments = if (secret != null && readOnly) index.readOnly + documentId else index.readOnly - documentId
            index = index.copy(sharingSecrets = secrets, readOnly = readOnlyDocuments)
            writeIndexLocked()
        }
        publishSummaries()
    }

    override suspend fun isReadOnly(documentId: String): Boolean = syncLock(lock) { documentId in index.readOnly }

    override suspend fun fileWithContent(hash: String): String? = if (hash.isEmpty()) null else syncLock(lock) {
        index.documents.firstNotNullOfOrNull { summary ->
            val resources = openDocuments[summary.id]?.resources?.map { it.hash to it.path }
                ?: storage.get(documentKey(summary.id))?.let { decodeOrNull<StoredDocument>(it) }?.resources?.map { it.hash to it.uri }
            resources?.firstOrNull { (resourceHash, path) -> resourceHash == hash && path.isNotEmpty() }?.second
        }
    }

    override suspend fun compactedOperations(documentId: String): Map<String, Long> = syncLock(lock) { index.operationBases[documentId].orEmpty() }

    override suspend fun compactOperations(documentId: String, upTo: Map<String, Long>): Map<String, Stamp> = syncLock(lock) {
        val operations = operationsLocked(documentId)
        val stamps = index.operationBaseStamps[documentId].orEmpty().toMutableMap()
        val bases = index.operationBases[documentId].orEmpty().toMutableMap()
        val proofs = proofsLocked(documentId)
        for ((replica, sequence) in upTo) {
            if (sequence <= (bases[replica] ?: 0L)) continue
            val last = operations.firstOrNull { it.id.replica == replica && it.id.sequence == sequence } ?: continue
            operations.removeAll { it.id.replica == replica && it.id.sequence <= sequence }
            proofs.keys.removeAll { it.replica == replica && it.sequence <= sequence }
            bases[replica] = sequence
            stamps[replica] = last.stamp
        }
        storage.set(operationsKey(documentId), json.encodeToString(ListSerializer(Operation.serializer()), operations))
        writeProofsLocked(documentId)
        index = index.copy(operationBases = index.operationBases + (documentId to bases), operationBaseStamps = index.operationBaseStamps + (documentId to stamps))
        writeIndexLocked()
        stamps
    }

    override suspend fun versionVector(documentId: String): Map<String, Long> = syncLock(lock) {
        val vector = index.operationBases[documentId].orEmpty().toMutableMap()
        operationsLocked(documentId).forEach { vector[it.id.replica] = maxOf(vector[it.id.replica] ?: 0L, it.id.sequence) }
        vector
    }


    override suspend fun operationsAfter(documentId: String, known: Map<String, Long>): List<Operation> = syncLock(lock) {
        operationsLocked(documentId).filter { it.id.sequence > (known[it.id.replica] ?: 0L) }.sortedBy { it.stamp }
    }

    override suspend fun proofs(documentId: String, ids: Collection<OperationId>): Map<OperationId, ByteArray> = syncLock(lock) {
        val proofs = proofsLocked(documentId)
        ids.mapNotNull { id -> proofs[id]?.let { id to it } }.toMap()
    }

    override suspend fun saveProofs(documentId: String, proofs: Map<OperationId, ByteArray>) {
        if (proofs.isEmpty()) return
        syncLock(lock) {
            // Not for a document deleted meanwhile
            if (index.documents.none { it.id == documentId }) return@syncLock
            proofsLocked(documentId).putAll(proofs)
            writeProofsLocked(documentId)
        }
    }

    private val operations = mutableMapOf<String, MutableList<Operation>>()

    /** The proofs of the operations of the documents read so far (see [OperationLog.saveProofs]). */
    private val proofs = mutableMapOf<String, MutableMap<OperationId, ByteArray>>()

    private fun proofsLocked(documentId: String): MutableMap<OperationId, ByteArray> = proofs.getOrPut(documentId) {
        storage.get(proofsKey(documentId))
            ?.let { decodeOrNull<StoredProofs>(it) }
            ?.proofs.orEmpty()
            .mapNotNull { stored -> decodeProof(stored.proof)?.let { OperationId(stored.replica, stored.sequence) to it } }
            .toMap(mutableMapOf())
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun writeProofsLocked(documentId: String) {
        val proofs = proofsLocked(documentId)
        if (proofs.isEmpty()) {
            storage.remove(proofsKey(documentId))
            return
        }
        val stored = StoredProofs(proofs = proofs.map { (id, proof) -> StoredProof(id.replica, id.sequence, Base64.encode(proof)) })
        storage.set(proofsKey(documentId), json.encodeToString(StoredProofs.serializer(), stored))
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeProof(text: String): ByteArray? = try {
        Base64.decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun operationsLocked(documentId: String): MutableList<Operation> = operations.getOrPut(documentId) {
        storage.get(operationsKey(documentId))
            ?.let { decodeOrNull<List<Operation>>(it) }
            .orEmpty()
            .toMutableList()
    }

    override suspend fun updateDocumentDefaults(document: DrawDocument) {
        syncLock(lock) {
            updateSummaryLocked(document.id) {
                it.copy(
                    defaultWidth = document.defaultWidthMm,
                    defaultHeight = document.defaultHeightMm,
                    defaultBackground = document.defaultBackground
                )
            }
        }
        markDocumentModified(document)
        publishSummaries()
    }

    override suspend fun touchDocument(document: DrawDocument, timestamp: Long) {
        syncLock(lock) { updateSummaryLocked(document.id) { it.copy(modifiedAt = timestamp) } }
        markDocumentModified(document)
        publishSummaries()
    }

    override suspend fun preference(key: String): String? = storage.get(PREFERENCE_PREFIX + key)

    override suspend fun setPreference(key: String, value: String?) {
        if (value == null) storage.remove(PREFERENCE_PREFIX + key)
        else if (!storage.set(PREFERENCE_PREFIX + key, value)) lastWriteSucceeded = false
    }

    // ==========================================================
    // Internals
    // ==========================================================

    private fun markPageModified(page: DrawPage) {
        val owner = syncLock(lock) {
            openDocuments.values.firstOrNull { doc -> doc.pages.any { it === page } }
        }
        if (owner != null) markDocumentModified(owner) else markAllOpenModified()
    }

    private fun markAllOpenModified() {
        syncLock(lock) { dirtyDocuments.addAll(openDocuments.keys) }
        scheduleSave()
    }

    private fun markDocumentModified(document: DrawDocument) {
        syncLock(lock) {
            // Documents of other stores (e.g. kept in memory) are not written
            if (index.documents.none { it.id == document.id }) return
            openDocuments.getOrPut(document.id) { document }
            dirtyDocuments.add(document.id)
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        syncLock(lock) {
            if (saveJob?.isActive == true) return
            saveJob = scope.launch {
                delay(saveDelayMs)
                syncLock(lock) { saveJob = null }
                flushNow()
            }
        }
    }

    private inline fun updateSummaryLocked(documentId: String, transform: (StoredSummary) -> StoredSummary) {
        index = index.copy(documents = index.documents.map { if (it.id == documentId) transform(it) else it })
    }

    private inline fun updateFolderLocked(folderId: String, transform: (StoredFolder) -> StoredFolder) {
        index = index.copy(folders = index.folders.map { if (it.id == folderId) transform(it) else it })
    }

    /** The files of the resources of every document but [documentId] (the same content is kept once). */
    private fun pathsOfOtherDocumentsLocked(documentId: String): Set<String> =
        index.documents.asSequence().filter { it.id != documentId }.flatMap { summary ->
            openDocuments[summary.id]?.resources?.map { it.path }
                ?: storage.get(documentKey(summary.id))?.let { decodeOrNull<StoredDocument>(it) }?.resources?.map { it.uri }
                ?: emptyList()
        }.toSet()

    /**
     * Removes a document, its stored content and its resources, but the files other documents
     * point at too (the caller writes the index).
     */
    private fun deleteDocumentLocked(documentId: String) {
        val stored = storage.get(documentKey(documentId))?.let { decodeOrNull<StoredDocument>(it) }
        val shared = stored?.resources?.takeIf { it.isNotEmpty() }?.let { pathsOfOtherDocumentsLocked(documentId) }.orEmpty()
        stored?.resources?.filter { it.uri.isNotEmpty() && it.uri !in shared }?.forEach { resource ->
            storage.remove(resourceKey(resource.uri))
            try {
                PlatformFiles.delete(resource.uri)
            } catch (_: Throwable) {
            }
        }
        storage.remove(documentKey(documentId))
        storage.remove(operationsKey(documentId))
        operations.remove(documentId)
        storage.remove(proofsKey(documentId))
        proofs.remove(documentId)
        openDocuments.remove(documentId)
        dirtyDocuments.remove(documentId)
        index = index.copy(
            documents = index.documents.filter { it.id != documentId },
            sharingSecrets = index.sharingSecrets - documentId,
            readOnly = index.readOnly - documentId,
            operationBases = index.operationBases - documentId,
            operationBaseStamps = index.operationBaseStamps - documentId
        )
    }

    private fun publishSummaries() {
        val (documents, folders) = syncLock(lock) { sortedSummaries(index) to folderSummaries(index) }
        _documents.value = documents
        _folders.value = folders
    }

    private fun writeIndexLocked() {
        if (!storage.set(INDEX_KEY, json.encodeToString(StoredIndex.serializer(), index))) {
            lastWriteSucceeded = false
        }
    }

    private fun writeDocumentLocked(document: DrawDocument) {
        val stored = document.toStored()
        val pageCount = document.pages.size
        val hasPdf = document.pages.any { it.pdfs.isNotEmpty() }
        // What the library tells of the content (published with the next summaries)
        updateSummaryLocked(document.id) { it.copy(pageCount = pageCount, hasPdf = hasPdf) }
        val written = storage.set(documentKey(document.id), json.encodeToString(StoredDocument.serializer(), stored))
        if (!written) {
            lastWriteSucceeded = false
            println("DrawView: the storage quota does not allow saving the document ${document.id}")
        } else {
            lastWriteSucceeded = true
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun restoreResourceFile(path: String) {
        if (PlatformFiles.exists(path)) return
        val encoded = storage.get(resourceKey(path)) ?: return
        try {
            PlatformFiles.writeBytes(path, Base64.decode(encoded))
        } catch (e: Throwable) {
            println("DrawView: cannot restore the resource $path: ${e.message}")
        }
    }

    private fun readIndex(): StoredIndex =
        storage.get(INDEX_KEY)?.let { decodeOrNull<StoredIndex>(it) } ?: StoredIndex()


    private inline fun <reified T> decodeOrNull(value: String): T? = try {
        json.decodeFromString<T>(value)
    } catch (e: Throwable) {
        println("DrawView: unreadable stored value (${e.message})")
        null
    }

    private fun sortedSummaries(index: StoredIndex): List<DocumentSummary> =
        index.documents.sortedByDescending { it.modifiedAt }.map { it.toSummary(isShared = it.id in index.sharingSecrets) }

    private fun folderSummaries(index: StoredIndex): List<FolderSummary> =
        index.folders.sortedBy { it.name }.map { it.toSummary() }

    public companion object {
        private const val PREFIX = "drawview."
        private const val INDEX_KEY = PREFIX + "index"
        private const val PREFERENCE_PREFIX = PREFIX + "preference."

        private fun documentKey(id: String) = PREFIX + "document." + id
        private fun operationsKey(id: String) = PREFIX + "operations." + id
        private fun proofsKey(id: String) = PREFIX + "proofs." + id
        private fun resourceKey(path: String) = PREFIX + "file." + path
    }
}

// ==========================================================
// Stored model
// ==========================================================

@Serializable
internal data class StoredIndex(
    /** This device in the changes of the documents (see `DocumentSession.replica`). */
    val replicaId: String = "",
    val documents: List<StoredSummary> = emptyList(),
    val folders: List<StoredFolder> = emptyList(),
    /** The secrets of the documents shared, by document (see [DocumentStore.sharingSecrets]). */
    val sharingSecrets: Map<String, String> = emptyMap(),
    /** The documents shared to be read only here. */
    val readOnly: Set<String> = emptySet(),
    /** The operations no longer in the logs, by document (see [DocumentStore.compactOperations]), and their stamps. */
    val operationBases: Map<String, Map<String, Long>> = emptyMap(),
    val operationBaseStamps: Map<String, Map<String, Stamp>> = emptyMap()
)

@Serializable
internal data class StoredFolder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long
) {
    fun toSummary() = FolderSummary(id = id, name = name, parentId = parentId, createdAt = createdAt, modifiedAt = modifiedAt)
}

@Serializable
internal data class StoredSummary(
    val id: String,
    val name: String,
    val folderId: String? = null,
    val createdAt: Long,
    val modifiedAt: Long,
    val lastOpenedAt: Long,
    val defaultWidth: Float = 210f,
    val defaultHeight: Float = 297f,
    val defaultBackground: PageBackground = PageBackground.Solid(),
    val pageCount: Int = 1,
    val hasPdf: Boolean = false
) {
    fun toSummary(isShared: Boolean) = DocumentSummary(
        id = id,
        name = name,
        folderId = folderId,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        lastOpenedAt = lastOpenedAt,
        defaultWidthMm = defaultWidth,
        defaultHeightMm = defaultHeight,
        defaultBackground = defaultBackground,
        isShared = isShared,
        pageCount = pageCount,
        hasPdf = hasPdf
    )
}

@Serializable
internal data class StoredDocument(
    val pages: List<PageSnapshot> = emptyList(),
    val resources: List<StoredResource> = emptyList(),
    val defaultsVersion: Stamp = Stamp.ZERO,
    val removedPages: List<PageSnapshot> = emptyList(),
    val titleVersion: Stamp = Stamp.ZERO
)

@Serializable
internal data class StoredResource(val id: String, val type: String, val uri: String, val hash: String = "")

/** The proofs of the operations of a document (see [OperationLog.saveProofs]), format [version]. */
@Serializable
internal data class StoredProofs(val version: Int = 1, val proofs: List<StoredProof> = emptyList())

/** The proof of the operation [replica] / [sequence], in Base64. */
@Serializable
internal data class StoredProof(val replica: String, val sequence: Long, val proof: String)


private fun DrawDocument.toStored(): StoredDocument = StoredDocument(
    pages = pages.map { it.snapshot() },
    resources = resources.map { StoredResource(it.id, it.type.name, it.path, it.hash) },
    defaultsVersion = defaultsVersion,
    removedPages = removedPages.values.map { it.snapshot() },
    titleVersion = titleVersion
)

