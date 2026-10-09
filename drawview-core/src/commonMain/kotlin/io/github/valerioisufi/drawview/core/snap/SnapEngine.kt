package io.github.valerioisufi.drawview.core.snap

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A line a box can be aligned with, in page millimeters: at [position] across x (a vertical line)
 * or y (a horizontal one), drawn as a guide from [from] to [to] along the other axis.
 */
class SnapLine(val position: Float, val from: Float, val to: Float)

/**
 * What a box can be aligned with on its page: the vertical lines [xs] and the horizontal ones
 * [ys] (the edges and the middles of the other elements, the middle of the page), and the [rows]
 * of its paper, where the first baseline of a text sits.
 */
class SnapTargets(val xs: List<SnapLine>, val ys: List<SnapLine>, val rows: FloatArray = FloatArray(0))

/**
 * A line the box is aligned with, shown while it is (page millimeters): vertical at x =
 * [position] ([vertical]) or horizontal at y = [position], from [from] to [to].
 */
data class SnapGuide(val vertical: Boolean, val position: Float, val from: Float, val to: Float)

/** How much a box is moved to be aligned ([dx], [dy] in page millimeters), and the [guides] shown. */
class Snapped(val dx: Float, val dy: Float, val guides: List<SnapGuide>) {
    companion object {
        val None = Snapped(0f, 0f, emptyList())
    }
}

/**
 * Aligns the boxes being moved, resized or placed with what is around them: the nearest line
 * within the tolerance, on each axis; its edges, its middle (and the first baseline of a text, on
 * the rows of the paper).
 */
object SnapEngine {

    /**
     * Aligns [box] with [targets]: its left edge, middle or right edge with a vertical line; its
     * top, middle or bottom with a horizontal line, or [baseline] (a y of the box, the first
     * baseline of a text) with a row of the paper; each when within [tolerance].
     */
    fun snapBox(box: Rect, targets: SnapTargets, tolerance: Float, baseline: Float? = null): Snapped {
        val x = nearest(listOf(box.left, box.center.x, box.right), targets.xs, tolerance)
        var y = nearest(listOf(box.top, box.center.y, box.bottom), targets.ys, tolerance)
        var onRow = false
        if (baseline != null) {
            val row = targets.rows.minByOrNull { abs(it - baseline) }
            if (row != null && abs(row - baseline) <= tolerance && (y == null || abs(row - baseline) < abs(y.delta))) {
                y = Match(row - baseline, null)
                onRow = true
            }
        }
        val dx = x?.delta ?: 0f
        val dy = y?.delta ?: 0f
        val moved = box.translate(dx, dy)
        val guides = buildList {
            x?.line?.let { add(SnapGuide(vertical = true, it.position, min(it.from, moved.top), max(it.to, moved.bottom))) }
            // A row of the paper shows itself
            if (!onRow) y?.line?.let { add(SnapGuide(vertical = false, it.position, min(it.from, moved.left), max(it.to, moved.right))) }
        }
        return Snapped(dx, dy, guides)
    }

    /**
     * Aligns the vertical edge at [x] (a side being dragged) with [targets]; how much it moves
     * and its guide, from [top] to [bottom] of the box.
     */
    fun snapEdge(x: Float, top: Float, bottom: Float, targets: SnapTargets, tolerance: Float): Snapped {
        val match = nearest(listOf(x), targets.xs, tolerance) ?: return Snapped.None
        val line = match.line ?: return Snapped(match.delta, 0f, emptyList())
        return Snapped(match.delta, 0f, listOf(SnapGuide(vertical = true, line.position, min(line.from, top), max(line.to, bottom))))
    }

    /** The first row of [rows] at [y] or below it, if any within [reach]. */
    fun rowAtOrBelow(y: Float, rows: FloatArray, reach: Float): Float? = rows.filter { it >= y && it - y <= reach }.minOrNull()

    private class Match(val delta: Float, val line: SnapLine?)

    /** The line nearest to one of [anchors] within [tolerance], and how far the anchor moves to it. */
    private fun nearest(anchors: List<Float>, lines: List<SnapLine>, tolerance: Float): Match? {
        var best: Match? = null
        for (anchor in anchors) {
            for (line in lines) {
                val delta = line.position - anchor
                if (abs(delta) <= tolerance && (best == null || abs(delta) < abs(best.delta))) best = Match(delta, line)
            }
        }
        return best
    }
}
