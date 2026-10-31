package io.github.valerioisufi.drawview.model.shape

import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.geometry.Point
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A geometric shape a stroke was recognized as (see [ShapeRecognizer]), in page millimeters: it
 * is drawn as a stroke through its [path] (see [strokeInputs]).
 */
public sealed interface Shape {
    /**
     * The polyline that draws the shape, from its first point to its last; a closed shape ends at
     * its first point.
     */
    public val path: List<Point>

    /** Whether the points of [path] are corners, where the pen turns, rather than samples of a curve. */
    public val hasCorners: Boolean get() = true
}

/** A straight segment from [start] to [end]. */
public data class Line(val start: Point, val end: Point) : Shape {
    override val path: List<Point> get() = listOf(start, end)
}

/**
 * A segment from [tail] to [tip] with a head at [tip]: two wings [headLength] long, at
 * [HEAD_ANGLE] from the shaft.
 */
public data class Arrow(val tail: Point, val tip: Point, val headLength: Float) : Shape {
    /** The ends of the two wings of the head. */
    public val wings: Pair<Point, Point>
        get() {
            val shaft = tip - tail
            val length = shaft.distance
            val back = if (length > 0f) shaft * (-headLength / length) else Point.Zero
            return back.rotated(HEAD_ANGLE) + tip to back.rotated(-HEAD_ANGLE) + tip
        }

    /** The shaft, then one wing and back to the tip, then the other wing. */
    override val path: List<Point>
        get() {
            val (left, right) = wings
            return listOf(tail, tip, left, tip, right)
        }

    public companion object {
        /** The angle between the shaft and each wing of the head, in radians (28°). */
        public const val HEAD_ANGLE: Float = (28.0 * PI / 180.0).toFloat()
    }
}

/** Straight segments joined at [vertices] (three or more), open. */
public data class Polyline(val vertices: List<Point>) : Shape {
    override val path: List<Point> get() = vertices
}

/**
 * A closed polygon through [vertices] (three or more, the first one not repeated): a triangle, a
 * rectangle, a square, a polygon of five or six sides.
 */
public data class Polygon(val vertices: List<Point>) : Shape {
    override val path: List<Point> get() = vertices + vertices.first()
}

/**
 * An ellipse of [center], with the radius [radiusX] along the direction [rotation] (radians, from
 * the x axis towards the y axis) and [radiusY] across it: a circle when they are equal.
 */
public data class Ellipse(val center: Point, val radiusX: Float, val radiusY: Float, val rotation: Float = 0f) : Shape {
    public val isCircle: Boolean get() = radiusX == radiusY

    /** Samples a fraction of a millimeter apart (64 at least), from the end of the axis [radiusX]. */
    override val path: List<Point>
        get() {
            val perimeter = PI * (3 * (radiusX + radiusY) - sqrt((3 * radiusX + radiusY) * (radiusX + 3 * radiusY).toDouble()))
            val count = ceil(perimeter / ELLIPSE_SAMPLE_MM).toInt().coerceIn(64, 1024)
            return (0..count).map { i ->
                val angle = 2.0 * PI * (i % count) / count
                Point((cos(angle) * radiusX).toFloat(), (sin(angle) * radiusY).toFloat()).rotated(rotation) + center
            }
        }

    override val hasCorners: Boolean get() = false
}

/** The distance between two inputs of the stroke of a shape along its sides, in millimeters. */
public const val SHAPE_INPUT_SPACING_MM: Float = 0.5f

private const val ELLIPSE_SAMPLE_MM = 0.5

/** The time between two inputs of the stroke of a shape, in milliseconds (a steady hand). */
private const val INPUT_INTERVAL_MS = 6L

/** How long the pen stays on a corner, in milliseconds, and in how many inputs. */
private const val CORNER_PAUSE_MS = 60L
private const val CORNER_PAUSE_INPUTS = 6

/**
 * The inputs of a stroke that draws this shape in place of the stroke of the inputs [drawn]: a
 * point every [spacing] millimeters along the [Shape.path], a short stop on each corner so that
 * the ink keeps it sharp, all at the median pressure of [drawn] (the width the user saw), at a
 * steady pace from the time [drawn] started.
 */
public fun Shape.strokeInputs(drawn: List<StrokePoint>, spacing: Float = SHAPE_INPUT_SPACING_MM): List<StrokePoint> {
    val pressure = drawn.map { it.pressure }.filter { it >= 0f }.sorted().let { if (it.isEmpty()) -1f else it[it.size / 2] }
    var time = drawn.firstOrNull()?.timestampMs ?: 0L
    val path = path
    val inputs = ArrayList<StrokePoint>()
    fun add(point: Point) {
        inputs += StrokePoint(point.x, point.y, pressure, time)
        time += INPUT_INTERVAL_MS
    }
    fun pause(point: Point) {
        repeat(CORNER_PAUSE_INPUTS) {
            inputs += StrokePoint(point.x, point.y, pressure, time)
            time += CORNER_PAUSE_MS / CORNER_PAUSE_INPUTS
        }
    }

    add(path.first())
    for (i in 1 until path.size) {
        val from = path[i - 1]
        val to = path[i]
        val steps = max(1, ceil((to - from).distance / spacing).toInt())
        for (step in 1..steps) add(from + (to - from) * (step.toFloat() / steps))
        if (hasCorners && i < path.lastIndex) pause(to)
    }
    return inputs
}

/** This vector turned by [angle] radians (from the x axis towards the y axis). */
internal fun Point.rotated(angle: Float): Point {
    val c = cos(angle)
    val s = sin(angle)
    return Point(x * c - y * s, x * s + y * c)
}
