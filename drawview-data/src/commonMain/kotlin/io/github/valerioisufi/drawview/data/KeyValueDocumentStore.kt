package io.github.valerioisufi.drawview.data

import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.core.change.StrokeData
import io.github.valerioisufi.drawview.core.change.TextMarkupData
import io.github.valerioisufi.drawview.core.change.toData
import io.github.valerioisufi.drawview.core.controller.DrawPersistence
import io.github.valerioisufi.drawview.core.controller.DrawPreferences
import io.github.valerioisufi.drawview.core.controller.ToolPreset
import io.github.valerioisufi.drawview.core.geometry.AffineTransform
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawPdf
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup
import io.github.valerioisufi.drawview.core.model.InputToolType
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.StrokeTool
import io.github.valerioisufi.drawview.core.model.TextMarkupKind
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.core.model.newElementId
import io.github.valerioisufi.drawview.core.model.readSnapshot
import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.core.util.SyncLock
import io.github.valerioisufi.drawview.core.util.syncLock
import io.github.valerioisufi.drawview.data.db.BrushSettingsData
import io.github.valerioisufi.drawview.data.db.StrokeInputCodec
import io.github.valerioisufi.drawview.data.db.currentTimeMillis
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Minimal string key-value storage.
 */
interface KeyValueStorage {
    fun get(key: String): String?

    /** Stores [value]; `false` when it could not be written (e.g. storage quota exceeded). */
    fun set(key: String, value: String): Boolean

    fun remove(key: String)
}

/**
 * [KeyValueStorage] kept in memory (tests).
 */
class InMemoryKeyValueStorage : KeyValueStorage {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun set(key: String, value: String): Boolean {
        values[key] = value
        return true
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    val keys: Set<String> get() = values.keys.toSet()
}

/**
 * [DocumentStore] that keeps every document as a JSON value of a [KeyValueStorage] (the folders
 * and the document summaries are kept in a single index value). Lightweight alternative to
 * [RoomDocumentStore], used in memory by the tests (with [InMemoryKeyValueStorage]).
 *
 * While a document is open, its in-memory model is the source of truth: every persistence
 * operation marks the document as modified and its whole content is written shortly after
 * (writes are coalesced). The files of the resources (imported images) are stored as Base64
 * values and restored into [PlatformFiles] when the document is loaded.
 */
class KeyValueDocumentStore(
    private val storage: KeyValueStorage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val saveDelayMs: Long = 400L
) : DocumentStore, DrawPersistence {

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

    private var storedPreferences: StoredPreferences = readPreferences()
    private val _preferences = MutableStateFlow(storedPreferences.toDrawPreferences())
    override val preferences: StateFlow<DrawPreferences> = _preferences.asStateFlow()

    override val persistence: DrawPersistence get() = this

    /** `false` after a write has been rejected by the storage (e.g. quota exceeded). */
    var lastWriteSucceeded: Boolean = true
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
        val now = currentTimeMillis()
        val document = syncLock(lock) {
            val document = DrawDocument(
                title = name,
                defaultWidthMm = widthMm,
                defaultHeightMm = heightMm,
                defaultBackground = background,
                createdAt = now,
                modifiedAt = now
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

    override suspend fun loadDocument(documentId: String): DrawDocument? {
        val document = syncLock(lock) {
            val summary = index.documents.firstOrNull { it.id == documentId } ?: return null
            val stored = storage.get(documentKey(documentId))?.let { decodeOrNull<StoredDocument>(it) }
            val document = DrawDocument(
                id = documentId,
                title = summary.name,
                defaultWidthMm = summary.defaultWidth,
                defaultHeightMm = summary.defaultHeight,
                defaultBackground = summary.defaultBackground,
                createdAt = summary.createdAt,
                modifiedAt = summary.modifiedAt,
                addInitialPage = false
            )
            document.lastOpenedAt = summary.lastOpenedAt

            stored?.resources?.forEach { resource ->
                val type = DrawResource.ResourceType.entries.firstOrNull { it.name == resource.type }
                    ?: DrawResource.ResourceType.IMAGE
                restoreResourceFile(resource.uri)
                document.resources.add(DrawResource(resource.id, type, resource.uri))
            }
            stored?.pages?.forEachIndexed { pageIndex, storedPage ->
                document.pages.add(storedPage.toPage(pageIndex, summary.defaultBackground))
            }
            if (document.pages.isEmpty()) {
                // A document must always have a page
                document.pages.add(
                    DrawPage(
                        index = 0,
                        widthMm = summary.defaultWidth,
                        heightMm = summary.defaultHeight,
                        background = summary.defaultBackground
                    )
                )
            }
            openDocuments[documentId] = document
            document
        }
        return document
    }

    override suspend fun renameDocument(documentId: String, newName: String): Boolean {
        val renamed = syncLock(lock) {
            val summary = index.documents.firstOrNull { it.id == documentId } ?: return false
            if (index.documents.any { it.id != documentId && it.folderId == summary.folderId && it.name == newName }) {
                return false
            }
            updateSummaryLocked(documentId) { it.copy(name = newName) }
            openDocuments[documentId]?.title = newName
            writeIndexLocked()
            true
        }
        publishSummaries()
        return renamed
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
            updateSummaryLocked(documentId) { it.copy(folderId = folderId, modifiedAt = currentTimeMillis()) }
            writeIndexLocked()
        }
        publishSummaries()
        return true
    }

    override suspend fun updateLastOpened(documentId: String) {
        syncLock(lock) {
            val now = currentTimeMillis()
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
            val now = currentTimeMillis()
            val folder = StoredFolder(newElementId(), name, parentId, createdAt = now, modifiedAt = now)
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
            updateFolderLocked(folderId) { it.copy(name = newName, modifiedAt = currentTimeMillis()) }
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
            updateFolderLocked(folderId) { it.copy(parentId = parentId, modifiedAt = currentTimeMillis()) }
            writeIndexLocked()
        }
        publishSummaries()
        return true
    }

    override suspend fun flush() = flushNow()

    override fun closeDocument(documentId: String) {
        syncLock(lock) {
            openDocuments[documentId]?.let { writeDocumentLocked(it) }
            dirtyDocuments.remove(documentId)
            openDocuments.remove(documentId)
            writeIndexLocked()
        }
    }

    /**
     * Writes the modified documents immediately.
     */
    fun flushNow() {
        syncLock(lock) {
            saveJob?.cancel()
            saveJob = null
            for (id in dirtyDocuments) {
                openDocuments[id]?.let { writeDocumentLocked(it) }
            }
            dirtyDocuments.clear()
            writeIndexLocked()
        }
    }

    // ==========================================================
    // DrawPersistence: every change rewrites the owning document
    // ==========================================================

    override suspend fun saveStrokes(page: DrawPage, strokes: List<DrawStroke>) = markPageModified(page)

    override suspend fun deleteStrokes(strokes: List<DrawStroke>) = markAllOpenModified()

    override suspend fun saveText(page: DrawPage, text: DrawText) = markPageModified(page)

    override suspend fun deleteText(text: DrawText) = markAllOpenModified()

    override suspend fun saveImage(page: DrawPage, image: DrawImage) = markPageModified(page)

    override suspend fun deleteImage(image: DrawImage) = markAllOpenModified()

    override suspend fun savePdf(page: DrawPage, pdf: DrawPdf) = markPageModified(page)

    override suspend fun deletePdf(pdf: DrawPdf) = markAllOpenModified()

    override suspend fun saveTextMarkup(page: DrawPage, markup: DrawTextMarkup) = markPageModified(page)

    override suspend fun deleteTextMarkup(markup: DrawTextMarkup) = markAllOpenModified()

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

    override suspend fun insertPage(document: DrawDocument, page: DrawPage, index: Int) = markDocumentModified(document)

    override suspend fun softDeletePage(document: DrawDocument, page: DrawPage, index: Int) = markDocumentModified(document)

    override suspend fun updatePagesOrder(document: DrawDocument, pages: List<DrawPage>) = markDocumentModified(document)

    override suspend fun updatePageFormat(document: DrawDocument, page: DrawPage) = markDocumentModified(document)

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

    override suspend fun savePresets(tool: Tool, presets: List<ToolPreset>) = updatePreferences {
        val data = presets.toBrushSettings(tool)
        when (tool) {
            Tool.INK_PEN -> it.copy(penPresets = data)
            Tool.INK_HIGHLIGHTER -> it.copy(highlighterPresets = data)
            Tool.TEXT_MARKUP -> it.copy(markupPresets = data)
            Tool.ERASER -> it.copy(eraserPresets = data)
            Tool.LAZO -> it.copy(lazoPresets = data)
            else -> it
        }
    }

    override suspend fun saveStylusOnlyMode(enabled: Boolean) = updatePreferences { it.copy(isStylusOnlyMode = enabled) }

    override suspend fun saveLastSelectedTool(tool: Tool) = updatePreferences { it.copy(lastSelectedTool = tool.name) }

    override suspend fun saveTextDefaults(fontSize: Float, color: Int) = updatePreferences {
        it.copy(defaultTextFontSize = fontSize, defaultTextColor = color)
    }

    // ==========================================================
    // Internals
    // ==========================================================

    private fun markPageModified(page: DrawPage) {
        val owner = syncLock(lock) {
            openDocuments.values.firstOrNull { doc -> readSnapshot { doc.pages.any { it === page } } }
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

    private fun updatePreferences(transform: (StoredPreferences) -> StoredPreferences) {
        val updated = syncLock(lock) {
            storedPreferences = transform(storedPreferences)
            if (!storage.set(PREFERENCES_KEY, json.encodeToString(StoredPreferences.serializer(), storedPreferences))) {
                lastWriteSucceeded = false
            }
            storedPreferences
        }
        _preferences.value = updated.toDrawPreferences()
    }

    private inline fun updateSummaryLocked(documentId: String, transform: (StoredSummary) -> StoredSummary) {
        index = index.copy(documents = index.documents.map { if (it.id == documentId) transform(it) else it })
    }

    private inline fun updateFolderLocked(folderId: String, transform: (StoredFolder) -> StoredFolder) {
        index = index.copy(folders = index.folders.map { if (it.id == folderId) transform(it) else it })
    }

    /** Removes a document, its stored content and its resources (the caller writes the index). */
    private fun deleteDocumentLocked(documentId: String) {
        val stored = storage.get(documentKey(documentId))?.let { decodeOrNull<StoredDocument>(it) }
        stored?.resources?.forEach { resource ->
            storage.remove(resourceKey(resource.uri))
            try {
                PlatformFiles.delete(resource.uri)
            } catch (_: Throwable) {
            }
        }
        storage.remove(documentKey(documentId))
        openDocuments.remove(documentId)
        dirtyDocuments.remove(documentId)
        index = index.copy(documents = index.documents.filter { it.id != documentId })
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
        val stored = readSnapshot { document.toStored() }
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

    private fun readPreferences(): StoredPreferences =
        storage.get(PREFERENCES_KEY)?.let { decodeOrNull<StoredPreferences>(it) } ?: StoredPreferences()

    private inline fun <reified T> decodeOrNull(value: String): T? = try {
        json.decodeFromString<T>(value)
    } catch (e: Throwable) {
        println("DrawView: unreadable stored value (${e.message})")
        null
    }

    private fun sortedSummaries(index: StoredIndex): List<DocumentSummary> =
        index.documents.sortedByDescending { it.modifiedAt }.map { it.toSummary() }

    private fun folderSummaries(index: StoredIndex): List<FolderSummary> =
        index.folders.sortedBy { it.name }.map { it.toSummary() }

    companion object {
        private const val PREFIX = "drawview."
        private const val INDEX_KEY = PREFIX + "index"
        private const val PREFERENCES_KEY = PREFIX + "preferences"

        private fun documentKey(id: String) = PREFIX + "document." + id
        private fun resourceKey(path: String) = PREFIX + "file." + path
    }
}

// ==========================================================
// Stored model
// ==========================================================

@Serializable
internal data class StoredIndex(
    val documents: List<StoredSummary> = emptyList(),
    val folders: List<StoredFolder> = emptyList()
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
    val defaultBackground: PageBackground = PageBackground.Solid()
) {
    fun toSummary() = DocumentSummary(
        id = id,
        name = name,
        folderId = folderId,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        lastOpenedAt = lastOpenedAt,
        defaultWidthMm = defaultWidth,
        defaultHeightMm = defaultHeight,
        defaultBackground = defaultBackground
    )
}

@Serializable
internal data class StoredDocument(
    val pages: List<StoredPage> = emptyList(),
    val resources: List<StoredResource> = emptyList()
)

@Serializable
internal data class StoredResource(val id: String, val type: String, val uri: String)

@Serializable
internal data class StoredPage(
    val id: String,
    val width: Float,
    val height: Float,
    /** `null` when the page follows the default background of the document. */
    val background: PageBackground? = null,
    val strokes: List<StoredStroke> = emptyList(),
    val texts: List<StoredText> = emptyList(),
    val images: List<StoredImage> = emptyList(),
    val pdfs: List<StoredPdf> = emptyList(),
    val markups: List<StoredTextMarkup> = emptyList()
)

@Serializable
internal data class StoredStroke(
    val id: String,
    val transform: AffineTransform = AffineTransform.IDENTITY,
    val zIndex: Int,
    val color: Int,
    val size: Float,
    val toolType: String,
    val family: String,
    /** Base64 of the [StrokeInputCodec] encoding of the input points. */
    val inputs: String
)

@Serializable
internal data class StoredText(
    val id: String,
    val zIndex: Int,
    val text: String,
    val isLatex: Boolean = false,
    val isMarkdownEnabled: Boolean = true,
    val alignment: Int = 0,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val color: Int,
    val fontSize: Float
)

@Serializable
internal data class StoredImage(
    val id: String,
    val zIndex: Int,
    val resourceId: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f
)

@Serializable
internal data class StoredPdf(val id: String, val zIndex: Int, val resourceId: String, val pdfPageIndex: Int)

@Serializable
internal data class StoredTextMarkup(
    val id: String,
    val zIndex: Int,
    val resourceId: String,
    val pdfPageIndex: Int,
    val pdfWidth: Float,
    val pdfHeight: Float,
    val startIndex: Int,
    val endIndex: Int,
    val text: String,
    /** Left, top, right, bottom of each line, in points of the PDF page. */
    val rects: List<Float>,
    val kind: String,
    val color: Int
)

@Serializable
internal data class StoredPreferences(
    val isStylusOnlyMode: Boolean = false,
    val lastSelectedTool: String = Tool.INK_PEN.name,
    val defaultTextFontSize: Float = 16f,
    val defaultTextColor: Int = 0xFF000000.toInt(),
    val penPresets: List<BrushSettingsData> = emptyList(),
    val highlighterPresets: List<BrushSettingsData> = emptyList(),
    val eraserPresets: List<BrushSettingsData> = emptyList(),
    val lazoPresets: List<BrushSettingsData> = emptyList(),
    val markupPresets: List<BrushSettingsData> = emptyList()
) {
    fun toDrawPreferences(): DrawPreferences = buildDrawPreferences(
        isStylusOnlyMode = isStylusOnlyMode,
        lastSelectedTool = lastSelectedTool,
        defaultTextFontSize = defaultTextFontSize,
        defaultTextColor = defaultTextColor,
        penPresets = penPresets,
        highlighterPresets = highlighterPresets,
        eraserPresets = eraserPresets,
        lazoPresets = lazoPresets,
        markupPresets = markupPresets
    )
}

@OptIn(ExperimentalEncodingApi::class)
private fun DrawDocument.toStored(): StoredDocument = StoredDocument(
    pages = pages.map { page ->
        StoredPage(
            id = page.id,
            width = page.widthMm,
            height = page.heightMm,
            background = if (page.hasCustomBackground) page.background else null,
            strokes = page.strokes.map { stroke ->
                StoredStroke(
                    id = stroke.id,
                    transform = stroke.transform,
                    zIndex = stroke.zIndex,
                    color = stroke.baseBrush.color.toArgb(),
                    size = stroke.baseBrush.size,
                    toolType = stroke.baseMesh.inputToolType.name,
                    family = stroke.baseBrush.tool.name,
                    inputs = Base64.encode(StrokeInputCodec.encode(stroke.baseMesh.points))
                )
            },
            texts = page.texts.map { text ->
                StoredText(
                    id = text.id,
                    zIndex = text.zIndex,
                    text = text.text,
                    isLatex = text.isLatex,
                    isMarkdownEnabled = text.isMarkdownEnabled,
                    alignment = text.alignment,
                    x = text.x,
                    y = text.y,
                    width = text.width,
                    height = text.height,
                    rotation = text.rotation,
                    color = text.color,
                    fontSize = text.fontSize
                )
            },
            images = page.images.map { image ->
                StoredImage(
                    id = image.id,
                    zIndex = image.zIndex,
                    resourceId = image.resourceId,
                    x = image.x,
                    y = image.y,
                    width = image.width,
                    height = image.height,
                    rotation = image.rotation
                )
            },
            pdfs = page.pdfs.map { StoredPdf(it.id, it.zIndex, it.resourceId, it.pdfPageIndex) },
            markups = page.markups.map { markup ->
                StoredTextMarkup(
                    id = markup.id,
                    zIndex = markup.zIndex,
                    resourceId = markup.resourceId,
                    pdfPageIndex = markup.pdfPageIndex,
                    pdfWidth = markup.pdfWidthPt,
                    pdfHeight = markup.pdfHeightPt,
                    startIndex = markup.startIndex,
                    endIndex = markup.endIndex,
                    text = markup.text,
                    rects = markup.toData().rects,
                    kind = markup.kind.name,
                    color = markup.color
                )
            }
        )
    },
    resources = resources.map { StoredResource(it.id, it.type.name, it.path) }
)

@OptIn(ExperimentalEncodingApi::class)
private fun StoredPage.toPage(pageIndex: Int, defaultBackground: PageBackground): DrawPage {
    val page = DrawPage(
        index = pageIndex,
        widthMm = width,
        heightMm = height,
        background = background ?: defaultBackground,
        id = id
    )
    page.hasCustomBackground = background != null
    for (stroke in strokes.sortedBy { it.zIndex }) {
        val points = try {
            StrokeInputCodec.decode(Base64.decode(stroke.inputs))
        } catch (_: Throwable) {
            continue
        }
        page.strokes.add(
            StrokeData(
                id = stroke.id,
                points = points,
                tool = StrokeTool.fromName(stroke.family) ?: StrokeTool.PRESSURE_PEN,
                size = stroke.size,
                color = stroke.color,
                inputToolType = InputToolType.fromName(stroke.toolType),
                transform = stroke.transform,
                zIndex = stroke.zIndex
            ).toElement()
        )
    }
    for (text in texts.sortedBy { it.zIndex }) {
        page.texts.add(
            DrawText(text.zIndex, text.id).apply {
                this.text = text.text
                isLatex = text.isLatex
                isMarkdownEnabled = text.isMarkdownEnabled
                alignment = text.alignment
                x = text.x
                y = text.y
                width = text.width
                height = text.height
                rotation = text.rotation
                color = text.color
                fontSize = text.fontSize
            }
        )
    }
    for (image in images.sortedBy { it.zIndex }) {
        page.images.add(
            DrawImage(image.zIndex, image.id).apply {
                resourceId = image.resourceId
                x = image.x
                y = image.y
                width = image.width
                height = image.height
                rotation = image.rotation
            }
        )
    }
    for (pdf in pdfs.sortedBy { it.zIndex }) {
        page.pdfs.add(DrawPdf(pdf.zIndex, pdf.pdfPageIndex, pdf.id).apply { resourceId = pdf.resourceId })
    }
    for (markup in markups.sortedBy { it.zIndex }) {
        page.markups.add(
            TextMarkupData(
                id = markup.id,
                resourceId = markup.resourceId,
                pdfPageIndex = markup.pdfPageIndex,
                pdfWidthPt = markup.pdfWidth,
                pdfHeightPt = markup.pdfHeight,
                startIndex = markup.startIndex,
                endIndex = markup.endIndex,
                text = markup.text,
                rects = markup.rects,
                kind = TextMarkupKind.fromName(markup.kind) ?: TextMarkupKind.HIGHLIGHT,
                color = markup.color,
                zIndex = markup.zIndex
            ).toElement()
        )
    }
    return page
}
