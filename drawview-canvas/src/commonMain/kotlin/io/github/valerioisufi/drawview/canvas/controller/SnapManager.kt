package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.canvas.snap.SnapEngine
import io.github.valerioisufi.drawview.canvas.snap.SnapGuide
import io.github.valerioisufi.drawview.canvas.snap.SnapLine
import io.github.valerioisufi.drawview.canvas.snap.SnapTargets
import io.github.valerioisufi.drawview.canvas.snap.Snapped
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.geometry.Rect

/**
 * Snapping of the boxes being moved, resized or created: what they can be aligned with on their
 * page (the edges and the middles of the other elements, the middle of the page, the rows of the
 * paper), how near is near (a few dp on the screen, whatever the zoom), and the guides shown
 * meanwhile. On by default ([isEnabled]); a gesture made with ⌥ / Alt held is free.
 */
public class SnapManager(private val controller: DrawViewController) {

    /** Whether the boxes snap (a preference of the user). */
    public var isEnabled: Boolean by mutableStateOf(true)
        private set

    /** The guides of the box snapped now, in page millimeters of the page [guidesPageIndex]. */
    public var guides: List<SnapGuide> by mutableStateOf(emptyList())
        private set

    internal var guidesPageIndex: Int by mutableIntStateOf(-1)
        private set

    public fun updateEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        isEnabled = enabled
        controller.preferencesChanged()
    }

    internal fun applyPreferences(preferences: DrawPreferences) {
        isEnabled = preferences.isSnapEnabled
    }

    /**
     * How much [box] (page millimeters of the page [pageIndex]) moves to be aligned with the
     * elements of its page except [moved], and the middle of the page; [baseline] (a y of the
     * box) sits on the rows of the paper. Nothing when snapping is off or the gesture is [free].
     */
    internal fun snapBox(pageIndex: Int, box: Rect, moved: Collection<DrawElement>, baseline: Float? = null, free: Boolean = false): Point {
        val snapped = snap(pageIndex, moved, free) { targets, tolerance -> SnapEngine.snapBox(box, targets, tolerance, baseline) }
            ?: return Point.Zero
        return Point(snapped.dx, snapped.dy)
    }

    /**
     * How much the vertical edge at [x] (a side of a box from [top] to [bottom], being dragged)
     * moves to be aligned with the elements of the page [pageIndex] except [moved].
     */
    internal fun snapEdge(pageIndex: Int, x: Float, top: Float, bottom: Float, moved: Collection<DrawElement>, free: Boolean = false): Float =
        snap(pageIndex, moved, free) { targets, tolerance -> SnapEngine.snapEdge(x, top, bottom, targets, tolerance) }?.dx ?: 0f

    /**
     * The row of the paper of the page [pageIndex] a new text tapped at [y] writes on: the first
     * one at [y] or below it (the line under the tap), if the paper has rows and snapping is on.
     */
    internal fun rowFor(pageIndex: Int, y: Float, free: Boolean = false): Float? {
        if (!isEnabled || free) return null
        val page = controller.document.pages.getOrNull(pageIndex) ?: return null
        val pattern = page.background.pattern(page.widthMm, page.heightMm) ?: return null
        if (pattern.dots || pattern.rows.isEmpty()) return null
        return SnapEngine.rowAtOrBelow(y, pattern.rows, reach = pattern.spacingMm)
    }

    /** Hides the guides (the gesture is over). */
    internal fun clearGuides() {
        if (guides.isNotEmpty()) guides = emptyList()
        guidesPageIndex = -1
    }

    private inline fun snap(pageIndex: Int, moved: Collection<DrawElement>, free: Boolean, compute: (SnapTargets, Float) -> Snapped): Snapped? {
        val page = controller.document.pages.getOrNull(pageIndex)
        if (!isEnabled || free || page == null) {
            clearGuides()
            return null
        }
        val tolerance = TOLERANCE_DP * controller.viewport.density / controller.viewport.pixelsPerMm(pageIndex).coerceAtLeast(0.01f)
        val snapped = compute(targetsOf(page, moved), tolerance)
        guides = snapped.guides
        guidesPageIndex = pageIndex
        return snapped
    }

    private fun targetsOf(page: DrawPage, moved: Collection<DrawElement>): SnapTargets {
        val xs = mutableListOf(SnapLine(page.widthMm / 2f, 0f, page.heightMm))
        val ys = mutableListOf(SnapLine(page.heightMm / 2f, 0f, page.widthMm))
        val boxes = page.strokes.filter { it !in moved }.map { it.bounds } +
            page.texts.filter { it !in moved && !controller.hiddenElements.isShownByCollaborator(it.id) }.map { it.getRotatedBounds() } +
            page.images.filter { it !in moved }.map { it.getRotatedBounds() } +
            page.customElements.filter { it !in moved }.map { it.getRotatedBounds() }
        for (other in boxes) {
            for (x in listOf(other.left, other.center.x, other.right)) xs += SnapLine(x, other.top, other.bottom)
            for (y in listOf(other.top, other.center.y, other.bottom)) ys += SnapLine(y, other.left, other.right)
        }
        val pattern = page.background.pattern(page.widthMm, page.heightMm)
        val rows = if (pattern != null && !pattern.dots) pattern.rows else FloatArray(0)
        return SnapTargets(xs, ys, rows)
    }

    private companion object {
        /** How near a line is near enough to snap to it, on the screen. */
        const val TOLERANCE_DP = 6f
    }
}
