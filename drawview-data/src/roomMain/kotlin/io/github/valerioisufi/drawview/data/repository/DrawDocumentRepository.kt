package io.github.valerioisufi.drawview.data.repository

import androidx.compose.ui.graphics.toArgb
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
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.data.buildDrawPreferences
import io.github.valerioisufi.drawview.data.db.BrushSettingsData
import io.github.valerioisufi.drawview.data.db.DocumentEntity
import io.github.valerioisufi.drawview.data.db.DrawDatabase
import io.github.valerioisufi.drawview.data.db.ImageEntity
import io.github.valerioisufi.drawview.data.db.PageEntity
import io.github.valerioisufi.drawview.data.db.PdfEntity
import io.github.valerioisufi.drawview.data.db.ResourceEntity
import io.github.valerioisufi.drawview.data.db.StrokeEntity
import io.github.valerioisufi.drawview.data.db.StrokeInputCodec
import io.github.valerioisufi.drawview.data.db.TextEntity
import io.github.valerioisufi.drawview.data.db.UserPreferencesEntity
import io.github.valerioisufi.drawview.data.db.currentTimeMillis
import io.github.valerioisufi.drawview.data.restoreStroke
import io.github.valerioisufi.drawview.data.toBrushSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Single source of truth of the documents: maps the Room entities to the in-memory model of
 * `:drawview-core` and implements the [DrawPersistence] port used by the editor.
 *
 * All the content coordinates are stored in page millimeters.
 */
class DrawDocumentRepository(
    val database: DrawDatabase
) : IDrawDocumentRepository, DrawPersistence {

    private val documentDao = database.documentDao()
    private val pageDao = database.pageDao()
    private val strokeDao = database.strokeDao()
    private val textDao = database.textDao()
    private val imageDao = database.imageDao()
    private val pdfDao = database.pdfDao()
    private val resourceDao = database.resourceDao()
    private val preferencesDao = database.preferencesDao()

    override val userPreferencesFlow: Flow<UserPreferencesEntity?>
        get() = preferencesDao.getPreferencesFlow()

    /** User preferences mapped to the editor model (defaults when nothing is stored yet). */
    val drawPreferencesFlow: Flow<DrawPreferences>
        get() = preferencesDao.getPreferencesFlow().map { (it ?: UserPreferencesEntity()).toDrawPreferences() }

    // ==========================================================
    // Loading
    // ==========================================================

    override suspend fun loadDocument(documentId: Int): DrawDocument? {
        val docWithPages = withContext(Dispatchers.IO) {
            pageDao.purgeDeletedPages(documentId)
            documentDao.getFullDocumentWithPages(documentId)
        } ?: return null
        val resources = withContext(Dispatchers.IO) { resourceDao.getResourcesForDocument(documentId) }

        // Re-modeling the strokes is CPU bound
        return withContext(Dispatchers.Default) {
            val docEntity = docWithPages.document
            val doc = DrawDocument(
                id = docEntity.id.toString(),
                title = docEntity.name,
                defaultWidthMm = docEntity.defaultWidth,
                defaultHeightMm = docEntity.defaultHeight,
                defaultBackground = docEntity.defaultBackground,
                createdAt = docEntity.createdAt,
                modifiedAt = docEntity.modifiedAt,
                dbId = docEntity.id,
                addInitialPage = false
            )
            doc.lastOpenedAt = docEntity.lastOpenedAt

            for (resource in resources) {
                val type = DrawResource.ResourceType.entries.firstOrNull { it.name.equals(resource.type, ignoreCase = true) }
                    ?: DrawResource.ResourceType.COLOR
                doc.resources.add(DrawResource(resource.id.toString(), type, resource.uri))
            }

            val sortedPages = docWithPages.pages.filter { !it.page.isDeleted }.sortedBy { it.page.pageNumber }
            for ((index, pageWithContent) in sortedPages.withIndex()) {
                val pageEntity = pageWithContent.page
                val page = DrawPage(
                    index = index,
                    widthMm = pageEntity.width,
                    heightMm = pageEntity.height,
                    background = pageEntity.background ?: docEntity.defaultBackground,
                    dbId = pageEntity.id
                )
                page.hasCustomBackground = pageEntity.background != null

                for (strokeEntity in pageWithContent.strokes.sortedBy { it.zIndex }) {
                    mapStroke(strokeEntity)?.let { page.strokes.add(it) }
                }
                for (textEntity in pageWithContent.texts.sortedBy { it.zIndex }) {
                    page.texts.add(mapText(textEntity))
                }
                for (imageEntity in pageWithContent.images.sortedBy { it.zIndex }) {
                    page.images.add(
                        DrawImage(imageEntity.zIndex).apply {
                            dbId = imageEntity.id
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
                        DrawPdf(pdfEntity.zIndex, pdfEntity.pdfPageIndex).apply {
                            dbId = pdfEntity.id
                            resourceId = pdfEntity.resourceId
                        }
                    )
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
                withContext(Dispatchers.IO) { insertPage(doc, page) }
                doc.pages.add(page)
            }
            doc
        }
    }

    private fun mapStroke(entity: StrokeEntity): DrawStroke? = restoreStroke(
        points = StrokeInputCodec.decode(entity.inputs),
        familyName = entity.brushFamily,
        toolTypeName = entity.toolType,
        size = entity.size,
        color = entity.color,
        zIndex = entity.zIndex,
        dbId = entity.id,
        id = entity.id.toString()
    )

    private fun mapText(entity: TextEntity) = DrawText(entity.zIndex).apply {
        dbId = entity.id
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

    override suspend fun createNewDefaultDocument(defaultDocumentName: String): DrawDocument =
        createDocument(defaultDocumentName, 210f, 297f, PageBackground.Solid())

    /**
     * Creates a document with one page of the given format.
     */
    suspend fun createDocument(
        name: String,
        widthMm: Float,
        heightMm: Float,
        background: PageBackground,
        folderId: Int? = null
    ): DrawDocument = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        val docEntity = DocumentEntity(
            name = name,
            folderId = folderId,
            createdAt = now,
            modifiedAt = now,
            lastOpenedAt = now,
            defaultWidth = widthMm,
            defaultHeight = heightMm,
            defaultBackground = background
        )
        val docId = documentDao.insert(docEntity).toInt()

        val pageId = pageDao.insert(
            PageEntity(
                documentId = docId,
                pageNumber = 0,
                width = widthMm,
                height = heightMm,
                background = null
            )
        ).toInt()

        DrawDocument(
            id = docId.toString(),
            title = name,
            defaultWidthMm = widthMm,
            defaultHeightMm = heightMm,
            defaultBackground = background,
            createdAt = now,
            modifiedAt = now,
            dbId = docId,
            addInitialPage = false
        ).apply {
            pages.add(DrawPage(index = 0, widthMm = widthMm, heightMm = heightMm, background = background, dbId = pageId))
        }
    }

    override suspend fun touchDocument(documentId: Int, timestamp: Long) = withContext(Dispatchers.IO) {
        documentDao.touchDocument(documentId, timestamp)
    }

    override suspend fun updateLastOpened(documentId: Int, timestamp: Long) = withContext(Dispatchers.IO) {
        documentDao.updateLastOpened(documentId, timestamp)
    }

    // ==========================================================
    // Pages
    // ==========================================================

    override suspend fun insertPageAt(documentId: Int, page: DrawPage): Int = withContext(Dispatchers.IO) {
        pageDao.shiftPagesUp(documentId, page.index)
        val id = pageDao.insert(
            PageEntity(
                documentId = documentId,
                pageNumber = page.index,
                width = page.widthMm,
                height = page.heightMm,
                background = if (page.hasCustomBackground) page.background else null
            )
        ).toInt()
        page.dbId = id
        id
    }

    override suspend fun deletePageAtIndex(documentId: Int, pageDbId: Int, deletedIndex: Int) = withContext(Dispatchers.IO) {
        pageDao.deleteById(pageDbId)
        pageDao.shiftPagesDown(documentId, deletedIndex)
    }

    override suspend fun updatePagesOrder(documentId: Int, pages: List<DrawPage>) = withContext(Dispatchers.IO) {
        for ((idx, page) in pages.withIndex()) {
            if (page.dbId != 0) pageDao.updatePageNumber(page.dbId, idx)
        }
    }

    override suspend fun softDeletePageAtIndex(documentId: Int, pageDbId: Int, deletedIndex: Int) = withContext(Dispatchers.IO) {
        pageDao.softDeletePage(pageDbId)
        pageDao.shiftPagesDown(documentId, deletedIndex)
    }

    override suspend fun restorePageAtIndex(documentId: Int, pageDbId: Int, restoreIndex: Int) = withContext(Dispatchers.IO) {
        pageDao.shiftPagesUp(documentId, restoreIndex)
        pageDao.restorePage(pageDbId, restoreIndex)
    }

    override suspend fun updatePageFormatAndBackground(documentId: Int, page: DrawPage) = withContext(Dispatchers.IO) {
        val entity = if (page.dbId != 0) pageDao.getPageById(page.dbId)
        else pageDao.getPagesForDocument(documentId).find { it.pageNumber == page.index && !it.isDeleted }
        if (entity != null) {
            pageDao.update(
                entity.copy(
                    width = page.widthMm,
                    height = page.heightMm,
                    background = if (page.hasCustomBackground) page.background else null
                )
            )
        }
    }

    override suspend fun updateDocumentDefaults(documentId: Int, background: PageBackground, width: Float, height: Float) = withContext(Dispatchers.IO) {
        documentDao.updateDocumentDefaults(documentId, background, width, height)
    }

    // ==========================================================
    // Strokes
    // ==========================================================

    private fun DrawStroke.toEntity(pageId: Int, id: Int = 0) = StrokeEntity(
        id = id,
        pageId = pageId,
        zIndex = zIndex,
        color = brushConfig.color.toArgb(),
        size = brushConfig.size,
        toolType = mesh.inputToolType.name,
        brushFamily = brushConfig.tool.name,
        inputs = StrokeInputCodec.encode(mesh.points)
    )

    override suspend fun saveNewStroke(pageId: Int, stroke: DrawStroke): Int = withContext(Dispatchers.IO) {
        val id = strokeDao.insert(stroke.toEntity(pageId)).toInt()
        stroke.dbId = id
        id
    }

    override suspend fun updateStroke(pageId: Int, stroke: DrawStroke) = withContext(Dispatchers.IO) {
        val id = stroke.dbId.takeIf { it != 0 } ?: stroke.id.toIntOrNull() ?: return@withContext
        strokeDao.insert(stroke.toEntity(pageId, id))
        Unit
    }

    override suspend fun deleteStroke(strokeId: Int) = withContext(Dispatchers.IO) {
        strokeDao.deleteById(strokeId)
    }

    override suspend fun saveNewStrokes(pageId: Int, strokes: List<DrawStroke>) = withContext(Dispatchers.IO) {
        for (stroke in strokes) {
            stroke.dbId = strokeDao.insert(stroke.toEntity(pageId)).toInt()
        }
    }

    override suspend fun deleteStrokesByIds(strokeIds: List<Int>) = withContext(Dispatchers.IO) {
        if (strokeIds.isEmpty()) return@withContext
        strokeDao.deleteByIds(strokeIds)
    }

    // ==========================================================
    // Preferences
    // ==========================================================

    private suspend fun updatePreferences(transform: (UserPreferencesEntity) -> UserPreferencesEntity) = withContext(Dispatchers.IO) {
        val current = preferencesDao.getPreferences() ?: UserPreferencesEntity()
        preferencesDao.insertOrUpdate(transform(current))
    }

    override suspend fun updateStylusMode(isStylusOnly: Boolean) = updatePreferences { it.copy(isStylusOnlyMode = isStylusOnly) }

    override suspend fun updatePenPresets(presets: List<BrushSettingsData>) = updatePreferences { it.copy(penPresets = presets) }

    override suspend fun updateHighlighterPresets(presets: List<BrushSettingsData>) = updatePreferences { it.copy(highlighterPresets = presets) }

    override suspend fun updateEraserPresets(presets: List<BrushSettingsData>) = updatePreferences { it.copy(eraserPresets = presets) }

    override suspend fun updateLazoPresets(presets: List<BrushSettingsData>) = updatePreferences { it.copy(lazoPresets = presets) }

    override suspend fun updateTextDefaults(fontSize: Float, color: Int, isLatex: Boolean) = updatePreferences {
        it.copy(defaultTextFontSize = fontSize, defaultTextColor = color, defaultTextIsLatex = isLatex)
    }

    override suspend fun updateLastSelectedTool(toolName: String) = updatePreferences { it.copy(lastSelectedTool = toolName) }

    // ==========================================================
    // DrawPersistence (editor port)
    // ==========================================================

    override suspend fun saveNewStrokes(page: DrawPage, strokes: List<DrawStroke>) {
        if (page.dbId == 0 || strokes.isEmpty()) return
        saveNewStrokes(page.dbId, strokes)
    }

    override suspend fun updateStrokes(page: DrawPage, strokes: List<DrawStroke>) {
        if (page.dbId == 0) return
        withContext(Dispatchers.IO) {
            for (stroke in strokes) {
                if (stroke.dbId == 0) stroke.dbId = strokeDao.insert(stroke.toEntity(page.dbId)).toInt()
                else strokeDao.insert(stroke.toEntity(page.dbId, stroke.dbId))
            }
        }
    }

    override suspend fun deleteStrokes(strokes: List<DrawStroke>) {
        val ids = strokes.mapNotNull { s -> s.dbId.takeIf { it != 0 } }
        deleteStrokesByIds(ids)
        strokes.forEach { it.dbId = 0 }
    }

    override suspend fun saveText(page: DrawPage, text: DrawText) {
        if (page.dbId == 0) return
        withContext(Dispatchers.IO) {
            val entity = TextEntity(
                id = text.dbId,
                pageId = page.dbId,
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
            if (text.dbId == 0) text.dbId = textDao.insert(entity).toInt()
            else textDao.insert(entity)
        }
    }

    override suspend fun deleteText(text: DrawText) {
        if (text.dbId == 0) return
        withContext(Dispatchers.IO) { textDao.deleteById(text.dbId) }
        text.dbId = 0
    }

    override suspend fun saveImage(page: DrawPage, image: DrawImage) {
        if (page.dbId == 0) return
        withContext(Dispatchers.IO) {
            val entity = ImageEntity(
                id = image.dbId,
                pageId = page.dbId,
                zIndex = image.zIndex,
                resourceId = image.resourceId,
                x = image.x,
                y = image.y,
                width = image.width,
                height = image.height,
                rotation = image.rotation
            )
            if (image.dbId == 0) image.dbId = imageDao.insert(entity).toInt()
            else imageDao.insert(entity)
        }
    }

    override suspend fun deleteImage(image: DrawImage) {
        if (image.dbId == 0) return
        withContext(Dispatchers.IO) { imageDao.deleteById(image.dbId) }
        image.dbId = 0
    }

    override suspend fun savePdf(page: DrawPage, pdf: DrawPdf) {
        if (page.dbId == 0) return
        withContext(Dispatchers.IO) {
            val entity = PdfEntity(
                id = pdf.dbId,
                pageId = page.dbId,
                zIndex = pdf.zIndex,
                resourceId = pdf.resourceId,
                pdfPageIndex = pdf.pdfPageIndex
            )
            pdf.dbId = pdfDao.insert(entity).toInt()
        }
    }

    override suspend fun addResource(document: DrawDocument, type: DrawResource.ResourceType, path: String): String {
        if (document.dbId == 0) return path
        return withContext(Dispatchers.IO) {
            resourceDao.insert(ResourceEntity(documentId = document.dbId, type = type.name, uri = path)).toString()
        }
    }

    override suspend fun insertPage(document: DrawDocument, page: DrawPage) {
        if (document.dbId == 0) return
        insertPageAt(document.dbId, page)
    }

    override suspend fun softDeletePage(document: DrawDocument, page: DrawPage, index: Int) {
        if (document.dbId == 0 || page.dbId == 0) return
        softDeletePageAtIndex(document.dbId, page.dbId, index)
    }

    override suspend fun restorePage(document: DrawDocument, page: DrawPage, index: Int) {
        if (document.dbId == 0 || page.dbId == 0) return
        restorePageAtIndex(document.dbId, page.dbId, index)
    }

    override suspend fun updatePagesOrder(document: DrawDocument, pages: List<DrawPage>) {
        if (document.dbId == 0) return
        updatePagesOrder(document.dbId, pages)
    }

    override suspend fun updatePageFormat(document: DrawDocument, page: DrawPage) {
        if (document.dbId == 0) return
        updatePageFormatAndBackground(document.dbId, page)
    }

    override suspend fun updateDocumentDefaults(document: DrawDocument) {
        if (document.dbId == 0) return
        updateDocumentDefaults(document.dbId, document.defaultBackground, document.defaultWidthMm, document.defaultHeightMm)
    }

    override suspend fun touchDocument(document: DrawDocument, timestamp: Long) {
        if (document.dbId == 0) return
        touchDocument(document.dbId, timestamp)
    }

    override suspend fun savePresets(tool: Tool, presets: List<ToolPreset>) {
        val data = presets.toBrushSettings()
        when (tool) {
            Tool.INK_PEN -> updatePenPresets(data)
            Tool.INK_HIGHLIGHTER -> updateHighlighterPresets(data)
            Tool.ERASER -> updateEraserPresets(data)
            Tool.LAZO -> updateLazoPresets(data)
            else -> {}
        }
    }

    override suspend fun saveStylusOnlyMode(enabled: Boolean) = updateStylusMode(enabled)

    override suspend fun saveLastSelectedTool(tool: Tool) = updateLastSelectedTool(tool.name)

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
    lazoPresets = lazoPresets
)
