package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.CompositeChange
import io.github.valerioisufi.drawview.model.change.MovePages
import io.github.valerioisufi.drawview.model.change.RemovePage
import io.github.valerioisufi.drawview.model.change.SetDocumentDefaults
import io.github.valerioisufi.drawview.model.change.SetPageFormat
import io.github.valerioisufi.drawview.model.geometry.Dimension

/**
 * Page management: insertion, deletion, reordering (drag & drop on the canvas or in the page
 * grid), templates and navigation.
 */
public class PageManager(private val controller: DrawViewController) {

    /** Page targeted by the context menu / page grid actions. */
    public var contextMenuTargetPageIndex: Int by mutableIntStateOf(-1)

    // --- Drag & drop reordering on the canvas ---
    internal var isReorderingPages: Boolean by mutableStateOf(false)
        private set
    internal var draggedPageIndex: Int by mutableIntStateOf(-1)
        private set
    internal var floatingPageRect: Rect? by mutableStateOf(null)
        private set

    private var dragTouchOffset = Offset.Zero
    private var originalPageOrder: List<DrawPage>? = null

    private val document get() = controller.document

    /**
     * A new page at [index] of the format of [template]: its size, and its background when it is
     * its own (else the page follows the background of the document, as [template] does). Without
     * a template, the format of the document.
     */
    private fun newPage(index: Int, template: DrawPage?): DrawPage {
        val ownBackground = template?.takeIf { it.hasCustomBackground }?.background
        return DrawPage(
            index = index,
            widthMm = template?.widthMm ?: document.defaultWidthMm,
            heightMm = template?.heightMm ?: document.defaultHeightMm,
            background = ownBackground ?: document.defaultBackground,
            orderKey = document.orderKeyAt(index)
        ).apply { hasCustomBackground = ownBackground != null }
    }

    /** The page a new page put after [pageIndex] follows: that page, or the last one when it is not a page. */
    private fun pageBefore(pageIndex: Int): DrawPage? = document.pages.getOrNull(pageIndex) ?: document.pages.lastOrNull()

    /**
     * Inserts a new page at the end, of the format of [template] (by default the last page; `null`:
     * the format of the document).
     */
    public fun addNewPageAtBottom(template: DrawPage? = document.pages.lastOrNull()): DrawPage =
        insertPage(document.pages.size, template)

    /**
     * Inserts a new page right after [pageIndex] (at the end when the index is invalid), of the
     * format of [template]: by default the page it follows, so that a page added after another one
     * is like it; `null`: the format of the document.
     */
    public fun addNewPageAfter(pageIndex: Int, template: DrawPage? = pageBefore(pageIndex)): DrawPage {
        val target = if (pageIndex in document.pages.indices) pageIndex + 1 else document.pages.size
        return insertPage(target, template)
    }

    private fun insertPage(index: Int, template: DrawPage?): DrawPage {
        controller.selectionManager.clearSelection()
        val page = newPage(index, template)
        controller.session.submit(AddPage.of(page))
        return page
    }

    /**
     * Deletes the page at [pageIndex] (the last page of a document cannot be deleted).
     */
    public fun deletePage(pageIndex: Int) {
        if (document.pages.size <= 1 || pageIndex !in document.pages.indices) return
        controller.selectionManager.clearSelection()
        if (controller.textEditorManager.activeTextPageIndex == pageIndex) {
            controller.textEditorManager.cancelEditing()
        }
        controller.session.submit(RemovePage(document.pages[pageIndex].id))
    }

    /**
     * Moves a page (e.g. drag & drop in the page grid), recording the change in the history.
     */
    public fun movePage(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in document.pages.indices || toIndex !in document.pages.indices || fromIndex == toIndex) return
        controller.selectionManager.clearSelection()
        val order = document.pages.map { it.id }.toMutableList()
        order.add(toIndex, order.removeAt(fromIndex))
        MovePages.toOrder(document, order)?.let { controller.session.submit(it) }
    }

    // ==========================================================
    // Canvas drag & drop
    // ==========================================================

    public fun startPageReorderMode() {
        controller.selectionManager.clearSelection()
        controller.textEditorManager.finishEditing()
        controller.contextMenuPosition = null
        originalPageOrder = document.pages.toList()
        isReorderingPages = true
        controller.requestRedraw()
    }

    /**
     * Picks the page under [touch] (screen pixels).
     * @return `true` if a page has been picked.
     */
    internal fun startDraggingPage(touch: Offset): Boolean {
        val visible = controller.viewport.visiblePages().firstOrNull { it.rect.contains(touch) } ?: return false
        draggedPageIndex = visible.index
        floatingPageRect = visible.rect
        dragTouchOffset = touch - visible.rect.topLeft
        controller.viewport.camera.stopAllAnimations()
        controller.requestRedraw()
        return true
    }

    internal fun updateDragPosition(touch: Offset) {
        val rect = floatingPageRect ?: return
        val topLeft = touch - dragTouchOffset
        floatingPageRect = Rect(topLeft, rect.size)
        performSwap()
        controller.requestRedraw()
    }

    /** Called while auto-scrolling: the floating page stays under the finger. */
    internal fun onCameraMovedWhileDragging() {
        performSwap()
    }

    private fun performSwap() {
        val floating = floatingPageRect ?: return
        val from = draggedPageIndex
        if (from < 0) return
        val target = controller.viewport.visiblePages().firstOrNull { it.index != from && it.rect.contains(floating.center) } ?: return
        document.movePage(from, target.index)
        draggedPageIndex = target.index
        controller.viewport.updatePageLayout()
    }

    /**
     * Moves a page while a reorder session is active (drag & drop in the page grid): the whole
     * reordering is recorded as a single change by [finishPageReorderMode].
     */
    public fun movePageDuringReorder(fromIndex: Int, toIndex: Int) {
        if (!isReorderingPages) return movePage(fromIndex, toIndex)
        if (fromIndex !in document.pages.indices || toIndex !in document.pages.indices || fromIndex == toIndex) return
        document.movePage(fromIndex, toIndex)
        controller.viewport.updatePageLayout()
        controller.requestRedraw()
    }

    internal fun releaseDraggedPage() {
        draggedPageIndex = -1
        floatingPageRect = null
        controller.requestRedraw()
    }

    public fun finishPageReorderMode() {
        releaseDraggedPage()
        isReorderingPages = false
        val oldOrder = originalPageOrder
        originalPageOrder = null
        val newOrder = document.pages.map { it.id }
        if (oldOrder != null) {
            // The pages were moved live: back to where the change starts from (its undo)
            document.restorePageOrder(oldOrder)
            MovePages.toOrder(document, newOrder)?.let { controller.session.submit(it) }
        }
        controller.viewport.markLayoutNeedsUpdate()
    }

    // ==========================================================
    // Navigation
    // ==========================================================

    /**
     * Centers the camera on the page [pageIndex], keeping the current zoom level.
     */
    public fun jumpToPage(pageIndex: Int) {
        val rects = controller.viewport.layout.pagesRectOnWindow
        if (pageIndex !in rects.indices) return
        val target = rects[pageIndex]
        controller.viewport.camera.centerOnWorldPoint(
            worldX = target.center.x,
            worldY = target.center.y,
            scale = controller.viewport.camera.getCurrentScale(),
            screenWidth = controller.viewport.width,
            screenHeight = controller.viewport.height
        )
        controller.viewport.camera.restoreToBounds(animated = false)
        controller.isPageGridVisible = false
        controller.requestRedraw()
    }

    // ==========================================================
    // Templates
    // ==========================================================

    /**
     * Changes format and background of a single page.
     */
    public fun changeSinglePageTemplate(pageIndex: Int, dimension: Dimension, background: PageBackground) {
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
    public fun applyDocumentTemplateChange(dimension: Dimension, background: PageBackground, overrideAll: Boolean) {
        val defaults = SetDocumentDefaults(dimension.width.mm, dimension.height.mm, background)
        val pages = if (overrideAll) {
            document.pages.map { SetPageFormat(it.id, dimension.width.mm, dimension.height.mm, null) }
        } else {
            emptyList()
        }
        controller.session.submit(CompositeChange(listOf(defaults) + pages))
    }
}
