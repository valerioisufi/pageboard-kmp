package io.github.valerioisufi.pageboard.model.geometry

import kotlin.math.sqrt

/**
 * A point of a plane ([x] to the right, [y] down), in page millimeters unless said otherwise.
 */
public data class Point(val x: Float, val y: Float) {
    public operator fun plus(other: Point): Point = Point(x + other.x, y + other.y)

    public operator fun minus(other: Point): Point = Point(x - other.x, y - other.y)

    public operator fun times(factor: Float): Point = Point(x * factor, y * factor)

    /** The distance from the origin. */
    val distance: Float get() = sqrt(x * x + y * y)

    public companion object {
        public val Zero: Point = Point(0f, 0f)
    }
}
