package io.github.valerioisufi.drawview.data.repository

import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.core.change.StrokeData
import io.github.valerioisufi.drawview.core.change.TextMarkupData
import io.github.valerioisufi.drawview.core.change.toData
import io.github.valerioisufi.drawview.core.controller.DrawPersistence
import io.github.valerioisufi.drawview.core.controller.DrawPreferences
import io.github.valerioisufi.drawview.core.controller.ToolPreset
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
import io.github.valerioisufi.drawview.data.buildDrawPreferences
import io.github.valerioisufi.drawview.data.db.DocumentEntity
import io.github.valerioisufi.drawview.data.db.DrawDatabase
import io.github.valerioisufi.drawview.data.db.ImageEntity
import io.github.valerioisufi.drawview.data.db.PageEntity
import io.github.valerioisufi.drawview.data.db.PdfEntity
import io.github.valerioisufi.drawview.data.db.ResourceEntity
import io.github.valerioisufi.drawview.data.db.StrokeEntity
import io.github.valerioisufi.drawview.data.db.StrokeInputCodec
import io.github.valerioisufi.drawview.data.db.TextEntity
import io.github.valerioisufi.drawview.data.db.TextMarkupEntity
import io.github.valerioisufi.drawview.data.db.UserPreferencesEntity
import io.github.valerioisufi.drawview.data.db.currentTimeMillis
import io.github.valerioisufi.drawview.data.toBrushSettings
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
 * Every row has the identifier of its element in the model. All the content coordinates are
 * stored in page millimeters.
 */
class DrawDocumentRepository(
    val database: DrawDatabase
) : DrawPersistence {

    private val documentDao = database.documentDao()
    private val pageDao = database.pageDao()
    private val strokeDao = database.strokeDao()
    private val textDao = database.textDao()
    private val imageDao = database.imageDao()
    private val pdfDao = database.pdfDao()
    private val textMarkupDao = database.textMarkupDao()
    private val resourceDao = database.resourceDao()
    private val preferencesDao = database.preferencesDao()
    private val json = Json

    /** User preferences mapped to the editor model (defaults when nothing is stored yet). */
    val drawPreferencesFlow: Flow<DrawPreferences>
        get() = preferencesDao.getPreferencesFlow().map { (it ?: UserPreferencesEntity()).toDrawPreferences() }

    // ==========================================================
    // Loading
    // ==========================================================

    suspend fun loadDocument(documentId: String): DrawDocument? {
        val docWithPages = withContext(Dispatchers.IO) {
            pageDao.purgeDeletedPages(documentId)
            documentDao.getFullDocumentWithPages(documentId)
        } ?: return null
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
                addInitialPage = false
            )
            doc.lastOpenedAt = docEntity.lastOpenedAt

            for (resource in resources) {
                val type = DrawResource.ResourceType.entries.firstOrNull { it.name.equals(resource.type, ignoreCase = true) }
                    ?: DrawResource.ResourceType.COLOR
                doc.resources.add(DrawResource(resource.id, type, resource.uri))
            }

            val sortedPages = docWithPages.pages.filter { !it.page.isDeleted }.sortedBy { it.page.pageNumber }
            for ((index, pageWithContent) in sortedPages.withIndex()) {
                val pageEntity = pageWithContent.page
                val page = DrawPage(
                    index = index,
                    widthMm = pageEntity.width,
                    heightMm = pageEntity.height,
                    background = pageEntity.background ?: docEntity.defaultBackground,
                    id = pageEntity.id
                )
                page.hasCustomBackground = pageEntity.background != null

                for (strokeEntity in pageWithContent.strokes.sortedBy { it.zIndex }) {
                    page.strokes.add(mapStroke(strokeEntity))
                }
                for (textEntity in pageWithContent.texts.sortedBy { it.zIndex }) {
                    page.texts.add(mapText(textEntity))
                }
                for (imageEntity in pageWithContent.images.sortedBy { it.zIndex }) {
                    page.images.add(
                        DrawImage(imageEntity.zIndex, imageEntity.id).apply {
                            resourceId = imageEntity.resourceId
                            x = imageEntity.x
                            y = imageEntity.y
                            width = imageEntity.width
                            height = imageEntity.height
                            rotation = imageEntity.rotation
                        }
                    )
                }
                for (pdfEntity in pageWithContent.pdfs.sortedBy { it.zIndex }) {
                    page.pdfs.add(
                        DrawPdf(pdfEntity.zIndex, pdfEntity.pdfPageIndex, pdfEntity.id).apply {
                            resourceId = pdfEntity.resourceId
                        }
                    )
                }
                for (markupEntity in pageWithContent.markups.sortedBy { it.zIndex }) {
                    mapTextMarkup(markupEntity)?.let { page.markups.add(it) }
                }
                doc.pages.add(page)
            }

            if (doc.pages.isEmpty()) {
                // A document must always have a page
                val page = DrawPage(
                    index = 0,
                    widthMm = docEntity.defaultWidth,
                    heightMm = docEntity.defaultHeight,
                    background = docEntity.defaultBackground
                )
                insertPage(doc, page, 0)
                doc.pages.add(page)
            }
            doc
        }
    }

    private fun mapStroke(entity: StrokeEntity): DrawStroke = StrokeData(
        id = entity.id,
        points = StrokeInputCodec.decode(entity.inputs),
        tool = StrokeTool.fromName(entity.brushFamily) ?: StrokeTool.PRESSURE_PEN,
        size = entity.size,
        color = entity.color,
        inputToolType = InputToolType.fromName(entity.toolType),
        transform = entity.transform,
        zIndex = entity.zIndex
    ).toElement()

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
            color = entity.color,
            zIndex = entity.zIndex
        ).toElement()
    }

    private fun mapText(entity: TextEntity) = DrawText(entity.zIndex, entity.id).apply {
        text = entity.text
        isLatex = entity.isLatex
        isMarkdownEnabled = entity.isMarkdownEnabled
        alignment = entity.alignment
        x = entity.x
        y = entity.y
        width = entity.width
        height = entity.height
        rotation = entity.rotation
        color = entity.color
        fontSize = entity.fontSize
    }

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
            modifiedAt = now
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
        insertPage(document, document.pages.single(), 0)
        return document
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
        zIndex = zIndex,
        color = baseBrush.color.toArgb(),
        size = baseBrush.size,
        toolType = baseMesh.inputToolType.name,
        brushFamily = baseBrush.tool.name,
        inputs = StrokeInputCodec.encode(baseMesh.points),
        transform = transform
    )

    override suspend fun saveStrokes(page: DrawPage, strokes: List<DrawStroke>) {
        if (strokes.isEmpty()) return
        withContext(Dispatchers.IO) { strokeDao.upsertAll(strokes.map { it.toEntity(page.id) }) }
    }

    override suspend fun deleteStrokes(strokes: List<DrawStroke>) {
        if (strokes.isEmpty()) return
        withContext(Dispatchers.IO) { strokeDao.deleteByIds(strokes.map { it.id }) }
    }

    override suspend fun saveText(page: DrawPage, text: DrawText) = withContext(Dispatchers.IO) {
        textDao.upsert(
            TextEntity(
                id = text.id,
                pageId = page.id,
                zIndex = text.zIndex,
                text = text.text,
                isLatex = text.isLatex,
                x = text.x,
                y = text.y,
                width = text.width,
                height = text.height,
                rotation = text.rotation,
                color = text.color,
                fontSize = text.fontSize,
                alignment = text.alignment,
                isMarkdownEnabled = text.isMarkdownEnabled
            )
        )
    }

    override suspend fun deleteText(text: DrawText) = withContext(Dispatchers.IO) { textDao.deleteById(text.id) }

    override suspend fun saveImage(page: DrawPage, image: DrawImage) = withContext(Dispatchers.IO) {
        imageDao.upsert(
            ImageEntity(
                id = image.id,
                pageId = page.id,
                zIndex = image.zIndex,
                resourceId = image.resourceId,
                x = image.x,
                y = image.y,
                width = image.width,
                height = image.height,
                rotation = image.rotation
            )
        )
    }

    override suspend fun deleteImage(image: DrawImage) = withContext(Dispatchers.IO) { imageDao.deleteById(image.id) }

    override suspend fun savePdf(page: DrawPage, pdf: DrawPdf) = withContext(Dispatchers.IO) {
        pdfDao.upsert(
            PdfEntity(
                id = pdf.id,
                pageId = page.id,
                zIndex = pdf.zIndex,
                resourceId = pdf.resourceId,
                pdfPageIndex = pdf.pdfPageIndex
            )
        )
    }

    override suspend fun deletePdf(pdf: DrawPdf) = withContext(Dispatchers.IO) { pdfDao.deleteById(pdf.id) }

    override suspend fun saveTextMarkup(page: DrawPage, markup: DrawTextMarkup) = withContext(Dispatchers.IO) {
        textMarkupDao.upsert(
            TextMarkupEntity(
                id = markup.id,
                pageId = page.id,
                zIndex = markup.zIndex,
                resourceId = markup.resourceId,
                pdfPageIndex = markup.pdfPageIndex,
                pdfWidth = markup.pdfWidthPt,
                pdfHeight = markup.pdfHeightPt,
                startIndex = markup.startIndex,
                endIndex = markup.endIndex,
                text = markup.text,
                rects = json.encodeToString(markup.toData().rects),
                kind = markup.kind.name,
                color = markup.color
            )
        )
    }

    override suspend fun deleteTextMarkup(markup: DrawTextMarkup) = withContext(Dispatchers.IO) {
        textMarkupDao.deleteById(markup.id)
    }

    override suspend fun saveResource(document: DrawDocument, resource: DrawResource) = withContext(Dispatchers.IO) {
        resourceDao.upsert(ResourceEntity(id = resource.id, documentId = document.id, type = resource.type.name, uri = resource.path))
    }

    override suspend fun insertPage(document: DrawDocument, page: DrawPage, index: Int) = withContext(Dispatchers.IO) {
        pageDao.shiftPagesUp(document.id, index)
        pageDao.upsert(
            PageEntity(
                id = page.id,
                documentId = document.id,
                pageNumber = index,
                width = page.widthMm,
                height = page.heightMm,
                background = if (page.hasCustomBackground) page.background else null
            )
        )
    }

    override suspend fun softDeletePage(document: DrawDocument, page: DrawPage, index: Int) = withContext(Dispatchers.IO) {
        pageDao.softDeletePage(page.id)
        pageDao.shiftPagesDown(document.id, index)
    }

    override suspend fun updatePagesOrder(document: DrawDocument, pages: List<DrawPage>) = withContext(Dispatchers.IO) {
        for ((index, page) in pages.withIndex()) pageDao.updatePageNumber(page.id, index)
    }

    override suspend fun updatePageFormat(document: DrawDocument, page: DrawPage) = withContext(Dispatchers.IO) {
        pageDao.updateFormat(page.id, page.widthMm, page.heightMm, if (page.hasCustomBackground) page.background else null)
    }

    override suspend fun updateDocumentDefaults(document: DrawDocument) = withContext(Dispatchers.IO) {
        documentDao.updateDocumentDefaults(document.id, document.defaultBackground, document.defaultWidthMm, document.defaultHeightMm)
    }

    override suspend fun touchDocument(document: DrawDocument, timestamp: Long) = withContext(Dispatchers.IO) {
        documentDao.touchDocument(document.id, timestamp)
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

    override suspend fun saveTextDefaults(fontSize: Float, color: Int) = updatePreferences {
        it.copy(defaultTextFontSize = fontSize, defaultTextColor = color)
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
    penPresets = penPresets,
    highlighterPresets = highlighterPresets,
    eraserPresets = eraserPresets,
    lazoPresets = lazoPresets,
    markupPresets = markupPresets
)
