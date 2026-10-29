package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.geometry.Rect as MmRect
import io.github.valerioisufi.drawview.model.placement.PageOccupancy
import io.github.valerioisufi.drawview.model.placement.PlacementBlock
import io.github.valerioisufi.drawview.model.placement.PlacementPlanner
import io.github.valerioisufi.drawview.model.placement.PlacementPolicy
import io.github.valerioisufi.drawview.model.placement.ReadingOrder

/**
 * A place for a block of content: its box on page [pageIndex] (page millimeters), or on a new page
 * created right after it when [newPageAfter] (the new page is made when the place is confirmed,
 * see [PlacementManager.pagesFor]).
 */
public class PlacementSpot(
    public val pageIndex: Int,
    public val box: MmRect,
    public val newPageAfter: Boolean = false
)

/**
 * A text to place and the places proposed for it, the best first (empty: the student chooses the
 * place with a tap).
 */
public class PlacementItem(public val preview: DrawText, public val spots: List<PlacementSpot> = emptyList())

/**
 * Texts prepared by the application (e.g. the notes of an assistant) waiting to be placed by the
 * student: each is shown in the first place proposed for it; the student goes through the other
 * places of a single text, drags any of them, or taps a page to put a single text there, then
 * confirms.
 *
 * @param items the texts: their content and size ([DrawText.width], [DrawText.height] in page
 *   millimeters) are drawn where they would go.
 * @param onPlace called on confirmation with the place of every item, in their order.
 * @param onCancel called when the placement is cancelled (Escape, another request).
 * @param label what is being placed, shown by the bar of the placement.
 */
public class PlacementRequest(
    public val items: List<PlacementItem>,
    public val onPlace: (spots: List<PlacementSpot>) -> Unit,
    public val onCancel: () -> Unit = {},
    public val label: String = ""
) {
    /** A single text [preview], proposed at [spots], placed by [onPlace]. */
    public constructor(
        preview: DrawText,
        onPlace: (PlacementSpot) -> Unit,
        onCancel: () -> Unit = {},
        spots: List<PlacementSpot> = emptyList(),
        label: String = ""
    ) : this(listOf(PlacementItem(preview, spots)), { onPlace(it.single()) }, onCancel, label)

    /** The text of a single item, `null` for several. */
    public val preview: DrawText? get() = items.singleOrNull()?.preview
}

/**
 * Pending placement of a [PlacementRequest]: while it is active, the texts are drawn where they
 * would go; a tap on one confirms, a drag of one moves it, a tap elsewhere on a page puts a single
 * text there, and the other gestures (drags, pinches, wheel, trackpad) still move the canvas, so
 * that any page can be reached. A single text without places proposed is drawn under the pointer
 * (mouse or stylus hover).
 */
public class PlacementManager(private val controller: DrawViewController) {

    public var request: PlacementRequest? by mutableStateOf(null)
        private set

    /** Screen position of the pointer while a placement is pending (`null`: outside the canvas). */
    public var pointer: Offset? by mutableStateOf(null)
        internal set

    /** The place of every item now (`null`: none yet, it follows the pointer). */
    public var spots: List<PlacementSpot?> by mutableStateOf(emptyList())
        private set

    /** Which of the places proposed for a single item is shown (-1: one chosen by the student). */
    public var spotIndex: Int by mutableIntStateOf(0)
        private set

    /** The free space of the pages of the places shown, faintly drawn while the placement is pending. */
    internal var freeSpace: Map<Int, List<MmRect>> = emptyMap()
        private set

    public val isActive: Boolean get() = request != null

    /** The places proposed for a single item (none for several, which are not gone through). */
    public val proposals: List<PlacementSpot> get() = request?.items?.singleOrNull()?.spots.orEmpty()

    /** Whether every item has a place, so that the placement can be confirmed. */
    public val canConfirm: Boolean get() = request != null && spots.isNotEmpty() && spots.all { it != null }

    /** Starts placing [newRequest] (cancelling the pending one), showing its first place. */
    public fun start(newRequest: PlacementRequest) {
        cancel()
        controller.selectionManager.clearSelection()
        if (controller.textEditorManager.isEditing) controller.textEditorManager.finishEditing()
        controller.contextMenuPosition = null
        controller.isPageGridVisible = false
        request = newRequest
        spots = newRequest.items.map { it.spots.firstOrNull() }
        spotIndex = 0
        freeSpace = spots.filterNotNull().filter { !it.newPageAfter }.map { it.pageIndex }.distinct()
            .associateWith { planner.freeSpace(occupancyOf(it)).freeRuns() }
        spots.firstOrNull { it != null }?.let { reveal(it) }
        controller.requestRedraw()
    }

    public fun cancel() {
        val current = request ?: return
        clear()
        current.onCancel()
    }

    /** Shows the next place proposed for a single item (after the last, the first). */
    public fun next(): Unit = step(1)

    /** Shows the previous place proposed for a single item (before the first, the last). */
    public fun previous(): Unit = step(-1)

    private fun step(delta: Int) {
        val proposals = proposals
        if (proposals.isEmpty()) return
        val from = spotIndex.coerceAtLeast(0)
        spotIndex = ((from + delta) % proposals.size + proposals.size) % proposals.size
        val spot = proposals[spotIndex]
        spots = listOf(spot)
        reveal(spot)
        controller.requestRedraw()
    }

    /**
     * Places the items where they are now.
     *
     * @return `false` when an item has no place yet (the placement stays pending).
     */
    public fun confirm(): Boolean {
        val current = request ?: return false
        if (!canConfirm) return false
        val chosen = spots.map { it!! }
        clear()
        current.onPlace(chosen)
        return true
    }

    private fun clear() {
        request = null
        pointer = null
        spots = emptyList()
        spotIndex = 0
        freeSpace = emptyMap()
        controller.requestRedraw()
    }

    /** Brings [spot] on screen: its box, or the end of the page a new page would follow. */
    private fun reveal(spot: PlacementSpot) {
        val page = controller.document.pages.getOrNull(spot.pageIndex) ?: return
        val box = if (spot.newPageAfter) MmRect(0f, page.heightMm - 1f, page.widthMm, page.heightMm) else spot.box
        val visible = controller.viewport.visiblePages().firstOrNull { it.index == spot.pageIndex }
        val mmToScreen = controller.viewport.pageMmToScreen(spot.pageIndex)
        if (visible != null && mmToScreen != null) {
            val corners = listOf(mmToScreen.mapPoint(box.left, box.top), mmToScreen.mapPoint(box.right, box.bottom))
            val inside = corners.all { it.x >= 0f && it.y >= 0f && it.x <= controller.viewport.width && it.y <= controller.viewport.height }
            if (inside) return
        }
        controller.pageManager.jumpToPage(spot.pageIndex, box)
    }

    // ==========================================================
    // Places
    // ==========================================================

    /** The planner of the places proposed by [spotsFor]. */
    public val planner: PlacementPlanner = PlacementPlanner()

    /**
     * What page [pageIndex] holds, but the elements of [excluded]: its elements, and the lines of
     * the text of its PDF page when the PDF engine reads them.
     */
    public fun occupancyOf(pageIndex: Int, excluded: Set<String> = emptySet()): PageOccupancy {
        val page = controller.document.pages.getOrNull(pageIndex) ?: return PageOccupancy(0f, 0f, emptyList())
        return PageOccupancy.of(page, pdfText = { controller.textMarkupManager.layoutOf(it) }, excluded = excluded)
    }

    /**
     * The places for [block] on page [pageIndex] by [policy], the best first; when it fits nowhere
     * there, the top of a new page after it.
     */
    public fun spotsFor(pageIndex: Int, block: PlacementBlock, policy: PlacementPolicy): List<PlacementSpot> {
        val page = controller.document.pages.getOrNull(pageIndex) ?: return emptyList()
        val found = planner.candidates(occupancyOf(pageIndex), block, policy)
        if (found.isNotEmpty()) return found.map { PlacementSpot(pageIndex, it.rect) }
        return listOfNotNull(newPageSpot(page, block))
    }

    /** The top of a new page after [page] (of its format) for [block], or `null` when it does not fit even there. */
    public fun newPageSpot(page: DrawPage, block: PlacementBlock): PlacementSpot? {
        val empty = PageOccupancy(page.widthMm, page.heightMm, emptyList(), PageOccupancy.lineSpacingOf(page.background))
        return planner.candidates(empty, block, ReadingOrder).firstOrNull()?.let { PlacementSpot(page.index, it.rect, newPageAfter = true) }
    }

    /**
     * The page of every spot of [spots]: the page it is on, or a new page right after it (of its
     * format), one for all the spots after the same page. Call it in a group of the history with
     * the content added, so that they are undone together.
     */
    public fun pagesFor(spots: List<PlacementSpot>): List<DrawPage?> {
        val pages = controller.document.pages.toList()
        val created = mutableMapOf<Int, DrawPage>()
        // From the last page back, so that a page added does not move the ones before it
        for (after in spots.filter { it.newPageAfter }.map { it.pageIndex }.distinct().sortedDescending()) {
            if (after in pages.indices) created[after] = controller.pageManager.addNewPageAfter(after)
        }
        return spots.map { if (it.newPageAfter) created[it.pageIndex] else pages.getOrNull(it.pageIndex) }
    }

    // ==========================================================
    // Gestures
    // ==========================================================

    /** The item whose text is drawn under [screenPoint], or `null`. */
    internal fun itemAt(screenPoint: Offset): Int? {
        for ((index, spot) in spots.withIndex().reversed()) {
            if (spot == null || spot.newPageAfter) continue
            val mm = controller.viewport.screenToPageMm(spot.pageIndex, screenPoint) ?: continue
            if (mm.x >= spot.box.left && mm.x <= spot.box.right && mm.y >= spot.box.top && mm.y <= spot.box.bottom) return index
        }
        return null
    }

    /**
     * Moves the text of item [index] so that its point [grab] (millimeters from its top-left
     * corner) is under [screenPoint], on the page there, kept inside it.
     */
    internal fun moveItem(index: Int, screenPoint: Offset, grab: Offset) {
        val spot = spots.getOrNull(index) ?: return
        val pageIndex = controller.viewport.findPageIndexAtScreen(screenPoint) ?: return
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        val mm = controller.viewport.screenToPageMm(pageIndex, screenPoint) ?: return
        val width = spot.box.width
        val height = spot.box.height
        val x = (mm.x - grab.x).coerceIn(0f, (page.widthMm - width).coerceAtLeast(0f))
        val y = (mm.y - grab.y).coerceIn(0f, (page.heightMm - height).coerceAtLeast(0f))
        spots = spots.toMutableList().also { it[index] = PlacementSpot(pageIndex, MmRect(x, y, x + width, y + height)) }
        if (request?.items?.size == 1) spotIndex = -1
        controller.requestRedraw()
    }

    /** The point of [screenPoint] in millimeters from the top-left corner of the text of item [index]. */
    internal fun grabOf(index: Int, screenPoint: Offset): Offset? {
        val spot = spots.getOrNull(index) ?: return null
        val mm = controller.viewport.screenToPageMm(spot.pageIndex, screenPoint) ?: return null
        return Offset(mm.x - spot.box.left, mm.y - spot.box.top)
    }

    /**
     * Where a single text would be placed for a tap at [screenPoint]: the page under the point and
     * the top-left corner of the text in its millimeters, kept inside the page; `null` outside of
     * the pages, or when several texts are placed.
     */
    internal fun targetAt(screenPoint: Offset): Pair<Int, Offset>? {
        val preview = request?.preview ?: return null
        val pageIndex = controller.viewport.findPageIndexAtScreen(screenPoint) ?: return null
        val page = controller.document.pages.getOrNull(pageIndex) ?: return null
        val mm = controller.viewport.screenToPageMm(pageIndex, screenPoint) ?: return null
        val x = mm.x.coerceIn(0f, (page.widthMm - preview.width).coerceAtLeast(0f))
        val y = mm.y.coerceIn(0f, (page.heightMm - preview.height).coerceAtLeast(0f))
        return pageIndex to Offset(x, y)
    }

    /**
     * A tap at [screenPoint]: on a text, confirms the placement; elsewhere on a page, puts a single
     * text there.
     *
     * @return `false` when nothing was placed (the placement stays pending).
     */
    internal fun placeAt(screenPoint: Offset): Boolean {
        val current = request ?: return false
        if (itemAt(screenPoint) != null) return confirm()
        val preview = current.preview ?: return false
        val (pageIndex, topLeft) = targetAt(screenPoint) ?: return false
        spots = listOf(PlacementSpot(pageIndex, MmRect(topLeft.x, topLeft.y, topLeft.x + preview.width, topLeft.y + preview.height)))
        return confirm()
    }
}
