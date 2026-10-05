package io.github.valerioisufi.drawview.data.repository

import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.data.db.BrushSettingsData
import io.github.valerioisufi.drawview.data.db.UserPreferencesEntity
import kotlinx.coroutines.flow.Flow

/**
 * Interface abstraction for document persistence operations.
 */
interface IDrawDocumentRepository {
    val userPreferencesFlow: Flow<UserPreferencesEntity?>

    suspend fun loadDocument(documentId: Int): DrawDocument?
    suspend fun createNewDefaultDocument(defaultDocumentName: String): DrawDocument
    suspend fun touchDocument(documentId: Int, timestamp: Long)
    suspend fun updateLastOpened(documentId: Int, timestamp: Long)

    suspend fun insertPageAt(documentId: Int, page: DrawPage): Int
    suspend fun deletePageAtIndex(documentId: Int, pageDbId: Int, deletedIndex: Int)
    suspend fun updatePagesOrder(documentId: Int, pages: List<DrawPage>)
    suspend fun softDeletePageAtIndex(documentId: Int, pageDbId: Int, deletedIndex: Int)
    suspend fun restorePageAtIndex(documentId: Int, pageDbId: Int, restoreIndex: Int)

    suspend fun saveNewStroke(pageId: Int, stroke: DrawStroke): Int
    suspend fun updateStroke(pageId: Int, stroke: DrawStroke)
    suspend fun deleteStroke(strokeId: Int)
    suspend fun saveNewStrokes(pageId: Int, strokes: List<DrawStroke>)
    suspend fun deleteStrokesByIds(strokeIds: List<Int>)

    suspend fun updateStylusMode(isStylusOnly: Boolean)
    suspend fun updatePenPresets(presets: List<BrushSettingsData>)
    suspend fun updateHighlighterPresets(presets: List<BrushSettingsData>)
    suspend fun updateEraserPresets(presets: List<BrushSettingsData>)
    suspend fun updateLazoPresets(presets: List<BrushSettingsData>)
    suspend fun updateMarkupPresets(presets: List<BrushSettingsData>)
    suspend fun updateTextDefaults(fontSize: Float, color: Int, isLatex: Boolean)
    suspend fun updateLastSelectedTool(toolName: String)
    suspend fun updatePageFormatAndBackground(documentId: Int, page: DrawPage)
    suspend fun updateDocumentDefaults(documentId: Int, background: PageBackground, width: Float, height: Float)
}
