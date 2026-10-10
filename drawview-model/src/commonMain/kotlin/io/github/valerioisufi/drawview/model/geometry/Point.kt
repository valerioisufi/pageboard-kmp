package io.github.valerioisufi.drawview.model.geometry

import kotlin.math.sqrt

/**
 * A point of a plane ([x] to the right, [y] down), in page millimeters unless said otherwise.
 */
data class Point(val x: Float, val y: Float) {
    operator fun plus(other: Point): Point = Point(x + other.x, y + other.y)

    operator fun minus(other: Point): Point = Point(x - other.x, y - other.y)

    operator fun times(factor: Float): Point = Point(x * factor, y * factor)

    /** The distance from the origin. */
    val distance: Float get() = sqrt(x * x + y * y)

    companion object {
        val Zero: Point = Point(0f, 0f)
    }
}
