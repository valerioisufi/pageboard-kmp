package io.github.valerioisufi.drawview.core.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.core.geometry.Dimension
import io.github.valerioisufi.drawview.core.history.AddPageAction
import io.github.valerioisufi.drawview.core.history.ChangePageTemplateAction
import io.github.valerioisufi.drawview.core.history.DeletePageAction
import io.github.valerioisufi.drawview.core.history.ReorderPagesAction
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
        background = document.defaultBackground
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
        document.pages.add(index, page)
        document.reindexPages()
        controller.historyManager.addHistoryAction(AddPageAction(page, index))
        controller.persist { insertPage(document, page) }
        controller.markLayoutNeedsUpdate()
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
        val page = document.pages.removeAt(pageIndex)
        document.reindexPages()
        controller.historyManager.addHistoryAction(DeletePageAction(page, pageIndex))
        controller.persist { softDeletePage(document, page, pageIndex) }
        controller.markLayoutNeedsUpdate()
    }

    /**
     * Moves a page (e.g. drag & drop in the page grid), recording the change in the history.
     */
    fun movePage(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in document.pages.indices || toIndex !in document.pages.indices || fromIndex == toIndex) return
        controller.selectionManager.clearSelection()
        val oldOrder = document.pages.toList()
        val page = document.pages.removeAt(fromIndex)
        document.pages.add(toIndex, page)
        document.reindexPages()
        val newOrder = document.pages.toList()
        controller.historyManager.addHistoryAction(ReorderPagesAction(oldOrder, newOrder))
        controller.persist { updatePagesOrder(document, newOrder) }
        controller.markLayoutNeedsUpdate()
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
        val newOrder = document.pages.toList()
        if (oldOrder != null && oldOrder != newOrder) {
            controller.historyManager.addHistoryAction(ReorderPagesAction(oldOrder, newOrder))
            controller.persist { updatePagesOrder(document, newOrder) }
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
        val custom = background != document.defaultBackground
        val action = ChangePageTemplateAction(
            page = page,
            oldWidthMm = page.widthMm, oldHeightMm = page.heightMm,
            oldBackground = page.background, oldHasCustomBackground = page.hasCustomBackground,
            newWidthMm = dimension.width.mm, newHeightMm = dimension.height.mm,
            newBackground = background, newHasCustomBackground = custom
        )
        page.widthMm = dimension.width.mm
        page.heightMm = dimension.height.mm
        page.background = background
        page.hasCustomBackground = custom
        page.markContentChanged()
        controller.historyManager.addHistoryAction(action)
        controller.persist { updatePageFormat(document, page) }
        controller.markLayoutNeedsUpdate()
        controller.invalidatePageContent(page, null)
    }

    /**
     * Changes the default format and background of the document.
     *
     * @param overrideAll `true` to apply them to every page, `false` to apply the background only
     * to the pages that follow the document default.
     */
    fun applyDocumentTemplateChange(dimension: Dimension, background: PageBackground, overrideAll: Boolean) {
        document.defaultWidthMm = dimension.width.mm
        document.defaultHeightMm = dimension.height.mm
        document.defaultBackground = background
        val changed = mutableListOf<DrawPage>()
        for (page in document.pages) {
            if (overrideAll) {
                page.widthMm = dimension.width.mm
                page.heightMm = dimension.height.mm
                page.background = background
                page.hasCustomBackground = false
                changed.add(page)
            } else if (!page.hasCustomBackground) {
                page.background = background
                changed.add(page)
            }
        }
        controller.persist {
            updateDocumentDefaults(document)
            changed.forEach { updatePageFormat(document, it) }
        }
        changed.forEach { it.markContentChanged() }
        controller.markLayoutNeedsUpdate()
        controller.tileManager.invalidateAll()
    }
}
