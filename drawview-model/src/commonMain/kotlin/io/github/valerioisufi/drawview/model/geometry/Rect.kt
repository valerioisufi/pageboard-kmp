package io.github.valerioisufi.drawview.model.geometry

import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle with its sides on the axes, in page millimeters unless said otherwise: from
 * ([left], [top]) included to ([right], [bottom]) excluded, empty when it has no area.
 */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val topLeft: Point get() = Point(left, top)
    val bottomRight: Point get() = Point(right, bottom)
    val center: Point get() = Point((left + right) / 2f, (top + bottom) / 2f)

    /** Whether it has no area. */
    val isEmpty: Boolean get() = left >= right || top >= bottom

    /** Whether it shares an area with [other]. */
    fun overlaps(other: Rect): Boolean =
        right > other.left && other.right > left && bottom > other.top && other.bottom > top

    /** Whether [point] is inside (its left and top sides included, the others excluded). */
    operator fun contains(point: Point): Boolean = point.x >= left && point.x < right && point.y >= top && point.y < bottom

    /** The area shared with [other] (empty, possibly inverted, when there is none). */
    fun intersect(other: Rect): Rect = Rect(max(left, other.left), max(top, other.top), min(right, other.right), min(bottom, other.bottom))

    /** The smallest rectangle holding this one and [other]. */
    fun union(other: Rect): Rect = Rect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    fun translate(dx: Float, dy: Float): Rect = Rect(left + dx, top + dy, right + dx, bottom + dy)

    /** Grown by [delta] on every side (shrunk when negative). */
    fun inflate(delta: Float): Rect = Rect(left - delta, top - delta, right + delta, bottom + delta)

    companion object {
        val Zero: Rect = Rect(0f, 0f, 0f, 0f)
    }
}
