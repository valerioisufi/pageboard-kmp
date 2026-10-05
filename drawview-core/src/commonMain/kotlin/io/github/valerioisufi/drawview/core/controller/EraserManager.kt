package io.github.valerioisufi.drawview.core.controller

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.core.geometry.HitTesting
import io.github.valerioisufi.drawview.core.history.BatchAction
import io.github.valerioisufi.drawview.core.history.EraseStrokesAction
import io.github.valerioisufi.drawview.core.history.EraseTextMarkupsAction
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup
import io.github.valerioisufi.drawview.core.model.Tool
import kotlin.math.max
import kotlin.math.min

/**
 * "Live eraser": removes the strokes whose mesh intersects the capsule swept by the eraser tip
 * (and the marks of the text of the PDFs it touches), and records a single history action per
 * gesture.
 */
class EraserManager(private val controller: DrawViewController) {

    private class ErasedStroke(val stroke: DrawStroke, val position: Int)

    private val erasedInGesture = LinkedHashMap<DrawPage, MutableList<ErasedStroke>>()

    /** Marks of the text of the PDFs erased during the gesture, with their position in their page. */
    private val erasedMarkupsInGesture = LinkedHashMap<DrawPage, MutableList<Pair<DrawTextMarkup, Int>>>()

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

        for (visible in controller.visiblePages()) {
            val page = controller.document.pages.getOrNull(visible.index) ?: continue
            val pxPerMm = visible.rect.width / page.widthMm.coerceAtLeast(0.01f)
            val radiusMm = diameterMm / 2f
            val radiusPx = radiusMm * pxPerMm
            if (!visible.rect.inflate(radiusPx).overlaps(segmentScreenBox.inflate(1f))) continue

            val a = controller.screenToPageMm(visible.index, fromScreen) ?: continue
            val b = controller.screenToPageMm(visible.index, toScreen) ?: continue
            val capsule = Rect(
                min(a.x, b.x) - radiusMm, min(a.y, b.y) - radiusMm,
                max(a.x, b.x) + radiusMm, max(a.y, b.y) + radiusMm
            )

            val toRemove = mutableListOf<DrawStroke>()
            for (stroke in page.strokes) {
                if (stroke.isDragging) continue
                val sb = stroke.bounds
                if (sb.left >= capsule.right || capsule.left >= sb.right || sb.top >= capsule.bottom || capsule.top >= sb.bottom) continue
                if (HitTesting.meshIntersectsCapsule(stroke.mesh, a.x, a.y, b.x, b.y, radiusMm)) {
                    toRemove.add(stroke)
                }
            }
            val markupsToRemove = page.markups.filter { markup ->
                val pointsToMm = markup.pointsToPageMm(page)
                markup.rects.any { HitTesting.rectIntersectsCapsule(pointsToMm.mapRect(it), a.x, a.y, b.x, b.y, radiusMm) }
            }
            if (toRemove.isEmpty() && markupsToRemove.isEmpty()) continue

            var dirty: Rect? = null
            if (toRemove.isNotEmpty()) {
                val records = erasedInGesture.getOrPut(page) { mutableListOf() }
                for (stroke in toRemove) {
                    val position = page.strokes.indexOf(stroke)
                    if (position < 0) continue
                    page.strokes.removeAt(position)
                    records.add(ErasedStroke(stroke, position))
                    dirty = dirty?.let { union(it, stroke.bounds) } ?: stroke.bounds
                    erased++
                }
                controller.persist { deleteStrokes(toRemove) }
            }
            if (markupsToRemove.isNotEmpty()) {
                val records = erasedMarkupsInGesture.getOrPut(page) { mutableListOf() }
                for (markup in markupsToRemove) {
                    val position = page.markups.indexOf(markup)
                    if (position < 0) continue
                    page.markups.removeAt(position)
                    records.add(markup to position)
                    val bounds = markup.boundsMm(page)
                    dirty = dirty?.let { union(it, bounds) } ?: bounds
                    erased++
                }
                controller.persist { markupsToRemove.forEach { deleteTextMarkup(it) } }
            }
            page.markContentChanged()
            dirty?.let { controller.invalidatePageContent(page, it) }
        }
        return erased
    }

    /**
     * Commits the strokes (and the marks) erased during the current gesture as one undoable action.
     */
    fun commitGesture() {
        if (erasedInGesture.isEmpty() && erasedMarkupsInGesture.isEmpty()) return
        val actions = erasedInGesture.map { (page, records) ->
            EraseStrokesAction(page, records.map { it.stroke }, records.map { it.position })
        } + erasedMarkupsInGesture.map { (page, records) ->
            EraseTextMarkupsAction(page, records.map { it.first }, records.map { it.second })
        }
        erasedInGesture.clear()
        erasedMarkupsInGesture.clear()
        controller.historyManager.addHistoryAction(if (actions.size == 1) actions[0] else BatchAction(actions))
    }

    private fun union(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
}
