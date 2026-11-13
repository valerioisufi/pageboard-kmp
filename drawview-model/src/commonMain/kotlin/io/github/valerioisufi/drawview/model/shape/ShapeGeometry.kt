package io.github.valerioisufi.drawview.model.shape

import io.github.valerioisufi.drawview.model.geometry.Point
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A part of the outline of a shape, in millimeters of its box: a polyline through [points],
 * [closed] when its last point joins its first one; [filled] when its inside is painted (with the
 * fill of the shape, or with its outline color for a [solid] part such as a filled head).
 */
public class ShapePath(
    public val points: List<Point>,
    public val closed: Boolean,
    public val filled: Boolean = false,
    /** Whether it is painted with the color of the outline, without dashes (a filled head). */
    public val solid: Boolean = false
)

/**
 * The box of a shape on its page, in millimeters: its top-left corner before its rotation,
 * its size and its rotation in degrees around its center (as `RotatableItem`).
 */
public data class ShapeBox(val x: Float, val y: Float, val width: Float, val height: Float, val rotation: Float = 0f) {
    val center: Point get() = Point(x + width / 2f, y + height / 2f)

    /** The point [local] of the box (from its top-left corner before its rotation) on the page. */
    public fun toPage(local: Point): Point {
        val rad = rotation * PI.toFloat() / 180f
        val offset = Point(local.x - width / 2f, local.y - height / 2f).rotated(rad)
        return center + offset
    }

    /** The point [page] of the page in the box (from its top-left corner before its rotation). */
    public fun toLocal(page: Point): Point {
        val rad = rotation * PI.toFloat() / 180f
        val offset = (page - center).rotated(-rad)
        return Point(offset.x + width / 2f, offset.y + height / 2f)
    }
}

/**
 * The geometry of the shapes ([ShapeElement]) in their box: the paths that draw them, what a
 * point hits, their anchors (where the ends of a line snap) and the box of a line between two
 * points. One geometry for the renderer, the export, the hits and the snaps.
 */
public object ShapeGeometry {
    /** The head of a line: its length is this many times the width of the line, [MIN_HEAD_MM] at least. */
    private const val HEAD_PER_WIDTH = 4.5f
    private const val MIN_HEAD_MM = 2.5f

    /** The head takes at most this part of the line. */
    private const val MAX_HEAD_PART = 0.45f

    /** The angle between the shaft and each side of a head (radians). */
    private const val HEAD_ANGLE = (28.0 * PI / 180.0).toFloat()

    /** The radius of the corners of a rounded rectangle: a part of its shorter side, at most [MAX_CORNER_MM]. */
    private const val CORNER_PART = 0.2f
    private const val MAX_CORNER_MM = 6f

    /** How far a curve may be from its polyline, in millimeters. */
    private const val CURVE_TOLERANCE_MM = 0.005f
    private const val MAX_CURVE_POINTS = 720

    /** The room around a line in its box, in millimeters (so that it can be hit). */
    private const val LINE_MARGIN_MM = 1f

    /** The length of the head of a line of the style [value] (before it is bounded by the line). */
    public fun headLength(value: ShapeElement): Float = max(MIN_HEAD_MM, value.widthMm * HEAD_PER_WIDTH)

    /**
     * The height of the box of a line of the style [value]: its width, its heads and some room,
     * so that everything it draws is inside its box.
     */
    public fun lineHeight(value: ShapeElement): Float {
        val heads = value.startHead != ShapeHead.NONE || value.endHead != ShapeHead.NONE
        val across = if (heads) 2f * headLength(value) * sin(HEAD_ANGLE) + value.widthMm else value.widthMm
        return across + 2f * LINE_MARGIN_MM
    }

    /** The box of a line of the style [value] from [start] to [end] on the page. */
    public fun lineBox(start: Point, end: Point, value: ShapeElement): ShapeBox {
        val length = (end - start).distance
        val height = lineHeight(value)
        val angle = if (length > 0f) atan2(end.y - start.y, end.x - start.x) * 180f / PI.toFloat() else 0f
        val center = (start + end) * 0.5f
        return ShapeBox(center.x - length / 2f, center.y - height / 2f, length, height, angle)
    }

    /** The two ends of a line in its [box], on the page. */
    public fun lineEnds(box: ShapeBox): Pair<Point, Point> =
        box.toPage(Point(0f, box.height / 2f)) to box.toPage(Point(box.width, box.height / 2f))

    /** The paths that draw [value] in a box of [width] x [height] millimeters. */
    public fun paths(value: ShapeElement, width: Float, height: Float): List<ShapePath> {
        if (value.kind.isLine) return linePaths(value, width, height)
        // The outline inside the box: its outer edge on the edge of the box
        val inset = min(value.widthMm / 2f, min(width, height) / 2f)
        val left = inset
        val top = inset
        val right = width - inset
        val bottom = height - inset
        val filled = value.fill != null
        val points = when (value.kind) {
            ShapeKind.RECTANGLE -> listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))
            ShapeKind.ROUNDED_RECTANGLE -> roundedRectangle(left, top, right, bottom)
            ShapeKind.ELLIPSE -> ellipse(left, top, right, bottom)
            ShapeKind.TRIANGLE -> listOf(Point((left + right) / 2f, top), Point(right, bottom), Point(left, bottom))
            ShapeKind.RIGHT_TRIANGLE -> listOf(Point(left, top), Point(right, bottom), Point(left, bottom))
            ShapeKind.DIAMOND -> listOf(
                Point((left + right) / 2f, top), Point(right, (top + bottom) / 2f),
                Point((left + right) / 2f, bottom), Point(left, (top + bottom) / 2f)
            )
            ShapeKind.PENTAGON -> regular(5, -PI / 2, left, top, right, bottom)
            ShapeKind.HEXAGON -> regular(6, 0.0, left, top, right, bottom)
            ShapeKind.LINE -> error("a line")
        }
        return listOf(ShapePath(points, closed = true, filled = filled))
    }

    private fun linePaths(value: ShapeElement, width: Float, height: Float): List<ShapePath> {
        val middle = height / 2f
        val start = Point(0f, middle)
        val end = Point(width, middle)
        val head = min(headLength(value), width * MAX_HEAD_PART)
        val paths = ArrayList<ShapePath>(3)
        // A filled head ends the shaft at its base, so that the shaft does not show past its tip
        var shaftStart = start
        var shaftEnd = end
        if (head > 0f) {
            if (value.startHead == ShapeHead.FILLED) shaftStart = Point(head * cos(HEAD_ANGLE), middle)
            if (value.endHead == ShapeHead.FILLED) shaftEnd = Point(width - head * cos(HEAD_ANGLE), middle)
        }
        paths += ShapePath(listOf(shaftStart, shaftEnd), closed = false)
        if (head > 0f) {
            headPath(value.startHead, start, Point(1f, 0f), head)?.let(paths::add)
            headPath(value.endHead, end, Point(-1f, 0f), head)?.let(paths::add)
        }
        return paths
    }

    /** The head [kind] at [tip], its wings going back along [back] (a unit vector). */
    private fun headPath(kind: ShapeHead, tip: Point, back: Point, length: Float): ShapePath? {
        if (kind == ShapeHead.NONE) return null
        val left = tip + back.rotated(HEAD_ANGLE) * length
        val right = tip + back.rotated(-HEAD_ANGLE) * length
        return when (kind) {
            ShapeHead.OPEN -> ShapePath(listOf(left, tip, right), closed = false)
            ShapeHead.FILLED -> ShapePath(listOf(left, tip, right), closed = true, filled = true, solid = true)
            ShapeHead.NONE -> null
        }
    }

    private fun roundedRectangle(left: Float, top: Float, right: Float, bottom: Float): List<Point> {
        val radius = min(min(right - left, bottom - top) * CORNER_PART, MAX_CORNER_MM)
        if (radius <= 0f) return listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))
        val steps = arcSteps(radius, PI / 2)
        val points = ArrayList<Point>(4 * (steps + 1))
        fun arc(cx: Float, cy: Float, from: Double) {
            for (i in 0..steps) {
                val angle = from + PI / 2 * i / steps
                points += Point(cx + (cos(angle) * radius).toFloat(), cy + (sin(angle) * radius).toFloat())
            }
        }
        arc(right - radius, top + radius, -PI / 2)
        arc(right - radius, bottom - radius, 0.0)
        arc(left + radius, bottom - radius, PI / 2)
        arc(left + radius, top + radius, PI)
        return points
    }

    private fun ellipse(left: Float, top: Float, right: Float, bottom: Float): List<Point> {
        val rx = (right - left) / 2f
        val ry = (bottom - top) / 2f
        val cx = left + rx
        val cy = top + ry
        val steps = arcSteps(max(rx, ry), 2 * PI)
        return List(steps) { i ->
            val angle = 2 * PI * i / steps
            Point(cx + (cos(angle) * rx).toFloat(), cy + (sin(angle) * ry).toFloat())
        }
    }

    /** The number of segments of an arc of [radius] over [sweep] radians within [CURVE_TOLERANCE_MM]. */
    private fun arcSteps(radius: Float, sweep: Double): Int {
        if (radius <= CURVE_TOLERANCE_MM) return 4
        val step = 2.0 * acos(1.0 - CURVE_TOLERANCE_MM / radius)
        val full = (ceil(sweep / step).toInt()).coerceAtLeast(4)
        return min(full, (MAX_CURVE_POINTS * sweep / (2 * PI)).toInt().coerceAtLeast(4))
    }

    /** A regular polygon of [sides] from the angle [first], stretched to the rectangle. */
    private fun regular(sides: Int, first: Double, left: Float, top: Float, right: Float, bottom: Float): List<Point> {
        val unit = List(sides) { i ->
            val angle = first + 2 * PI * i / sides
            Point(cos(angle).toFloat(), sin(angle).toFloat())
        }
        val minX = unit.minOf { it.x }
        val maxX = unit.maxOf { it.x }
        val minY = unit.minOf { it.y }
        val maxY = unit.maxOf { it.y }
        return unit.map {
            Point(left + (it.x - minX) / (maxX - minX) * (right - left), top + (it.y - minY) / (maxY - minY) * (bottom - top))
        }
    }

    /**
     * Whether the point [local] of the box hits [value] in a box of [width] x [height]: within
     * [tolerance] millimeters of its outline, or inside it when it is filled.
     */
    public fun hits(value: ShapeElement, local: Point, width: Float, height: Float, tolerance: Float): Boolean {
        val reach = value.widthMm / 2f + tolerance
        for (path in paths(value, width, height)) {
            if (path.filled && path.closed && inside(local, path.points)) return true
            if (distanceTo(local, path.points, path.closed) <= reach) return true
        }
        return false
    }

    /**
     * The anchors of [value] in a box of [width] x [height] millimeters, where the ends of a line
     * snap: the corners and the middles of the sides of a shape of a box and its center, the ends
     * and the middle of a line.
     */
    public fun anchors(value: ShapeElement, width: Float, height: Float): List<Point> {
        val middle = Point(width / 2f, height / 2f)
        if (value.kind.isLine) return listOf(Point(0f, height / 2f), middle, Point(width, height / 2f))
        val outline = when (value.kind) {
            // The extremes of the curves: the middles of the sides of the box
            ShapeKind.ELLIPSE, ShapeKind.ROUNDED_RECTANGLE -> null
            else -> paths(value.copy(widthMm = ShapeElement.MIN_WIDTH_MM), width, height).first().points
        }
        val sides = listOf(Point(width / 2f, 0f), Point(width, height / 2f), Point(width / 2f, height), Point(0f, height / 2f))
        if (outline == null) return sides + middle
        val corners = outline
        val middles = corners.indices.map { i -> (corners[i] + corners[(i + 1) % corners.size]) * 0.5f }
        return corners + middles + middle
    }

    /** The distance from [point] to the polyline [points] (joined back to its start when [closed]). */
    internal fun distanceTo(point: Point, points: List<Point>, closed: Boolean): Float {
        if (points.isEmpty()) return Float.POSITIVE_INFINITY
        if (points.size == 1) return (point - points[0]).distance
        var best = Float.POSITIVE_INFINITY
        val last = if (closed) points.size else points.size - 1
        for (i in 0 until last) {
            best = min(best, segmentDistance(point, points[i], points[(i + 1) % points.size]))
        }
        return best
    }

    private fun segmentDistance(p: Point, a: Point, b: Point): Float {
        val ab = b - a
        val lengthSquared = ab.x * ab.x + ab.y * ab.y
        if (lengthSquared == 0f) return (p - a).distance
        val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / lengthSquared).coerceIn(0f, 1f)
        return (p - (a + ab * t)).distance
    }

    /** Whether [point] is inside the closed polygon [points] (even-odd). */
    internal fun inside(point: Point, points: List<Point>): Boolean {
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            if ((a.y > point.y) != (b.y > point.y)) {
                val x = (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
                if (point.x < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** The length of [points] (joined back to its start when [closed]). */
    internal fun length(points: List<Point>, closed: Boolean): Float {
        var total = 0f
        for (i in 1 until points.size) total += (points[i] - points[i - 1]).distance
        if (closed && points.size > 1) total += (points.first() - points.last()).distance
        return total
    }
}
