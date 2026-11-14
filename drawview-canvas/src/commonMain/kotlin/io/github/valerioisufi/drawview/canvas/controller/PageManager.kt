package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.FractionalIndex
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.TrashRetention
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.CompositeChange
import io.github.valerioisufi.drawview.model.change.CopiedPages
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.MovePages
import io.github.valerioisufi.drawview.model.change.RemovePage
import io.github.valerioisufi.drawview.model.change.SetDocumentDefaults
import io.github.valerioisufi.drawview.model.change.SetPageFormat
import io.github.valerioisufi.drawview.model.change.copyPages
import io.github.valerioisufi.drawview.model.change.pasteInto
import io.github.valerioisufi.drawview.model.geometry.Dimension
import io.github.valerioisufi.drawview.model.geometry.Rect as MmRect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Page management: insertion, deletion, copy and paste, reordering (drag & drop on the canvas
 * or in the page grid), templates and navigation.
 */
public class PageManager internal constructor(private val controller: DrawViewController) {

    /** The pages selected in the page grid. */
    public val selection: PageSelection = PageSelection(controller.document)

    private val deletedPages = MutableSharedFlow<List<String>>(extraBufferCapacity = 8)

    /** The pages deleted here ([deletePages]), each time: told with Undo ([restorePages]). */
    public val deleted: SharedFlow<List<String>> = deletedPages.asSharedFlow()

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

    /**
     * [count] new pages to put right after [pageIndex], one after the other, of the format of that
     * page, not added yet (the changes adding them are the caller's, see `AddPage`).
     */
    internal fun newPagesAfter(pageIndex: Int, count: Int): List<DrawPage> {
        val template = pageBefore(pageIndex)
        val index = if (pageIndex in document.pages.indices) pageIndex + 1 else document.pages.size
        var before = document.pages.getOrNull(index - 1)?.orderKey
        val after = document.pages.drop(index).firstOrNull { before == null || it.orderKey > before!! }?.orderKey
        return List(count) { k ->
            val key = FractionalIndex.between(before, after)
            before = key
            newPage(index + k, template).copyWithOrderKey(key)
        }
    }

    private fun DrawPage.copyWithOrderKey(key: String): DrawPage = DrawPage(
        index = index,
        widthMm = widthMm,
        heightMm = heightMm,
        background = background,
        orderKey = key
    ).also { it.hasCustomBackground = hasCustomBackground }

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

    /**
     * Inserts a new page right before [pageIndex] (at the end when the index is invalid), of the
     * format of [template]: by default the page it goes before, so that it is like its neighbor;
     * `null`: the format of the document.
     */
    public fun addNewPageBefore(pageIndex: Int, template: DrawPage? = pageBefore(pageIndex)): DrawPage {
        val target = if (pageIndex in document.pages.indices) pageIndex else document.pages.size
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
        val page = document.pages.getOrNull(pageIndex) ?: return
        deletePages(listOf(page.id))
    }

    /** The pages [ids] shown, in the order of the document. */
    private fun pagesOf(ids: Collection<String>): List<DrawPage> = ids.toSet().let { wanted -> document.pages.filter { it.id in wanted } }

    /** [changes] as one change (one step to undo), or `null` for none. */
    private fun oneChange(changes: List<DocumentChange>): DocumentChange? = when (changes.size) {
        0 -> null
        1 -> changes[0]
        else -> CompositeChange(changes)
    }

    /**
     * Deletes the pages [ids] in one change, unless they are every page of the document (a
     * document keeps a page).
     */
    public fun deletePages(ids: Collection<String>) {
        val pages = pagesOf(ids)
        if (pages.isEmpty() || pages.size >= document.pages.size) return
        controller.selectionManager.clearSelection()
        // The page of the text being written, or of the text it changes
        val editor = controller.textEditorManager
        val editing = setOf(editor.activeTextPageIndex, editor.itemPageIndex)
        if (pages.any { document.pages.indexOf(it) in editing }) editor.cancelEditing()
        oneChange(pages.map { RemovePage(it.id) })?.let { controller.session.submit(it) }
        deletedPages.tryEmit(pages.map { it.id })
    }

    /**
     * The pages removed in the last [TrashRetention] at [now] (milliseconds), here or on another
     * device, the most recent removal first: the trash of the document (see `docs/trash.md`).
     */
    public fun deletedPagesAt(now: Long): List<DrawPage> = document.removedPagesSince(TrashRetention.expiredBefore(now))

    /**
     * Puts the pages [ids] removed back where they were, in one change (one step to undo): the
     * change undoing their removal, so that every device sees them again.
     */
    public fun restorePages(ids: Collection<String>) {
        if (controller.isReadOnly) return
        val pages = ids.toSet().mapNotNull { document.removedPages[it] }
        oneChange(pages.map { AddPage.of(it) })?.let { controller.session.submit(it) }
    }

    /** Whether the pages [ids] can be deleted: some, not every page of the document. */
    public fun canDeletePages(ids: Collection<String>): Boolean = pagesOf(ids).size in 1 until document.pages.size

    /** Copies the page at [pageIndex] with all its content into the clipboard of the application (see [EditorClipboard]). */
    public fun copyPage(pageIndex: Int) {
        val page = document.pages.getOrNull(pageIndex) ?: return
        copyPages(listOf(page.id))
    }

    /** Copies the pages [ids] with all their content, in the order of the document, into the clipboard of the application. */
    public fun copyPages(ids: Collection<String>) {
        val pages = pagesOf(ids).takeIf { it.isNotEmpty() } ?: return
        controller.clipboard.content = document.copyPages(pages)
    }

    /** Pastes the pages of the clipboard after the last of the pages [ids] (see [pastePages]). */
    public fun pastePagesAfter(ids: Collection<String>) {
        val last = pagesOf(ids).lastOrNull() ?: return
        pastePages(document.pages.indexOf(last))
    }

    /**
     * Duplicates the pages [ids] with all their content, in the order of the document, right after
     * the last of them, in one change (one step to undo), and shows the first copy. The clipboard
     * of the application is left as it is. The elements of a file not on this device are left out
     * (see [DrawViewController.Message.PASTED_WITHOUT_MISSING_FILES]).
     */
    public fun duplicatePages(ids: Collection<String>) {
        if (controller.isReadOnly) return
        val pages = pagesOf(ids).takeIf { it.isNotEmpty() } ?: return
        controller.selectionManager.clearSelection()
        val index = document.pages.indexOf(pages.last()) + 1
        val pasted = document.copyPages(pages).pasteInto(document, index)
        if (pasted.leftOut > 0) controller.emitMessage(DrawViewController.Message.PASTED_WITHOUT_MISSING_FILES)
        controller.session.submit(pasted.change ?: return) ?: return
        jumpToPage(document.pages.indexOf(pasted.pages.first()))
    }

    /** Whether pages were copied, in this document or another one (see [EditorClipboard]). */
    public fun canPastePages(): Boolean = controller.clipboard.content is CopiedPages

    /**
     * Pastes the pages of the clipboard right after [pageIndex] (at the end when the index is
     * invalid), with all their content, and shows the first one. The elements of a file not on
     * this device are left out (see [DrawViewController.Message.PASTED_WITHOUT_MISSING_FILES]).
     */
    public fun pastePages(pageIndex: Int) {
        val copied = controller.clipboard.content as? CopiedPages ?: return
        controller.selectionManager.clearSelection()
        val index = if (pageIndex in document.pages.indices) pageIndex + 1 else document.pages.size
        val pasted = copied.pasteInto(document, index)
        if (pasted.leftOut > 0) controller.emitMessage(DrawViewController.Message.PASTED_WITHOUT_MISSING_FILES)
        controller.session.submit(pasted.change ?: return) ?: return
        jumpToPage(document.pages.indexOf(pasted.pages.first()))
    }

    /**
     * Moves the pages [ids] as one block, in their order, to [toIndex] among the other pages (0:
     * before all of them), in one change; pages selected apart are gathered.
     */
    public fun movePages(ids: Collection<String>, toIndex: Int) {
        val block = pagesOf(ids).map { it.id }.takeIf { it.isNotEmpty() } ?: return
        controller.selectionManager.clearSelection()
        val others = document.pages.map { it.id }.filter { it !in block }
        val order = others.toMutableList().apply { addAll(toIndex.coerceIn(0, others.size), block) }
        MovePages.toOrder(document, order)?.let { controller.session.submit(it) }
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

    /**
     * Puts the pages [ids] together, in their order, at [toIndex] among the other pages, while a
     * reorder session is active (the page grid dragging a selection): the whole reordering is
     * recorded as a single change by [finishPageReorderMode]. Without a session, [movePages].
     */
    public fun moveBlockDuringReorder(ids: Collection<String>, toIndex: Int) {
        if (!isReorderingPages) return movePages(ids, toIndex)
        val block = pagesOf(ids).takeIf { it.isNotEmpty() } ?: return
        val others = document.pages.filter { it !in block }
        val order = others.toMutableList().apply { addAll(toIndex.coerceIn(0, others.size), block) }
        if (order == document.pages) return
        document.restorePageOrder(order)
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
        jumpToPage(pageIndex, bounds = null)
    }

    /**
     * Centers the camera on the page [pageIndex] (and on [bounds] in page millimeters when given),
     * keeping the current zoom level. If the canvas has not been laid out yet, remembers the place
     * for the first layout.
     */
    public fun jumpToPage(pageIndex: Int, bounds: MmRect?) {
        if (pageIndex !in document.pages.indices) return
        val page = document.pages[pageIndex]
        val normX = if (bounds != null && page.widthMm > 0f) {
            ((bounds.left + bounds.right) * 0.5f / page.widthMm).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        val normY = if (bounds != null && page.heightMm > 0f) {
            ((bounds.top + bounds.bottom) * 0.5f / page.heightMm).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        val rects = controller.viewport.layout.pagesRectOnWindow
        if (controller.viewport.width > 0f && pageIndex in rects.indices) {
            val target = rects[pageIndex]
            controller.viewport.camera.centerOnWorldPoint(
                worldX = target.left + target.width * normX,
                worldY = target.top + target.height * normY,
                scale = controller.viewport.camera.getCurrentScale(),
                screenWidth = controller.viewport.width,
                screenHeight = controller.viewport.height
            )
            controller.viewport.camera.restoreToBounds(animated = false)
        } else {
            // Not laid out yet: centered on it at the first layout
            controller.viewport.setInitialAnchor(pageIndex, normX, normY)
        }
        controller.isPageGridVisible = false
        selection.clear()
        controller.requestRedraw()
    }

    /**
     * Centers the camera on the page identified by [pageId] (or at [pageNumber], 1-based, when
     * [pageId] is not found) and on [bounds] in page millimeters when given.
     */
    public fun jumpToPage(pageId: String, pageNumber: Int = 1, bounds: MmRect? = null) {
        if (document.pages.isEmpty()) return
        val index = document.pages.indexOfFirst { it.id == pageId }.takeIf { it >= 0 }
            ?: (pageNumber - 1).coerceIn(0, document.pages.lastIndex)
        jumpToPage(index, bounds)
    }

    // ==========================================================
    // Templates
    // ==========================================================

    /**
     * Changes format and background of a single page.
     */
    public fun changeSinglePageTemplate(pageIndex: Int, dimension: Dimension, background: PageBackground) {
        val page = document.pages.getOrNull(pageIndex) ?: return
        changePagesTemplate(listOf(page.id), dimension, background)
    }

    /** Gives the pages [ids] the format of [dimension] and [background], in one change. */
    public fun changePagesTemplate(ids: Collection<String>, dimension: Dimension, background: PageBackground) {
        val custom = background.takeIf { it != document.defaultBackground }
        oneChange(pagesOf(ids).map { SetPageFormat(it.id, dimension.width.mm, dimension.height.mm, custom) })?.let { controller.session.submit(it) }
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
