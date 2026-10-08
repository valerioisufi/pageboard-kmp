package io.github.valerioisufi.drawview.core.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.valerioisufi.drawview.core.model.DrawText

/**
 * A text prepared by the application (e.g. a note of an assistant) waiting to be placed by the
 * student: the next tap on a page places it there.
 *
 * @param preview the text to place: its content and size ([DrawText.width], [DrawText.height] in
 *   page millimeters) are drawn under the pointer while the placement is pending.
 * @param onPlace called with the page and the top-left corner (page millimeters) chosen by the
 *   student, already kept inside the page.
 * @param onCancel called when the placement is cancelled (Escape, another request).
 */
class PlacementRequest(
    val preview: DrawText,
    val onPlace: (pageIndex: Int, xMm: Float, yMm: Float) -> Unit,
    val onCancel: () -> Unit = {}
)

/**
 * Pending placement of a [PlacementRequest]: while it is active, a tap (mouse click, stylus or
 * finger tap) on a page places the element instead of using the active tool, and the other
 * gestures (drags, pinches, wheel, trackpad) still move the canvas, so that any page can be
 * reached. The canvas draws the preview under the pointer (mouse or stylus hover).
 */
class PlacementManager(private val controller: DrawViewController) {

    var request: PlacementRequest? by mutableStateOf(null)
        private set

    /** Screen position of the pointer while a placement is pending (`null`: outside the canvas). */
    var pointer: Offset? by mutableStateOf(null)
        internal set

    val isActive: Boolean get() = request != null

    /** Starts placing [newRequest] (cancelling the pending one). */
    fun start(newRequest: PlacementRequest) {
        cancel()
        controller.selectionManager.clearSelection()
        if (controller.textEditorManager.isEditing) controller.textEditorManager.finishEditing()
        controller.contextMenuPosition = null
        controller.isPageGridVisible = false
        request = newRequest
        controller.requestRedraw()
    }

    fun cancel() {
        val current = request ?: return
        request = null
        pointer = null
        controller.requestRedraw()
        current.onCancel()
    }

    /**
     * Where the element would be placed for a tap at [screenPoint]: the page under the point and
     * the top-left corner of the element in its millimeters, kept inside the page; `null` outside
     * of the pages.
     */
    fun targetAt(screenPoint: Offset): Pair<Int, Offset>? {
        val preview = request?.preview ?: return null
        val pageIndex = controller.findPageIndexAtScreen(screenPoint) ?: return null
        val page = controller.document.pages.getOrNull(pageIndex) ?: return null
        val mm = controller.screenToPageMm(pageIndex, screenPoint) ?: return null
        val x = mm.x.coerceIn(0f, (page.widthMm - preview.width).coerceAtLeast(0f))
        val y = mm.y.coerceIn(0f, (page.heightMm - preview.height).coerceAtLeast(0f))
        return pageIndex to Offset(x, y)
    }

    /**
     * Places the pending element at [screenPoint].
     *
     * @return `false` when the point is outside of the pages (the placement stays pending).
     */
    internal fun placeAt(screenPoint: Offset): Boolean {
        val current = request ?: return false
        val (pageIndex, topLeft) = targetAt(screenPoint) ?: return false
        request = null
        pointer = null
        controller.requestRedraw()
        current.onPlace(pageIndex, topLeft.x, topLeft.y)
        return true
    }
}
