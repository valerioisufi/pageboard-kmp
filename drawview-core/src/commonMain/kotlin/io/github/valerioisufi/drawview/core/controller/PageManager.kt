package io.github.valerioisufi.drawview.core.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.core.change.AddPage
import io.github.valerioisufi.drawview.core.change.CompositeChange
import io.github.valerioisufi.drawview.core.change.RemovePage
import io.github.valerioisufi.drawview.core.change.SetDocumentDefaults
import io.github.valerioisufi.drawview.core.change.SetPageFormat
import io.github.valerioisufi.drawview.core.change.MovePages
import io.github.valerioisufi.drawview.core.geometry.Dimension
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.PageBackground

/**
 * Page management: insertion, deletion, reordering (drag & drop on the canvas or in the page
 * grid), templates and navigation.
 */
class PageManager(private val controller: DrawViewController) {

    /** Page targeted by the context menu / page grid actions. */
    var contextMenuTargetPageIndex: Int by mutableIntStateOf(-1)

    // --- Drag & drop reordering on the canvas ---
    var isReorderingPages: Boolean by mutableStateOf(false)
        private set
    var draggedPageIndex: Int by mutableIntStateOf(-1)
        private set
    var floatingPageRect: Rect? by mutableStateOf(null)
        private set

    private var dragTouchOffset = Offset.Zero
    private var originalPageOrder: List<DrawPage>? = null

    private val document get() = controller.document

    private fun newPage(index: Int): DrawPage = DrawPage(
        index = index,
        widthMm = document.defaultWidthMm,
        heightMm = document.defaultHeightMm,
        background = document.defaultBackground,
        orderKey = document.orderKeyAt(index)
    )

    fun addNewPageAtBottom(): DrawPage = insertPage(document.pages.size)

    /**
     * Inserts a new page right after [pageIndex] (at the end when the index is invalid).
     */
    fun addNewPageAfter(pageIndex: Int): DrawPage {
        val target = if (pageIndex in document.pages.indices) pageIndex + 1 else document.pages.size
        return insertPage(target)
    }

    private fun insertPage(index: Int): DrawPage {
        controller.selectionManager.clearSelection()
        val page = newPage(index)
        controller.session.submit(AddPage.of(page))
        return page
    }

    /**
     * Deletes the page at [pageIndex] (the last page of a document cannot be deleted).
     */
    fun deletePage(pageIndex: Int) {
        if (document.pages.size <= 1 || pageIndex !in document.pages.indices) return
        controller.selectionManager.clearSelection()
        if (controller.textEditorManager.activeTextPageIndex == pageIndex) {
            controller.textEditorManager.cancelTextEditing()
        }
        controller.session.submit(RemovePage(document.pages[pageIndex].id))
    }

    /**
     * Moves a page (e.g. drag & drop in the page grid), recording the change in the history.
     */
    fun movePage(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in document.pages.indices || toIndex !in document.pages.indices || fromIndex == toIndex) return
        controller.selectionManager.clearSelection()
        val order = document.pages.map { it.id }.toMutableList()
        order.add(toIndex, order.removeAt(fromIndex))
        MovePages.toOrder(document, order)?.let { controller.session.submit(it) }
    }

    // ==========================================================
    // Canvas drag & drop
    // ==========================================================

    fun startPageReorderMode() {
        controller.selectionManager.clearSelection()
        controller.textEditorManager.cancelTextEditing()
        controller.contextMenuPosition = null
        originalPageOrder = document.pages.toList()
        isReorderingPages = true
        controller.requestRedraw()
    }

    /**
     * Picks the page under [touch] (screen pixels).
     * @return `true` if a page has been picked.
     */
    fun startDraggingPage(touch: Offset): Boolean {
        val visible = controller.visiblePages().firstOrNull { it.rect.contains(touch) } ?: return false
        draggedPageIndex = visible.index
        floatingPageRect = visible.rect
        dragTouchOffset = touch - visible.rect.topLeft
        controller.cameraPhysics.stopAllAnimations()
        controller.requestRedraw()
        return true
    }

    fun updateDragPosition(touch: Offset) {
        val rect = floatingPageRect ?: return
        val topLeft = touch - dragTouchOffset
        floatingPageRect = Rect(topLeft, rect.size)
        performSwap()
        controller.requestRedraw()
    }

    /** Called while auto-scrolling: the floating page stays under the finger. */
    fun onCameraMovedWhileDragging() {
        performSwap()
    }

    private fun performSwap() {
        val floating = floatingPageRect ?: return
        val from = draggedPageIndex
        if (from < 0) return
        val target = controller.visiblePages().firstOrNull { it.index != from && it.rect.contains(floating.center) } ?: return
        val page = document.pages.removeAt(from)
        document.pages.add(target.index, page)
        document.reindexPages()
        draggedPageIndex = target.index
        controller.updatePageLayout()
    }

    /**
     * Moves a page while a reorder session is active (drag & drop in the page grid): the whole
     * reordering is recorded as a single change by [finishPageReorderMode].
     */
    fun movePageDuringReorder(fromIndex: Int, toIndex: Int) {
        if (!isReorderingPages) return movePage(fromIndex, toIndex)
        if (fromIndex !in document.pages.indices || toIndex !in document.pages.indices || fromIndex == toIndex) return
        val page = document.pages.removeAt(fromIndex)
        document.pages.add(toIndex, page)
        document.reindexPages()
        controller.updatePageLayout()
        controller.requestRedraw()
    }

    fun releaseDraggedPage() {
        draggedPageIndex = -1
        floatingPageRect = null
        controller.requestRedraw()
    }

    fun finishPageReorderMode() {
        releaseDraggedPage()
        isReorderingPages = false
        val oldOrder = originalPageOrder
        originalPageOrder = null
        val newOrder = document.pages.map { it.id }
        if (oldOrder != null) {
            // The pages were moved live: back to where the change starts from (its undo)
            document.pages.clear()
            document.pages.addAll(oldOrder)
            document.reindexPages()
            MovePages.toOrder(document, newOrder)?.let { controller.session.submit(it) }
        }
        controller.markLayoutNeedsUpdate()
    }

    // ==========================================================
    // Navigation
    // ==========================================================

    /**
     * Centers the camera on the page [pageIndex], keeping the current zoom level.
     */
    fun jumpToPage(pageIndex: Int) {
        val rects = controller.calcPage.pagesRectOnWindow
        if (pageIndex !in rects.indices) return
        val target = rects[pageIndex]
        controller.cameraPhysics.centerOnWorldPoint(
            worldX = target.center.x,
            worldY = target.center.y,
            scale = controller.cameraPhysics.getCurrentScale(),
            screenWidth = controller.viewportWidth,
            screenHeight = controller.viewportHeight
        )
        controller.cameraPhysics.restoreToBounds(animated = false)
        controller.isPageGridVisible = false
        controller.requestRedraw()
    }

    // ==========================================================
    // Templates
    // ==========================================================

    /**
     * Changes format and background of a single page.
     */
    fun changeSinglePageTemplate(pageIndex: Int, dimension: Dimension, background: PageBackground) {
        val page = document.pages.getOrNull(pageIndex) ?: return
        val custom = background.takeIf { it != document.defaultBackground }
        controller.session.submit(SetPageFormat(page.id, dimension.width.mm, dimension.height.mm, custom))
    }

    /**
     * Changes the default format and background of the document.
     *
     * @param overrideAll `true` to apply them to every page, `false` to apply the background only
     * to the pages that follow the document default.
     */
    fun applyDocumentTemplateChange(dimension: Dimension, background: PageBackground, overrideAll: Boolean) {
        val defaults = SetDocumentDefaults(dimension.width.mm, dimension.height.mm, background)
        val pages = if (overrideAll) {
            document.pages.map { SetPageFormat(it.id, dimension.width.mm, dimension.height.mm, null) }
        } else {
            emptyList()
        }
        controller.session.submit(CompositeChange(listOf(defaults) + pages))
    }
}
