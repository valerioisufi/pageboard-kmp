package io.github.valerioisufi.drawview.model.shape

import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.geometry.VectorDot
import io.github.valerioisufi.drawview.model.geometry.VectorDrawing
import io.github.valerioisufi.drawview.model.geometry.VectorFill
import io.github.valerioisufi.drawview.model.geometry.VectorLine
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
    public val solid: Boolean = false,
    /** Whether its points are corners (a polygon), not the samples of a curve. */
    public val cornered: Boolean = true
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

    /**
     * The paths that draw [value] in a box of [width] x [height] millimeters, everything they draw
     * (their width and the miters of their corners, see [MITER_LIMIT]) inside the box.
     */
    public fun paths(value: ShapeElement, width: Float, height: Float): List<ShapePath> {
        if (value.kind.isLine) return linePaths(value, width, height)
        val half = value.widthMm / 2f
        val filled = value.fill != null
        val curved = value.kind == ShapeKind.ELLIPSE || value.kind == ShapeKind.ROUNDED_RECTANGLE
        // The outline inside the box: its outer edge on the edge of the box, the miters of its
        // corners too
        val inset = min(half, min(width, height) / 2f)
        var points = outline(value.kind, width, height, inset, inset, inset, inset)
        if (!curved) {
            // Moved in until the farthest miter on each side touches the box (a few rounds: the
            // angles change a little as the outline moves)
            val room = min(width, height) / 2f
            var left = inset
            var top = inset
            var right = inset
            var bottom = inset
            repeat(MITER_ROUNDS) {
                val tips = miterTips(points, half)
                left = (left - tips.minOf { it.x }).coerceIn(inset, room)
                top = (top - tips.minOf { it.y }).coerceIn(inset, room)
                right = (right + tips.maxOf { it.x } - width).coerceIn(inset, room)
                bottom = (bottom + tips.maxOf { it.y } - height).coerceIn(inset, room)
                points = outline(value.kind, width, height, left, top, right, bottom)
            }
        }
        return listOf(ShapePath(points, closed = true, filled = filled, cornered = !curved))
    }

    /**
     * The outline of a shape of a box of [kind] in a box of [width] x [height], inside it by
     * [insetLeft], [insetTop], [insetRight] and [insetBottom].
     */
    private fun outline(kind: ShapeKind, width: Float, height: Float, insetLeft: Float, insetTop: Float, insetRight: Float, insetBottom: Float): List<Point> {
        val left = insetLeft
        val top = insetTop
        val right = width - insetRight
        val bottom = height - insetBottom
        return when (kind) {
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
    }

    /**
     * The points the corners of the convex polygon [corners] reach when stroked [half] times two
     * wide with mitered joins: past each vertex along its bisector by [half] over the sine of half
     * its angle, by [half] when the miter is cut (past [MITER_LIMIT]).
     */
    private fun miterTips(corners: List<Point>, half: Float): List<Point> = corners.indices.map { i ->
        val vertex = corners[i]
        val a = corners[(i - 1 + corners.size) % corners.size] - vertex
        val b = corners[(i + 1) % corners.size] - vertex
        val la = a.distance
        val lb = b.distance
        if (la == 0f || lb == 0f) return@map vertex
        val ua = a * (1f / la)
        val ub = b * (1f / lb)
        val inward = ua + ub
        val length = inward.distance
        if (length == 0f) return@map vertex
        val cos = (ua.x * ub.x + ua.y * ub.y).coerceIn(-1f, 1f)
        val sinHalf = sqrt((1f - cos) / 2f)
        val ratio = if (sinHalf > 0f) 1f / sinHalf else Float.POSITIVE_INFINITY
        val reach = if (ratio <= MITER_LIMIT) half * ratio else half
        vertex - inward * (reach / length)
    }

    /**
     * The miter limit of the outlines (the one of the PDF): a corner whose miter would be longer
     * than this many times the width is cut (beveled).
     */
    public const val MITER_LIMIT: Float = 10f

    private const val MITER_ROUNDS = 4

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
            // The miter of an open head reaches past its tip: its tip is pulled in by it
            val miter = value.widthMm / 2f / sin(HEAD_ANGLE)
            val startTip = if (value.startHead == ShapeHead.OPEN) Point(min(miter, width / 2f), middle) else start
            val endTip = if (value.endHead == ShapeHead.OPEN) Point(max(width - miter, width / 2f), middle) else end
            if (value.startHead == ShapeHead.OPEN) shaftStart = startTip
            if (value.endHead == ShapeHead.OPEN) shaftEnd = endTip
            paths[0] = ShapePath(listOf(shaftStart, shaftEnd), closed = false)
            headPath(value.startHead, startTip, Point(1f, 0f), head)?.let(paths::add)
            headPath(value.endHead, endTip, Point(-1f, 0f), head)?.let(paths::add)
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
     * Whether the capsule of [radius] millimeters around the segment [from] - [to] of the box
     * touches [value] in a box of [width] x [height]: its outline, or its inside when it is filled
     * (what the eraser erases).
     */
    public fun touchesSegment(value: ShapeElement, from: Point, to: Point, radius: Float, width: Float, height: Float): Boolean {
        val reach = value.widthMm / 2f + radius
        for (path in paths(value, width, height)) {
            if (path.filled && path.closed && (inside(from, path.points) || inside(to, path.points))) return true
            val points = path.points
            val last = if (path.closed) points.size else points.size - 1
            for (i in 0 until last) {
                if (segmentsDistance(from, to, points[i], points[(i + 1) % points.size]) <= reach) return true
            }
        }
        return false
    }

    /**
     * [value] drawn in a box of [width] x [height] millimeters: its fill, its outline (cut in
     * dashes or dots, at most [MAX_DASHES] of them: a longer outline has longer ones) and its heads.
     */
    public fun drawing(value: ShapeElement, width: Float, height: Float): VectorDrawing {
        val fills = ArrayList<VectorFill>(2)
        val lines = ArrayList<VectorLine>()
        val dots = ArrayList<VectorDot>()
        for (path in paths(value, width, height)) {
            if (path.solid) {
                fills += VectorFill(path.points, value.stroke)
                continue
            }
            val fill = value.fill
            if (path.filled && fill != null) fills += VectorFill(path.points, fill)
            when (value.dash) {
                ShapeDash.SOLID -> lines += VectorLine(path.points, path.closed, value.stroke, value.widthMm)
                ShapeDash.DASHED -> {
                    val dash = max(DASH_PER_WIDTH * value.widthMm, MIN_DASH_MM)
                    val gap = max(GAP_PER_WIDTH * value.widthMm, MIN_GAP_MM)
                    val pieces = if (path.closed && path.cornered) cornerDashes(path.points, dash, gap) else dashes(path.points, path.closed, dash, gap)
                    pieces.forEach { lines += VectorLine(it, closed = false, value.stroke, value.widthMm) }
                }
                ShapeDash.DOTTED -> {
                    val spacing = max(DOT_SPACING_PER_WIDTH * value.widthMm, MIN_DOT_SPACING_MM)
                    val centers = if (path.closed && path.cornered) cornerDots(path.points, spacing) else dotCenters(path.points, path.closed, spacing)
                    centers.forEach { dots += VectorDot(it, value.widthMm * DOT_RADIUS_PER_WIDTH, value.stroke) }
                }
            }
        }
        return VectorDrawing(fills, lines, dots)
    }

    /** A dash this many times the width, [MIN_DASH_MM] at least; the gaps the same with [GAP_PER_WIDTH]. */
    private const val DASH_PER_WIDTH = 4f
    private const val MIN_DASH_MM = 2f
    private const val GAP_PER_WIDTH = 2.5f
    private const val MIN_GAP_MM = 1.2f
    private const val DOT_SPACING_PER_WIDTH = 2.5f
    private const val MIN_DOT_SPACING_MM = 1f
    private const val DOT_RADIUS_PER_WIDTH = 0.65f

    /** The most dashes or dots of a part of an outline. */
    public const val MAX_DASHES: Int = 1000

    /**
     * The dashes [dash] long, [gap] apart, along [points]: a whole number of them, stretched a
     * little, so that a closed outline has no seam and an open one ends with a dash.
     */
    private fun dashes(points: List<Point>, closed: Boolean, dash: Float, gap: Float): List<List<Point>> {
        val length = length(points, closed)
        if (length <= 0f) return emptyList()
        val period = dash + gap
        val count = if (closed) {
            (length / period).toInt().coerceIn(1, MAX_DASHES)
        } else {
            ((length + gap) / period).toInt().coerceIn(1, MAX_DASHES)
        }
        val scale = if (closed) length / (count * period) else (length + gap) / (count * period)
        val on = dash * scale
        val off = gap * scale
        val walker = Walker(points, closed)
        val pieces = ArrayList<List<Point>>(count)
        var at = 0f
        repeat(count) {
            pieces += walker.between(at, min(at + on, length))
            at += on + off
        }
        return pieces
    }

    /**
     * The dashes of a closed polygon side by side: a dash bent around every corner (half of it on
     * each side), the others spread evenly on each side, so that no corner is a gap.
     */
    private fun cornerDashes(corners: List<Point>, dash: Float, gap: Float): List<List<Point>> {
        val period = dash + gap
        val total = length(corners, closed = true)
        // A longer pattern when the outline would have too many dashes
        val stretch = max(1f, total / (period * MAX_DASHES))
        val pieces = ArrayList<List<Point>>()
        val bends = ArrayList<Pair<Point, Point>>(corners.size) // the end of the half dash before each corner, the start after it
        for (i in corners.indices) {
            val from = corners[i]
            val to = corners[(i + 1) % corners.size]
            val side = (to - from).distance
            if (side <= 0f) {
                bends += from to from
                continue
            }
            val direction = (to - from) * (1f / side)
            val count = max(1, (side / (period * stretch) + 0.5f).toInt())
            val step = side / count
            val half = min(dash * stretch / 2f, step / 2f - gap * stretch / 2f).coerceAtLeast(step / 4f)
            // After the corner [from], then the middles of the other dashes, then before [to]
            bends += (from + direction * half) to (to - direction * half)
            for (k in 1 until count) {
                val middle = k * step
                pieces += listOf(from + direction * (middle - half), from + direction * (middle + half))
            }
        }
        for (i in corners.indices) {
            val before = bends[(i - 1 + corners.size) % corners.size].second
            val after = bends[i].first
            pieces += listOf(before, corners[i], after)
        }
        return pieces
    }

    /** The dots of a closed polygon side by side: one on every corner, the others spread evenly on each side. */
    private fun cornerDots(corners: List<Point>, spacing: Float): List<Point> {
        val total = length(corners, closed = true)
        val stretch = max(1f, total / (spacing * MAX_DASHES))
        val centers = ArrayList<Point>()
        for (i in corners.indices) {
            val from = corners[i]
            val to = corners[(i + 1) % corners.size]
            val count = max(1, ((to - from).distance / (spacing * stretch) + 0.5f).toInt())
            for (k in 0 until count) centers += from + (to - from) * (k.toFloat() / count)
        }
        return centers
    }

    /** The centers of dots [spacing] apart along [points], a whole number of them. */
    private fun dotCenters(points: List<Point>, closed: Boolean, spacing: Float): List<Point> {
        val length = length(points, closed)
        if (length <= 0f) return listOfNotNull(points.firstOrNull())
        val intervals = (length / spacing).roundToIntAtLeastOne().coerceAtMost(MAX_DASHES)
        val step = length / intervals
        val walker = Walker(points, closed)
        val count = if (closed) intervals else intervals + 1
        return List(count) { walker.at(min(it * step, length)) }
    }

    private fun Float.roundToIntAtLeastOne(): Int = (this + 0.5f).toInt().coerceAtLeast(1)

    /** Walks a polyline by the distance along it. */
    private class Walker(points: List<Point>, closed: Boolean) {
        private val vertices = if (closed) points + points.first() else points
        private val starts = FloatArray(vertices.size).also { starts ->
            for (i in 1 until vertices.size) starts[i] = starts[i - 1] + (vertices[i] - vertices[i - 1]).distance
        }

        /** The point at [distance] along the polyline. */
        fun at(distance: Float): Point {
            val i = segmentAt(distance)
            val length = starts[i + 1] - starts[i]
            val t = if (length > 0f) ((distance - starts[i]) / length).coerceIn(0f, 1f) else 0f
            return vertices[i] + (vertices[i + 1] - vertices[i]) * t
        }

        /** The part of the polyline from [from] to [to], with its corners. */
        fun between(from: Float, to: Float): List<Point> {
            val first = segmentAt(from)
            val last = segmentAt(to)
            val piece = ArrayList<Point>(last - first + 2)
            piece += at(from)
            for (i in first + 1..last) piece += vertices[i]
            piece += at(to)
            return piece
        }

        private fun segmentAt(distance: Float): Int {
            var low = 0
            var high = vertices.size - 2
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (starts[middle] <= distance) low = middle else high = middle - 1
            }
            return low.coerceAtLeast(0)
        }
    }

    /**
     * The anchors of [value] in a box of [width] x [height] millimeters, where the ends of a line
     * snap: the corners and the middles of the sides of the outline of a shape of a box (of its box
     * for a curve) and its center, the ends and the middle of a line.
     */
    public fun anchors(value: ShapeElement, width: Float, height: Float): List<Point> {
        val middle = Point(width / 2f, height / 2f)
        if (value.kind.isLine) return listOf(Point(0f, height / 2f), middle, Point(width, height / 2f))
        val outline = when (value.kind) {
            // The extremes of the curves: the middles of the sides of the box
            ShapeKind.ELLIPSE, ShapeKind.ROUNDED_RECTANGLE -> null
            else -> paths(value, width, height).first().points
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

    /** The distance between the segments [a1] - [a2] and [b1] - [b2] (0 when they cross). */
    internal fun segmentsDistance(a1: Point, a2: Point, b1: Point, b2: Point): Float {
        if (crosses(a1, a2, b1, b2)) return 0f
        return min(min(segmentDistance(a1, b1, b2), segmentDistance(a2, b1, b2)), min(segmentDistance(b1, a1, a2), segmentDistance(b2, a1, a2)))
    }

    private fun crosses(a1: Point, a2: Point, b1: Point, b2: Point): Boolean {
        fun side(p: Point, q: Point, r: Point) = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
        val d1 = side(b1, b2, a1)
        val d2 = side(b1, b2, a2)
        val d3 = side(a1, a2, b1)
        val d4 = side(a1, a2, b2)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
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
