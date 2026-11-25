package io.github.valerioisufi.pageboard.canvas.controller

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.model.PageElement
import io.github.valerioisufi.pageboard.model.change.RemoveElements
import io.github.valerioisufi.pageboard.model.geometry.HitTesting
import io.github.valerioisufi.pageboard.model.geometry.Point
import kotlin.math.max
import kotlin.math.min

/**
 * "Live eraser": removes the strokes whose mesh intersects the capsule swept by the eraser tip
 * (and the marks of the text of the PDFs and the elements whose type says it erases them, the
 * shapes, it touches) as soon as it touches them; what a gesture
 * erases is undone at once.
 */
internal class EraserManager(private val controller: PageboardController) {

    /** A gesture is erasing: its changes are a group of the history until [commitGesture]. */
    private var inGesture = false

    /**
     * Erases along the segment [fromScreen] - [toScreen] (screen pixels).
     *
     * @return the number of strokes and marks removed.
     */
    fun eraseAlongSegment(fromScreen: Offset, toScreen: Offset): Int {
        val diameterMm = controller.toolManager.activePreset(Tool.ERASER)?.sizeMm ?: 8f
        var erased = 0
        val segmentScreenBox = Rect(
            min(fromScreen.x, toScreen.x), min(fromScreen.y, toScreen.y),
            max(fromScreen.x, toScreen.x), max(fromScreen.y, toScreen.y)
        )

        for (visible in controller.viewport.visiblePages()) {
            val page = controller.document.pages.getOrNull(visible.index) ?: continue
            val pxPerMm = visible.rect.width / page.widthMm.coerceAtLeast(0.01f)
            val radiusMm = diameterMm / 2f
            val radiusPx = radiusMm * pxPerMm
            if (!visible.rect.inflate(radiusPx).overlaps(segmentScreenBox.inflate(1f))) continue

            val a = controller.viewport.screenToPageMm(visible.index, fromScreen) ?: continue
            val b = controller.viewport.screenToPageMm(visible.index, toScreen) ?: continue
            val capsule = Rect(
                min(a.x, b.x) - radiusMm, min(a.y, b.y) - radiusMm,
                max(a.x, b.x) + radiusMm, max(a.y, b.y) + radiusMm
            )

            val toRemove = mutableListOf<PageElement>()
            for (stroke in page.strokes) {
                if (controller.hiddenElements.isHidden(stroke.id)) continue
                if (!page.isElementVisible(stroke) || page.isElementLocked(stroke)) continue
                val sb = stroke.bounds
                if (sb.left >= capsule.right || capsule.left >= sb.right || sb.top >= capsule.bottom || capsule.top >= sb.bottom) continue
                if (HitTesting.meshIntersectsCapsule(stroke.mesh, a.x, a.y, b.x, b.y, radiusMm)) {
                    toRemove.add(stroke)
                }
            }
            val elements = controller.engines.elements
            for (custom in page.customElements) {
                if (controller.hiddenElements.isHidden(custom.id)) continue
                if (!page.isElementVisible(custom) || page.isElementLocked(custom)) continue
                val cb = custom.getRotatedBounds()
                if (cb.left >= capsule.right || capsule.left >= cb.right || cb.top >= capsule.bottom || capsule.top >= cb.bottom) continue
                if (elements.erasedBy(custom, Point(a.x, a.y), Point(b.x, b.y), radiusMm)) toRemove.add(custom)
            }
            val markupsToRemove = page.markups.filter { markup ->
                if (!page.isElementVisible(markup) || page.isElementLocked(markup)) return@filter false
                val pointsToMm = markup.pointsToPageMm(page)
                markup.rects.any { HitTesting.rectIntersectsCapsule(pointsToMm.mapRect(it), a.x, a.y, b.x, b.y, radiusMm) }
            }
            toRemove.addAll(markupsToRemove)
            if (toRemove.isEmpty()) continue

            if (!inGesture) {
                inGesture = true
                controller.historyManager.beginGroup()
            }
            controller.session.submit(RemoveElements.of(page, toRemove))
            erased += toRemove.size
        }
        return erased
    }

    /**
     * Ends the gesture: what it erased becomes one entry of the history.
     */
    fun commitGesture() {
        if (!inGesture) return
        inGesture = false
        controller.historyManager.endGroup()
    }

    /**
     * Cancels the gesture: whatever it erased is undone.
     */
    fun cancelGesture() {
        if (!inGesture) return
        inGesture = false
        controller.historyManager.endGroup()
        controller.historyManager.undo()
    }
}
