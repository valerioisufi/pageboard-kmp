package io.github.valerioisufi.drawview.core.history

import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.core.controller.DrawPersistence
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawPage

/**
 * Execution context for Undo/Redo commands.
 * Keeps actions decoupled from UI controllers and persistence layers.
 */
interface DrawingActionContext {
    val document: DrawDocument?

    fun onPageModified(pageIndex: Int) {}

    fun markLayoutNeedsUpdate() {}

    /** Schedules a persistence operation (operations are executed in submission order). */
    fun persist(operation: suspend DrawPersistence.() -> Unit) {}

    /**
     * Invalidates the rendering caches of [page] in [mmRect] (page millimeters, `null` for the
     * whole page).
     */
    fun invalidatePageContent(page: DrawPage, mmRect: Rect? = null) {
        onPageModified(page.index)
    }

    /** Discards the active lasso selection (actions may move the selected elements). */
    fun clearSelection() {}
}

/**
 * Represents a reversible operation performed on the canvas/document.
 * Implements the Command Pattern for Undo/Redo architecture.
 */
interface DrawAction {
    suspend fun undo(context: DrawingActionContext)
    suspend fun redo(context: DrawingActionContext)
}
