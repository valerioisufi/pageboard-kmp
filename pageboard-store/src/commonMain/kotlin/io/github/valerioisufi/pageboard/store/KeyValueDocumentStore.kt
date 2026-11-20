package io.github.valerioisufi.pageboard.store

import io.github.valerioisufi.pageboard.model.CustomElement
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.PdfElement
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.TextMarkupElement
import io.github.valerioisufi.pageboard.model.KotlinStrokeModeller
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.StrokeModeller
import io.github.valerioisufi.pageboard.model.change.DocumentPersistence
import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.OperationId
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.platform.PlatformFiles
import io.github.valerioisufi.pageboard.platform.SyncLock
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import io.github.valerioisufi.pageboard.platform.syncLock
import io.github.valerioisufi.pageboard.store.space.CatalogItem
import io.github.valerioisufi.pageboard.store.space.CatalogOperation
import io.github.valerioisufi.pageboard.store.space.SpaceStore
import io.github.valerioisufi.pageboard.store.space.StoredSpace
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
 * `RoomDocumentStore` (`pageboard-store-room`), used in memory by the tests (with [InMemoryKeyValueStorage]).
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
) : DocumentStore, DocumentPersistence, PreferenceStore, SpaceStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val lock = SyncLock()

    /** The operations of the documents and their proofs (under [lock]). */
    private val log = KeyValueOperationLog(storage, json)

    /** The shared folders, their catalogs and logs (under [lock]). */
    private val spaceValues = KeyValueSpaces(storage, json)

    private var index: StoredIndex = readIndex()
    private val openDocuments = mutableMapOf<String, Document>()
    private val dirtyDocuments = mutableSetOf<String>()
    private var saveJob: Job? = null

    private val _documents = MutableStateFlow(sortedSummaries(index))
    override val documents: StateFlow<List<DocumentSummary>> = _documents.asStateFlow()

    private val _folders = MutableStateFlow(syncLock(lock) { folderSummaries(index) })
    override val folders: StateFlow<List<FolderSummary>> = _folders.asStateFlow()

    private val _trash = MutableStateFlow(index.trash().items)
    override val trash: StateFlow<List<TrashedItem>> = _trash.asStateFlow()

    override val persistence: DocumentPersistence get() = this

    // ==========================================================
    // DocumentStore
    // ==========================================================

    override suspend fun createDocument(
        name: String,
        widthMm: Float,
        heightMm: Float,
        background: PageBackground,
        folderId: String?
    ): Document {
        val now = clock()
        val document = syncLock(lock) {
            val document = Document(
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

    override suspend fun insertDocument(document: Document, folderId: String?, versionVector: Map<String, Long>) {
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

    override suspend fun loadDocument(documentId: String): Document? = syncLock(lock) {
        readDocumentLocked(documentId)?.also { openDocuments[documentId] = it }
    }

    /** Read from the document stored, not taken as the document open (which a search would replace). */
    override suspend fun loadOutline(documentId: String): DocumentOutline? = syncLock(lock) {
        readDocumentLocked(documentId)?.let { DocumentOutline.of(it) }
    }

    /** Read from the document stored, as [loadOutline]. */
    override suspend fun pageFingerprint(documentId: String, index: Int): String? = syncLock(lock) {
        readDocumentLocked(documentId)?.pageFingerprint(index)
    }

    /** Read from the document stored, as [loadOutline]. */
    override suspend fun loadPage(documentId: String, index: Int): LoadedPage? = syncLock(lock) {
        readDocumentLocked(documentId)?.let { LoadedPage.of(it, index) }
    }

    private fun readDocumentLocked(documentId: String): Document? {
        val summary = index.documents.firstOrNull { it.id == documentId } ?: return null
        // Loaded again while open: with its last changes
        if (dirtyDocuments.remove(documentId)) openDocuments[documentId]?.let { writeDocumentLocked(it) }
        val stored = storage.get(documentKey(documentId))?.let { decodeOrNull<StoredDocument>(it) }
        val document = Document(
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
            val type = DocumentResource.ResourceType.entries.firstOrNull { it.name == resource.type }
                ?: DocumentResource.ResourceType.IMAGE
            restoreResourceFile(resource.uri)
            document.addResource(DocumentResource(resource.id, type, resource.uri, resource.hash))
        }
        document.defaultsVersion = stored?.defaultsVersion ?: Stamp.ZERO
        document.titleVersion = stored?.titleVersion ?: Stamp.ZERO
        stored?.pages?.forEach { storedPage -> document.appendPage(storedPage.toPage(document)) }
        stored?.removedPages?.forEach { storedPage -> storedPage.toPage(document).let { document.removedPages[it.id] = it } }
        document.reindexPages()
        return document
    }

    override suspend fun isDocumentNameAvailable(documentId: String, name: String): Boolean = syncLock(lock) {
        val summary = index.documents.firstOrNull { it.id == documentId } ?: return false
        val trash = index.trash()
        index.documents.none { it.id != documentId && it.folderId == summary.folderId && it.name == name && trash.isDocumentLive(it.id) }
    }

    override suspend fun updateDocumentTitle(document: Document) {
        syncLock(lock) {
            updateSummaryLocked(document.id) { it.copy(name = document.title) }
            writeIndexLocked()
        }
        markDocumentModified(document)
        publishSummaries()
    }

    override suspend fun trashDocument(documentId: String): Boolean {
        syncLock(lock) {
            if (!index.trash().isDocumentLive(documentId)) return false
            updateSummaryLocked(documentId) { it.copy(trashedAt = clock()) }
            writeIndexLocked()
        }
        publishSummaries()
        return true
    }

    override suspend fun trashFolder(folderId: String): Boolean {
        syncLock(lock) {
            if (index.folders.none { it.id == folderId } || !index.trash().isFolderLive(folderId)) return false
            updateFolderLocked(folderId) { it.copy(trashedAt = clock()) }
            writeIndexLocked()
        }
        publishSummaries()
        return true
    }

    override suspend fun restore(itemId: String): String? {
        val name = syncLock(lock) {
            val trash = index.trash()
            val item = trash.item(itemId) ?: return null
            val target = trash.restoreTarget(item)
            val name = when (item.kind) {
                TrashedItem.Kind.DOCUMENT -> trash.availableDocumentName(item.name, target, item.id).also { name ->
                    updateSummaryLocked(item.id) { it.copy(name = name, folderId = target, trashedAt = null) }
                    openDocuments[item.id]?.title = name
                }
                TrashedItem.Kind.FOLDER -> trash.availableFolderName(item.name, target, item.id).also { name ->
                    updateFolderLocked(item.id) { it.copy(name = name, parentId = target, trashedAt = null) }
                }
            }
            writeIndexLocked()
            name
        }
        publishSummaries()
        return name
    }

    override suspend fun purgeTrash(before: Long) {
        val expired = syncLock(lock) { index.trash().items.filter { it.trashedAt < before }.sortedBy { it.trashedAt } }
        for (item in expired) {
            when (item.kind) {
                TrashedItem.Kind.DOCUMENT -> deleteDocument(item.id)
                TrashedItem.Kind.FOLDER -> deleteFolder(item.id)
            }
        }
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
            val trash = index.trash()
            if (!trash.isDocumentLive(documentId) || !trash.isFolderLive(folderId)) return false
            if (index.documents.any { it.folderId == folderId && it.name == summary.name && trash.isDocumentLive(it.id) }) return false
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
            val trash = index.trash()
            if (!trash.isFolderLive(parentId)) return false
            if (index.folders.any { it.parentId == parentId && it.name == name && trash.isFolderLive(it.id) }) return false
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
            val trash = index.trash()
            if (index.folders.any { it.id != folderId && it.parentId == folder.parentId && it.name == newName && trash.isFolderLive(it.id) }) return false
            updateFolderLocked(folderId) { it.copy(name = newName, modifiedAt = clock()) }
            writeIndexLocked()
        }
        publishSummaries()
        return true
    }

    override suspend fun deleteFolder(folderId: String): Boolean {
        syncLock(lock) {
            if (index.folders.none { it.id == folderId }) return false
            // What is in the trash on its own stays there, at its root
            for (item in index.trash().itemsInside(folderId)) {
                when (item.kind) {
                    TrashedItem.Kind.DOCUMENT -> updateSummaryLocked(item.id) { it.copy(folderId = null) }
                    TrashedItem.Kind.FOLDER -> updateFolderLocked(item.id) { it.copy(parentId = null) }
                }
            }
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
            val trash = index.trash()
            if (!trash.isFolderLive(folderId) || !trash.isFolderLive(parentId)) return false
            // A folder cannot be moved into itself or into one of its sub-folders
            var ancestorId = parentId
            while (ancestorId != null) {
                if (ancestorId == folderId) return false
                ancestorId = index.folders.firstOrNull { it.id == ancestorId }?.parentId
            }
            if (index.folders.any { it.parentId == parentId && it.name == folder.name && trash.isFolderLive(it.id) }) return false
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

    override suspend fun saveStrokes(page: Page, strokes: List<StrokeElement>): Unit = markPageModified(page)

    override suspend fun saveText(page: Page, text: TextElement): Unit = markPageModified(page)

    override suspend fun saveImage(page: Page, image: ImageElement): Unit = markPageModified(page)

    override suspend fun savePdf(page: Page, pdf: PdfElement): Unit = markPageModified(page)

    override suspend fun saveTextMarkup(page: Page, markup: TextMarkupElement): Unit = markPageModified(page)

    override suspend fun saveCustomElement(page: Page, element: CustomElement): Unit = markPageModified(page)

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun saveResource(document: Document, resource: DocumentResource) {
        syncLock(lock) {
            PlatformFiles.readBytes(resource.path)?.let { bytes ->
                if (!storage.set(resourceKey(resource.path), Base64.encode(bytes))) {
                    println("Pageboard: the storage quota does not allow saving the resource ${resource.path}")
                }
            }
        }
        markDocumentModified(document)
    }

    override suspend fun savePage(document: Document, page: Page): Unit = markDocumentModified(document)

    override suspend fun appendOperation(document: Document, operation: Operation) {
        syncLock(lock) {
            val operations = log.operationsOf(document.id)
            if (operations.none { it.id == operation.id }) {
                operations.add(operation)
                log.writeOperations(document.id)
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
        val operations = log.operationsOf(documentId)
        val stamps = index.operationBaseStamps[documentId].orEmpty().toMutableMap()
        val bases = index.operationBases[documentId].orEmpty().toMutableMap()
        val proofs = log.proofsOf(documentId)
        for ((replica, sequence) in upTo) {
            if (sequence <= (bases[replica] ?: 0L)) continue
            val last = operations.firstOrNull { it.id.replica == replica && it.id.sequence == sequence } ?: continue
            operations.removeAll { it.id.replica == replica && it.id.sequence <= sequence }
            proofs.keys.removeAll { it.replica == replica && it.sequence <= sequence }
            bases[replica] = sequence
            stamps[replica] = last.stamp
        }
        log.writeOperations(documentId)
        log.writeProofs(documentId)
        index = index.copy(operationBases = index.operationBases + (documentId to bases), operationBaseStamps = index.operationBaseStamps + (documentId to stamps))
        writeIndexLocked()
        stamps
    }

    override suspend fun versionVector(documentId: String): Map<String, Long> = syncLock(lock) {
        val vector = index.operationBases[documentId].orEmpty().toMutableMap()
        log.operationsOf(documentId).forEach { vector[it.id.replica] = maxOf(vector[it.id.replica] ?: 0L, it.id.sequence) }
        vector
    }


    override suspend fun operationsAfter(documentId: String, known: Map<String, Long>): List<Operation> = syncLock(lock) {
        log.operationsOf(documentId).filter { it.id.sequence > (known[it.id.replica] ?: 0L) }.sortedBy { it.stamp }
    }

    override suspend fun proofs(documentId: String, ids: Collection<OperationId>): Map<OperationId, ByteArray> = syncLock(lock) {
        val proofs = log.proofsOf(documentId)
        ids.mapNotNull { id -> proofs[id]?.let { id to it } }.toMap()
    }

    override suspend fun saveProofs(documentId: String, proofs: Map<OperationId, ByteArray>) {
        if (proofs.isEmpty()) return
        syncLock(lock) {
            // Not for a document deleted meanwhile
            if (index.documents.none { it.id == documentId }) return@syncLock
            log.proofsOf(documentId).putAll(proofs)
            log.writeProofs(documentId)
        }
    }

    override suspend fun updateDocumentDefaults(document: Document) {
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

    override suspend fun touchDocument(document: Document, timestamp: Long) {
        syncLock(lock) { updateSummaryLocked(document.id) { it.copy(modifiedAt = timestamp) } }
        markDocumentModified(document)
        publishSummaries()
    }

    // ==========================================================
    // SpaceStore
    // ==========================================================

    override suspend fun spaces(): List<StoredSpace> = syncLock(lock) { spaceValues.spaces() }

    override suspend fun saveSpace(space: StoredSpace) {
        syncLock(lock) {
            val value = spaceValues.valueOf(space.id) ?: StoredSpaceValue()
            spaceValues.write(space.id, value.copy(rootFolderId = space.rootFolderId, sharing = space.sharing, readOnly = space.readOnly))
        }
        // The root of a shared folder is told in the folders
        publishSummaries()
    }

    override suspend fun removeSpace(spaceId: String) {
        syncLock(lock) { spaceValues.remove(spaceId) }
        publishSummaries()
    }

    override suspend fun catalogItems(spaceId: String): List<CatalogItem> = syncLock(lock) { spaceValues.valueOf(spaceId)?.items.orEmpty() }

    override suspend fun saveCatalogChange(spaceId: String, items: Collection<CatalogItem>, operation: CatalogOperation): Unit = syncLock(lock) {
        val value = spaceValues.valueOf(spaceId) ?: return@syncLock
        if (value.operations.any { it.id == operation.id }) return@syncLock
        val changed = items.associateBy { it.id }
        val kept = value.items.map { changed[it.id] ?: it }
        val added = changed.values.filter { item -> value.items.none { it.id == item.id } }
        spaceValues.write(spaceId, value.copy(items = kept + added, operations = value.operations + operation))
    }

    override suspend fun catalogVersionVector(spaceId: String): Map<String, Long> = syncLock(lock) {
        spaceValues.valueOf(spaceId)?.operations.orEmpty().groupBy { it.id.replica }.mapValues { (_, ops) -> ops.maxOf { it.id.sequence } }
    }

    override suspend fun catalogOperationsAfter(spaceId: String, known: Map<String, Long>): List<CatalogOperation> = syncLock(lock) {
        spaceValues.valueOf(spaceId)?.operations.orEmpty().filter { it.id.sequence > (known[it.id.replica] ?: 0L) }.sortedBy { it.stamp }
    }

    override suspend fun catalogProofs(spaceId: String, ids: Collection<OperationId>): Map<OperationId, ByteArray> = syncLock(lock) {
        val value = spaceValues.valueOf(spaceId) ?: return@syncLock emptyMap()
        val wanted = ids.toSet()
        spaceValues.proofsOf(value).filterKeys { it in wanted }
    }

    override suspend fun saveCatalogProofs(spaceId: String, proofs: Map<OperationId, ByteArray>): Unit = syncLock(lock) {
        val value = spaceValues.valueOf(spaceId) ?: return@syncLock
        spaceValues.write(spaceId, value.copy(proofs = spaceValues.storedProofs(spaceValues.proofsOf(value) + proofs)))
    }

    override suspend fun putFolder(folderId: String, name: String, parentId: String?) {
        syncLock(lock) {
            val now = clock()
            index = if (index.folders.any { it.id == folderId }) {
                index.copy(folders = index.folders.map { if (it.id == folderId) it.copy(name = name, parentId = parentId, modifiedAt = now) else it })
            } else {
                index.copy(folders = index.folders + StoredFolder(folderId, name, parentId, createdAt = now, modifiedAt = now))
            }
            writeIndexLocked()
        }
        publishSummaries()
    }

    override suspend fun putDocumentFolder(documentId: String, folderId: String?): Boolean {
        syncLock(lock) {
            if (index.documents.none { it.id == documentId }) return false
            updateSummaryLocked(documentId) { it.copy(folderId = folderId) }
            writeIndexLocked()
        }
        publishSummaries()
        return true
    }

    override suspend fun putTrashed(itemId: String, trashed: Boolean) {
        syncLock(lock) {
            val trashedAt = if (trashed) clock() else null
            when {
                index.documents.any { it.id == itemId } -> updateSummaryLocked(itemId) { if (trashed && it.trashedAt != null) it else it.copy(trashedAt = trashedAt) }
                index.folders.any { it.id == itemId } -> updateFolderLocked(itemId) { if (trashed && it.trashedAt != null) it else it.copy(trashedAt = trashedAt) }
                else -> return
            }
            writeIndexLocked()
        }
        publishSummaries()
    }

    override suspend fun preference(key: String): String? = storage.get(PREFERENCE_PREFIX + key)

    override suspend fun setPreference(key: String, value: String?) {
        if (value == null) storage.remove(PREFERENCE_PREFIX + key)
        else storage.set(PREFERENCE_PREFIX + key, value)
    }

    // ==========================================================
    // Internals
    // ==========================================================

    private fun markPageModified(page: Page) {
        val owner = syncLock(lock) {
            openDocuments.values.firstOrNull { doc -> doc.pages.any { it === page } }
        }
        if (owner != null) markDocumentModified(owner) else markAllOpenModified()
    }

    private fun markAllOpenModified() {
        syncLock(lock) { dirtyDocuments.addAll(openDocuments.keys) }
        scheduleSave()
    }

    private fun markDocumentModified(document: Document) {
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

    /**
     * The files of the resources of every document but [documentId] (the same content is kept
     * once), or `null` when a stored document cannot be read: its files are not known then.
     */
    private fun pathsOfOtherDocumentsLocked(documentId: String): Set<String>? {
        val paths = mutableSetOf<String>()
        for (summary in index.documents) {
            if (summary.id == documentId) continue
            val open = openDocuments[summary.id]
            if (open != null) {
                open.resources.mapTo(paths) { it.path }
                continue
            }
            val value = storage.get(documentKey(summary.id)) ?: continue
            val stored = decodeOrNull<StoredDocument>(value) ?: return null
            stored.resources.mapTo(paths) { it.uri }
        }
        return paths
    }

    /**
     * Removes a document, its stored content and its resources, but the files other documents
     * point at too, and every file while a stored document cannot be read (the caller writes the
     * index).
     */
    private fun deleteDocumentLocked(documentId: String) {
        val stored = storage.get(documentKey(documentId))?.let { decodeOrNull<StoredDocument>(it) }
        val shared = if (stored?.resources.isNullOrEmpty()) emptySet() else pathsOfOtherDocumentsLocked(documentId)
        stored?.resources?.filter { it.uri.isNotEmpty() && shared != null && it.uri !in shared }?.forEach { resource ->
            storage.remove(resourceKey(resource.uri))
            try {
                PlatformFiles.delete(resource.uri)
            } catch (_: Throwable) {
            }
        }
        storage.remove(documentKey(documentId))
        log.forget(documentId)
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
        val (documents, folders, trash) = syncLock(lock) { Triple(sortedSummaries(index), folderSummaries(index), index.trash().items) }
        _documents.value = documents
        _folders.value = folders
        _trash.value = trash
    }

    private fun writeIndexLocked() {
        storage.set(INDEX_KEY, json.encodeToString(StoredIndex.serializer(), index))
    }

    private fun writeDocumentLocked(document: Document) {
        val stored = document.toStored()
        val pageCount = document.pages.size
        val hasPdf = document.pages.any { it.pdfs.isNotEmpty() }
        // What the library tells of the content (published with the next summaries)
        updateSummaryLocked(document.id) { it.copy(pageCount = pageCount, hasPdf = hasPdf) }
        val written = storage.set(documentKey(document.id), json.encodeToString(StoredDocument.serializer(), stored))
        if (!written) println("Pageboard: the storage quota does not allow saving the document ${document.id}")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun restoreResourceFile(path: String) {
        if (PlatformFiles.exists(path)) return
        val encoded = storage.get(resourceKey(path)) ?: return
        try {
            PlatformFiles.writeBytes(path, Base64.decode(encoded))
        } catch (e: Throwable) {
            println("Pageboard: cannot restore the resource $path: ${e.message}")
        }
    }

    private fun readIndex(): StoredIndex =
        storage.get(INDEX_KEY)?.let { decodeOrNull<StoredIndex>(it) } ?: StoredIndex()


    private inline fun <reified T> decodeOrNull(value: String): T? = try {
        json.decodeFromString<T>(value)
    } catch (e: Throwable) {
        println("Pageboard: unreadable stored value (${e.message})")
        null
    }

    private fun sortedSummaries(index: StoredIndex): List<DocumentSummary> {
        val trash = index.trash()
        return index.documents.filter { trash.isDocumentLive(it.id) }.sortedByDescending { it.modifiedAt }
            .map { it.toSummary(isShared = it.id in index.sharingSecrets) }
    }

    /** The folders out of the trash, the roots of the shared folders told (under [lock]). */
    private fun folderSummaries(index: StoredIndex): List<FolderSummary> {
        val trash = index.trash()
        val shared = spaceValues.spaces().mapNotNullTo(mutableSetOf()) { it.rootFolderId }
        return index.folders.filter { trash.isFolderLive(it.id) }.sortedBy { it.name }.map { it.toSummary().copy(isShared = it.id in shared) }
    }

    public companion object {
        private const val PREFIX = "pageboard."
        private const val INDEX_KEY = PREFIX + "index"
        private const val PREFERENCE_PREFIX = PREFIX + "preference."

        private fun documentKey(id: String) = PREFIX + "document." + id
        private fun resourceKey(path: String) = PREFIX + "file." + path
    }
}
