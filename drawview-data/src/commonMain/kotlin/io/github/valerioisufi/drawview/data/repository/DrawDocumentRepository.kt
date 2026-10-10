package io.github.valerioisufi.drawview.data.repository

import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.core.change.DocumentChange
import io.github.valerioisufi.drawview.core.change.Operation
import io.github.valerioisufi.drawview.core.change.OperationId
import io.github.valerioisufi.drawview.core.change.StrokeData
import io.github.valerioisufi.drawview.core.change.TextMarkupData
import io.github.valerioisufi.drawview.core.change.toData
import io.github.valerioisufi.drawview.core.controller.DrawPersistence
import io.github.valerioisufi.drawview.core.controller.DrawPreferences
import io.github.valerioisufi.drawview.core.controller.ToolPreset
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawElement
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawPdf
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup
import io.github.valerioisufi.drawview.core.model.PurgedContent
import io.github.valerioisufi.drawview.core.model.TextMarkupKind
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.core.model.newElementId
import io.github.valerioisufi.drawview.data.buildDrawPreferences
import io.github.valerioisufi.drawview.data.db.DocumentEntity
import io.github.valerioisufi.drawview.data.db.DrawDatabase
import io.github.valerioisufi.drawview.data.db.ImageEntity
import io.github.valerioisufi.drawview.data.db.OperationBaseEntity
import io.github.valerioisufi.drawview.data.db.OperationEntity
import io.github.valerioisufi.drawview.data.db.PageEntity
import io.github.valerioisufi.drawview.data.db.PdfEntity
import io.github.valerioisufi.drawview.data.db.ReplicaEntity
import io.github.valerioisufi.drawview.data.db.ResourceEntity
import io.github.valerioisufi.drawview.data.db.StrokeEntity
import io.github.valerioisufi.drawview.data.db.StrokeInputCodec
import io.github.valerioisufi.drawview.data.db.TextEntity
import io.github.valerioisufi.drawview.data.db.TextMarkupEntity
import io.github.valerioisufi.drawview.data.db.UserPreferencesEntity
import io.github.valerioisufi.drawview.data.db.currentTimeMillis
import io.github.valerioisufi.drawview.data.toBrushSettings
import io.github.valerioisufi.drawview.ink.InkStrokeModeller
import io.github.valerioisufi.drawview.model.ElementOrder
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.StrokeModeller
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.text.TextDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Single source of truth of the documents: maps the Room entities to the in-memory model of
 * `:drawview-core` and implements the [DrawPersistence] port used by the editor.
 *
 * Every row has the identifier of its element in the model, with its order and the stamps of its
 * last changes. All the content coordinates are stored in page millimeters.
 */
class DrawDocumentRepository(
    val database: DrawDatabase,
    /** Models the strokes loaded from their inputs. */
    private val strokeModeller: StrokeModeller = InkStrokeModeller
) : DrawPersistence {

    private val documentDao = database.documentDao()
    private val pageDao = database.pageDao()
    private val strokeDao = database.strokeDao()
    private val textDao = database.textDao()
    private val imageDao = database.imageDao()
    private val pdfDao = database.pdfDao()
    private val textMarkupDao = database.textMarkupDao()
    private val resourceDao = database.resourceDao()
    private val operationDao = database.operationDao()
    private val purgeDao = database.purgeDao()
    private val replicaDao = database.replicaDao()
    private val preferencesDao = database.preferencesDao()
    private val json = Json

    /** User preferences mapped to the editor model (defaults when nothing is stored yet). */
    val drawPreferencesFlow: Flow<DrawPreferences>
        get() = preferencesDao.getPreferencesFlow().map { (it ?: UserPreferencesEntity()).toDrawPreferences() }

    // ==========================================================
    // Loading
    // ==========================================================

    suspend fun loadDocument(documentId: String): DrawDocument? {
        val docWithPages = withContext(Dispatchers.IO) { documentDao.getFullDocumentWithPages(documentId) } ?: return null
        val resources = withContext(Dispatchers.IO) { resourceDao.getResourcesForDocument(documentId) }

        // Re-modeling the strokes is CPU bound
        return withContext(Dispatchers.Default) {
            val docEntity = docWithPages.document
            val doc = DrawDocument(
                id = docEntity.id,
                title = docEntity.name,
                defaultWidthMm = docEntity.defaultWidth,
                defaultHeightMm = docEntity.defaultHeight,
                defaultBackground = docEntity.defaultBackground,
                createdAt = docEntity.createdAt,
                modifiedAt = docEntity.modifiedAt,
                addInitialPage = false,
                strokeModeller = strokeModeller
            )
            doc.lastOpenedAt = docEntity.lastOpenedAt
            doc.defaultsVersion = docEntity.defaultsVersion
            doc.titleVersion = docEntity.titleVersion

            for (resource in resources) {
                val type = DrawResource.ResourceType.entries.firstOrNull { it.name.equals(resource.type, ignoreCase = true) }
                    ?: DrawResource.ResourceType.COLOR
                doc.resources.add(DrawResource(resource.id, type, resource.uri, resource.contentHash))
            }

            val sortedPages = docWithPages.pages.sortedWith(compareBy({ it.page.orderKey }, { it.page.id }))
            for (pageWithContent in sortedPages) {
                val pageEntity = pageWithContent.page
                val page = DrawPage(
                    widthMm = pageEntity.width,
                    heightMm = pageEntity.height,
                    background = pageEntity.background ?: docEntity.defaultBackground,
                    id = pageEntity.id,
                    orderKey = pageEntity.orderKey
                )
                page.hasCustomBackground = pageEntity.background != null
                page.added = pageEntity.added
                page.removed = pageEntity.removed
                page.orderVersion = pageEntity.orderVersion
                page.formatVersion = pageEntity.formatVersion
                page.contentVersions = pageEntity.contentVersions
                page.removalSaw = pageEntity.removalSaw

                val elements = pageWithContent.strokes.map { e -> mapStroke(e).stored(e.orderKey, e.added, e.removed, e.placed, e.updated) } +
                    pageWithContent.texts.map { e -> mapText(e).stored(e.orderKey, e.added, e.removed, e.placed, e.updated) } +
                    pageWithContent.images.map { e -> mapImage(e).stored(e.orderKey, e.added, e.removed, e.placed, e.updated) } +
                    pageWithContent.pdfs.map { e -> mapPdf(e).stored(e.orderKey, e.added, e.removed, e.placed, e.updated) } +
                    pageWithContent.markups.mapNotNull { e -> mapTextMarkup(e)?.stored(e.orderKey, e.added, e.removed, e.placed, e.updated) }
                // The removed elements are kept, hidden
                for (element in elements.sortedBy { it.order }) {
                    if (element.removed != null) page.removedElements.add(element) else page.add(element)
                }
                if (page.isHidden) doc.removedPages[page.id] = page else doc.pages.add(page)
            }
            doc.reindexPages()
            doc
        }
    }

    private fun <T : DrawElement> T.stored(order: ElementOrder, added: Stamp, removed: Stamp?, placed: Stamp, updated: Stamp): T =
        apply {
            this.order = order
            this.added = added
            this.removed = removed
            this.placed = placed
            this.updated = updated
        }

    private fun mapStroke(entity: StrokeEntity): DrawStroke = StrokeData(
        id = entity.id,
        points = StrokeInputCodec.decode(entity.inputs),
        tool = StrokeTool.fromName(entity.brushFamily) ?: StrokeTool.PRESSURE_PEN,
        size = entity.size,
        color = entity.color,
        inputToolType = InputToolType.fromName(entity.toolType),
        transform = entity.transform
    ).toElement(strokeModeller)

    private fun mapTextMarkup(entity: TextMarkupEntity): DrawTextMarkup? {
        val rects = try {
            json.decodeFromString<List<Float>>(entity.rects)
        } catch (_: Exception) {
            return null
        }
        return TextMarkupData(
            id = entity.id,
            resourceId = entity.resourceId,
            pdfPageIndex = entity.pdfPageIndex,
            pdfWidthPt = entity.pdfWidth,
            pdfHeightPt = entity.pdfHeight,
            startIndex = entity.startIndex,
            endIndex = entity.endIndex,
            text = entity.text,
            rects = rects,
            kind = TextMarkupKind.fromName(entity.kind) ?: TextMarkupKind.HIGHLIGHT,
            color = entity.color
        ).toElement()
    }

    private fun mapText(entity: TextEntity) = DrawText(entity.id).apply {
        text = entity.text
        alignment = TextAlignment.fromName(entity.alignment) ?: TextAlignment.START
        autoWidth = entity.autoWidth
        x = entity.x
        y = entity.y
        width = entity.width
        height = entity.height
        rotation = entity.rotation
        color = entity.color
        fontSize = entity.fontSize
        contentVersions = entity.contentVersions
    }

    private fun mapImage(entity: ImageEntity) = DrawImage(entity.id).apply {
        resourceId = entity.resourceId
        x = entity.x
        y = entity.y
        width = entity.width
        height = entity.height
        rotation = entity.rotation
    }

    private fun mapPdf(entity: PdfEntity) = DrawPdf(entity.pdfPageIndex, entity.id).apply { resourceId = entity.resourceId }

    // ==========================================================
    // Documents
    // ==========================================================

    /**
     * Creates a document with one page of the given format in [folderId].
     */
    suspend fun createDocument(
        name: String,
        widthMm: Float,
        heightMm: Float,
        background: PageBackground,
        folderId: String? = null
    ): DrawDocument {
        val now = currentTimeMillis()
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
        withContext(Dispatchers.IO) {
            documentDao.insert(
                DocumentEntity(
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
            )
        }
        savePage(document, document.pages.single())
        return document
    }

    /**
     * Stores [document] with its whole content (removed pages and elements included) in
     * [folderId] (see [io.github.valerioisufi.drawview.data.DocumentStore.insertDocument]).
     */
    suspend fun insertDocument(document: DrawDocument, folderId: String?, versionVector: Map<String, Long> = emptyMap()) {
        val now = currentTimeMillis()
        withContext(Dispatchers.IO) {
            documentDao.insert(
                DocumentEntity(
                    id = document.id,
                    name = document.title,
                    folderId = folderId,
                    createdAt = now,
                    modifiedAt = now,
                    lastOpenedAt = now,
                    defaultWidth = document.defaultWidthMm,
                    defaultHeight = document.defaultHeightMm,
                    defaultBackground = document.defaultBackground,
                    defaultsVersion = document.defaultsVersion,
                    titleVersion = document.titleVersion
                )
            )
        }
        // The operations it was received with (their changes are in it)
        withContext(Dispatchers.IO) {
            for ((replica, sequence) in versionVector) operationDao.upsertBase(OperationBaseEntity(document.id, replica, sequence))
        }
        document.resources.forEach { saveResource(document, it) }
        for (page in document.allPages()) {
            savePage(document, page)
            val elements = page.elements() + page.removedElements
            saveStrokes(page, elements.filterIsInstance<DrawStroke>())
            for (element in elements) {
                when (element) {
                    is DrawStroke -> {}
                    is DrawText -> saveText(page, element)
                    is DrawImage -> saveImage(page, element)
                    is DrawPdf -> savePdf(page, element)
                    is DrawTextMarkup -> saveTextMarkup(page, element)
                }
            }
        }
    }

    // ==========================================================
    // Replication
    // ==========================================================

    /** This device in the changes of the documents (created the first time). */
    suspend fun replicaId(): String = withContext(Dispatchers.IO) {
        replicaDao.get() ?: run {
            replicaDao.insert(ReplicaEntity(replicaId = newElementId()))
            checkNotNull(replicaDao.get())
        }
    }

    /** The last operation of every replica in the log of [documentId]. */
    suspend fun versionVector(documentId: String): Map<String, Long> = withContext(Dispatchers.IO) {
        // With the operations no longer in the log
        val vector = operationDao.bases(documentId).associate { it.replica to it.sequence }.toMutableMap()
        for (last in operationDao.lastSequences(documentId)) vector[last.replica] = maxOf(vector[last.replica] ?: 0L, last.sequence)
        vector
    }

    suspend fun compactedOperations(documentId: String): Map<String, Long> = withContext(Dispatchers.IO) {
        operationDao.bases(documentId).associate { it.replica to it.sequence }
    }

    /**
     * Removes from the log of [documentId] its operations up to [upTo] (a version vector),
     * remembering them: the stamps of the last ones, by replica.
     */
    suspend fun compactOperations(documentId: String, upTo: Map<String, Long>): Map<String, Stamp> = withContext(Dispatchers.IO) {
        val bases = operationDao.baseEntities(documentId).associateBy { it.replica }
        for ((replica, sequence) in upTo) {
            if (sequence <= (bases[replica]?.sequence ?: 0L)) continue
            val stamp = operationDao.stampOf(documentId, replica, sequence) ?: continue
            operationDao.deleteUpTo(documentId, replica, sequence)
            operationDao.upsertBase(OperationBaseEntity(documentId, replica, sequence, stamp))
        }
        operationDao.baseEntities(documentId).associate { it.replica to it.stamp }
    }

    override suspend fun deleteForGood(document: DrawDocument, content: PurgedContent) = withContext(Dispatchers.IO) {
        purgeDao.deletePages(content.pages.map { it.id })
        val elements = content.elements.map { it.second }
        purgeDao.deleteStrokes(elements.filterIsInstance<DrawStroke>().map { it.id })
        purgeDao.deleteTexts(elements.filterIsInstance<DrawText>().map { it.id })
        purgeDao.deleteImages(elements.filterIsInstance<DrawImage>().map { it.id })
        purgeDao.deletePdfs(elements.filterIsInstance<DrawPdf>().map { it.id })
        purgeDao.deleteTextMarkups(elements.filterIsInstance<DrawTextMarkup>().map { it.id })
    }

    /** The operations of [documentId] after [known] (a version vector), in stamp order. */
    suspend fun operationsAfter(documentId: String, known: Map<String, Long>): List<Operation> = withContext(Dispatchers.IO) {
        // Only the ones missing read (the whole log of a document worked on for long is large)
        operationDao.lastSequences(documentId)
            .filter { it.sequence > (known[it.replica] ?: 0L) }
            .flatMap { operationDao.getAfter(documentId, it.replica, known[it.replica] ?: 0L) }
            .sortedBy { it.stamp }
            .map { Operation(OperationId(it.replica, it.sequence), it.stamp, json.decodeFromString(DocumentChange.serializer(), it.change)) }
    }

    // ==========================================================
    // Preferences
    // ==========================================================

    private suspend fun updatePreferences(transform: (UserPreferencesEntity) -> UserPreferencesEntity) = withContext(Dispatchers.IO) {
        val current = preferencesDao.getPreferences() ?: UserPreferencesEntity()
        preferencesDao.insertOrUpdate(transform(current))
    }

    // ==========================================================
    // DrawPersistence (editor port)
    // ==========================================================

    // The stroke as it was drawn, and its placement
    private fun DrawStroke.toEntity(pageId: String) = StrokeEntity(
        id = id,
        pageId = pageId,
        orderKey = order,
        color = baseBrush.color,
        size = baseBrush.size,
        toolType = baseMesh.inputToolType.name,
        brushFamily = baseBrush.tool.name,
        inputs = StrokeInputCodec.encode(baseMesh.points),
        transform = transform,
        added = added,
        removed = removed,
        placed = placed,
        updated = updated
    )

    override suspend fun saveStrokes(page: DrawPage, strokes: List<DrawStroke>) {
        if (strokes.isEmpty()) return
        withContext(Dispatchers.IO) { strokeDao.upsertAll(strokes.map { it.toEntity(page.id) }) }
    }

    override suspend fun saveText(page: DrawPage, text: DrawText) = withContext(Dispatchers.IO) {
        textDao.upsert(
            TextEntity(
                id = text.id,
                pageId = page.id,
                orderKey = text.order,
                text = text.text,
                x = text.x,
                y = text.y,
                width = text.width,
                height = text.height,
                rotation = text.rotation,
                color = text.color,
                fontSize = text.fontSize,
                alignment = text.alignment.name,
                autoWidth = text.autoWidth,
                added = text.added,
                removed = text.removed,
                placed = text.placed,
                updated = text.updated,
                contentVersions = text.contentVersions
            )
        )
    }

    override suspend fun saveImage(page: DrawPage, image: DrawImage) = withContext(Dispatchers.IO) {
        imageDao.upsert(
            ImageEntity(
                id = image.id,
                pageId = page.id,
                orderKey = image.order,
                resourceId = image.resourceId,
                x = image.x,
                y = image.y,
                width = image.width,
                height = image.height,
                rotation = image.rotation,
                added = image.added,
                removed = image.removed,
                placed = image.placed,
                updated = image.updated
            )
        )
    }

    override suspend fun savePdf(page: DrawPage, pdf: DrawPdf) = withContext(Dispatchers.IO) {
        pdfDao.upsert(
            PdfEntity(
                id = pdf.id,
                pageId = page.id,
                orderKey = pdf.order,
                resourceId = pdf.resourceId,
                pdfPageIndex = pdf.pdfPageIndex,
                added = pdf.added,
                removed = pdf.removed,
                placed = pdf.placed,
                updated = pdf.updated
            )
        )
    }

    override suspend fun saveTextMarkup(page: DrawPage, markup: DrawTextMarkup) = withContext(Dispatchers.IO) {
        textMarkupDao.upsert(
            TextMarkupEntity(
                id = markup.id,
                pageId = page.id,
                orderKey = markup.order,
                resourceId = markup.resourceId,
                pdfPageIndex = markup.pdfPageIndex,
                pdfWidth = markup.pdfWidthPt,
                pdfHeight = markup.pdfHeightPt,
                startIndex = markup.startIndex,
                endIndex = markup.endIndex,
                text = markup.text,
                rects = json.encodeToString(markup.toData().rects),
                kind = markup.kind.name,
                color = markup.color,
                added = markup.added,
                removed = markup.removed,
                placed = markup.placed,
                updated = markup.updated
            )
        )
    }

    override suspend fun saveResource(document: DrawDocument, resource: DrawResource) = withContext(Dispatchers.IO) {
        resourceDao.upsert(ResourceEntity(id = resource.id, documentId = document.id, type = resource.type.name, uri = resource.path, contentHash = resource.hash))
    }

    override suspend fun fileWithContent(hash: String): String? =
        if (hash.isEmpty()) null else withContext(Dispatchers.IO) { resourceDao.findUriByHash(hash) }

    override suspend fun savePage(document: DrawDocument, page: DrawPage) = withContext(Dispatchers.IO) {
        pageDao.upsert(
            PageEntity(
                id = page.id,
                documentId = document.id,
                orderKey = page.orderKey,
                width = page.widthMm,
                height = page.heightMm,
                background = if (page.hasCustomBackground) page.background else null,
                added = page.added,
                removed = page.removed,
                orderVersion = page.orderVersion,
                formatVersion = page.formatVersion,
                contentVersions = page.contentVersions,
                removalSaw = page.removalSaw
            )
        )
    }

    override suspend fun updateDocumentDefaults(document: DrawDocument) = withContext(Dispatchers.IO) {
        documentDao.updateDocumentDefaults(
            document.id, document.defaultBackground, document.defaultWidthMm, document.defaultHeightMm, document.defaultsVersion
        )
    }

    override suspend fun updateDocumentTitle(document: DrawDocument) = withContext(Dispatchers.IO) {
        documentDao.updateDocumentTitle(document.id, document.title, document.titleVersion)
    }

    override suspend fun touchDocument(document: DrawDocument, timestamp: Long) = withContext(Dispatchers.IO) {
        documentDao.touchDocument(document.id, timestamp)
    }

    override suspend fun appendOperation(document: DrawDocument, operation: Operation) = withContext(Dispatchers.IO) {
        operationDao.insert(
            OperationEntity(
                documentId = document.id,
                replica = operation.id.replica,
                sequence = operation.id.sequence,
                stamp = operation.stamp,
                change = json.encodeToString(DocumentChange.serializer(), operation.change)
            )
        )
    }

    override suspend fun savePresets(tool: Tool, presets: List<ToolPreset>) {
        val data = presets.toBrushSettings(tool)
        when (tool) {
            Tool.INK_PEN -> updatePreferences { it.copy(penPresets = data) }
            Tool.INK_HIGHLIGHTER -> updatePreferences { it.copy(highlighterPresets = data) }
            Tool.TEXT_MARKUP -> updatePreferences { it.copy(markupPresets = data) }
            Tool.ERASER -> updatePreferences { it.copy(eraserPresets = data) }
            Tool.LAZO -> updatePreferences { it.copy(lazoPresets = data) }
            else -> {}
        }
    }

    override suspend fun saveStylusOnlyMode(enabled: Boolean) = updatePreferences { it.copy(isStylusOnlyMode = enabled) }

    override suspend fun saveLastSelectedTool(tool: Tool) = updatePreferences { it.copy(lastSelectedTool = tool.name) }

    override suspend fun saveSnapEnabled(enabled: Boolean) = updatePreferences { it.copy(isSnapEnabled = enabled) }

    override suspend fun saveTextDefaults(defaults: TextDefaults) = updatePreferences {
        it.copy(defaultTextFontSize = defaults.fontSize, defaultTextColor = defaults.color, defaultTextAlignment = defaults.alignment.name)
    }
}

/**
 * Maps the stored preferences to the editor model.
 */
fun UserPreferencesEntity.toDrawPreferences(): DrawPreferences = buildDrawPreferences(
    isStylusOnlyMode = isStylusOnlyMode,
    lastSelectedTool = lastSelectedTool,
    defaultTextFontSize = defaultTextFontSize,
    defaultTextColor = defaultTextColor,
    defaultTextAlignment = defaultTextAlignment,
    isSnapEnabled = isSnapEnabled,
    penPresets = penPresets,
    highlighterPresets = highlighterPresets,
    eraserPresets = eraserPresets,
    lazoPresets = lazoPresets,
    markupPresets = markupPresets
)
