package io.github.valerioisufi.pageboard.model.shape

import io.github.valerioisufi.pageboard.model.geometry.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Recognizes the shape a stroke drawn by hand looks like: a line, an arrow, a broken line of two
 * to five segments, an ellipse or a circle, a triangle, a rectangle or a square, a polygon of five
 * or six sides; or nothing, when it looks like none of them (handwriting, a curve, a scribble).
 *
 * The shapes found are regularized: a line, a side or an axis within a few degrees of 0°, 45° or
 * 90° takes that angle, an ellipse nearly round is a circle, a rectangle nearly square a square.
 */
public object ShapeRecognizer {
    /**
     * The shape of the stroke through [points] (page millimeters, in the order they were drawn),
     * or `null` when it looks like none. [tolerance] is how far a hand drawing a shape may stray
     * from it at the scale it is drawn at, in millimeters (a few dp on the screen): the points at
     * the end within it of the last one are the pen held still, and a stroke not bigger than a
     * few of it is a dot.
     */
    public fun recognize(points: List<Point>, tolerance: Float): Shape? {
        val drawn = withoutHold(points, tolerance * HOLD_RADIUS)
        if (drawn.size < 2) return null
        val diagonal = diagonal(drawn)
        if (diagonal < tolerance * MIN_SIZE) return null
        val length = length(drawn)
        val path = resample(drawn, max(length / RESAMPLED_POINTS, tolerance / 4f))
        if (path.size < 3) return null
        // Closed when its last part comes back to its start (drawn past it, or not quite to it)
        val closed = (path.size * (1f - LOOP_END)).toInt().let { from -> (from until path.size).any { (path[it] - path[0]).distance < CLOSED_GAP * diagonal } }
        return if (closed) recognizeClosed(closeLoop(path), diagonal, tolerance) else recognizeOpen(path, diagonal, tolerance)
    }

    // ==========================================================
    // Open strokes
    // ==========================================================

    private fun recognizeOpen(path: List<Point>, diagonal: Float, tolerance: Float): Shape? {
        val start = path.first()
        val end = path.last()
        val chord = (end - start).distance
        if (path.maxOf { distanceToSegment(it, start, end) } <= max(tolerance, LINE_DEVIATION * chord)) {
            return straightLine(start, end)
        }

        val epsilon = max(tolerance, CORNER_DEVIATION * diagonal)
        var corners = simplify(path, 0, path.lastIndex, epsilon)
        arrow(path, corners, tolerance)?.let { return it }

        // A flick of the pen at an end is no segment
        val length = length(path)
        if (corners.size > 2 && segment(path, corners, 0) < HOOK * length) corners = corners.drop(1)
        if (corners.size > 2 && segment(path, corners, corners.size - 2) < HOOK * length) corners = corners.dropLast(1)
        val vertices = corners.map { path[it] }
        if (vertices.size == 2) return straightLine(vertices[0], vertices[1])

        if (vertices.size - 1 > MAX_POLYLINE_SEGMENTS) return null
        for (i in 1 until vertices.lastIndex) {
            val turn = turn(vertices[i - 1], vertices[i], vertices[i + 1])
            if (turn < MIN_TURN || !isSharp(path, corners[i], turn)) return null
        }
        val sub = path.subList(corners.first(), corners.last() + 1)
        if (meanDistance(sub, vertices, closed = false) > STRAIGHTNESS * epsilon) return null
        return Polyline(regularizedSegments(vertices))
    }

    /**
     * The arrow of the corners [corners] of [path]: a long shaft, then a head that turns back at its
     * end and stays near it.
     */
    private fun arrow(path: List<Point>, corners: List<Int>, tolerance: Float): Arrow? {
        if (corners.size < 3 || corners.size - 2 > MAX_HEAD_SEGMENTS) return null
        val tail = path[corners[0]]
        val tip = path[corners[1]]
        val shaft = (tip - tail).distance
        val head = corners.drop(2).map { path[it] }
        val headLength = head.maxOf { (it - tip).distance }
        if (headLength > HEAD_MAX * shaft || headLength < max(tolerance * 3f, HEAD_MIN * shaft)) return null
        if (angleBetween(tip - tail, path[corners[2]] - tip) < HEAD_TURN) return null
        val line = straightLine(tail, tip)
        return Arrow(line.start, line.end, min(headLength, HEAD_MAX_DRAWN * shaft))
    }

    /** The segment from [start] to [end], turned around [start] onto 0°, 45° or 90° when near. */
    private fun straightLine(start: Point, end: Point): Line {
        val delta = end - start
        val angle = atan2(delta.y, delta.x)
        val snapped = snapAngle(angle, QUARTER / 2f)
        return Line(start, if (snapped == angle) end else start + Point(delta.distance, 0f).rotated(snapped))
    }

    /** [vertices] with each segment turned onto 0°, 45° or 90° when near, its length kept. */
    private fun regularizedSegments(vertices: List<Point>): List<Point> {
        val result = mutableListOf(vertices.first())
        for (i in 1 until vertices.size) {
            val delta = vertices[i] - vertices[i - 1]
            val angle = snapAngle(atan2(delta.y, delta.x), QUARTER / 2f)
            result += result.last() + Point(delta.distance, 0f).rotated(angle)
        }
        return result
    }

    // ==========================================================
    // Closed strokes
    // ==========================================================

    private fun recognizeClosed(path: List<Point>, diagonal: Float, tolerance: Float): Shape? {
        val ellipse = fitEllipse(path)
        val polygon = fitPolygon(path, max(tolerance, CLOSED_CORNER_DEVIATION * diagonal))
        return when {
            ellipse != null && (polygon == null || ellipse.second <= polygon.second) -> ellipse.first
            polygon != null -> regularizedPolygon(polygon.first)
            else -> null
        }
    }

    /**
     * The ellipse through [path] and the mean distance of its points from it, or `null` when the
     * points stray too far from any: its center and axes from the moments of the points, its radii
     * by least squares.
     */
    private fun fitEllipse(path: List<Point>): Pair<Ellipse, Float>? {
        val points = path.dropLast(1)
        val center = Point(points.sumOf { it.x.toDouble() }.toFloat() / points.size, points.sumOf { it.y.toDouble() }.toFloat() / points.size)
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (p in points) {
            val d = p - center
            sxx += d.x * d.x
            syy += d.y * d.y
            sxy += d.x * d.y
        }
        val rotation = (0.5 * atan2(2 * sxy, sxx - syy)).toFloat()
        val local = points.map { (it - center).rotated(-rotation) }
        var uuuu = 0.0
        var uuvv = 0.0
        var vvvv = 0.0
        var uu = 0.0
        var vv = 0.0
        for (p in local) {
            val u2 = (p.x * p.x).toDouble()
            val v2 = (p.y * p.y).toDouble()
            uuuu += u2 * u2
            uuvv += u2 * v2
            vvvv += v2 * v2
            uu += u2
            vv += v2
        }
        val determinant = uuuu * vvvv - uuvv * uuvv
        if (determinant <= 0.0) return null
        val a = (uu * vvvv - vv * uuvv) / determinant
        val b = (vv * uuuu - uu * uuvv) / determinant
        if (a <= 0.0 || b <= 0.0) return null
        val radiusX = (1.0 / sqrt(a)).toFloat()
        val radiusY = (1.0 / sqrt(b)).toFloat()

        val errors = local.map { abs(sqrt(a * it.x * it.x + b * it.y * it.y).toFloat() - 1f) }
        if (errors.average() > ELLIPSE_MEAN_ERROR || errors.max() > ELLIPSE_MAX_ERROR) return null
        val distance = errors.average().toFloat() * sqrt(radiusX * radiusY)

        val ellipse = if (abs(radiusX - radiusY) <= ROUND * max(radiusX, radiusY)) {
            Ellipse(center, (radiusX + radiusY) / 2f, (radiusX + radiusY) / 2f, 0f)
        } else {
            Ellipse(center, radiusX, radiusY, snapAngle(rotation, QUARTER / 2f))
        }
        return ellipse to distance
    }

    /**
     * The polygon of three to six corners through [path] (closed) and the mean distance of its
     * points from its sides, or `null` when it has more corners or fewer, or its sides are not
     * straight. Each side is the line fit to the middle of its points, so a corner drawn round is
     * where its two sides meet.
     */
    private fun fitPolygon(path: List<Point>, epsilon: Float): Pair<List<Point>, Float>? {
        val far = path.indices.maxBy { (path[it] - path[0]).distance }
        if (far == 0 || far == path.lastIndex) return null
        val corners = (simplify(path, 0, far, epsilon) + simplify(path, far, path.lastIndex, epsilon).drop(1)).dropLast(1).toMutableList()
        fun vertices() = corners.map { path[it] }
        val perimeter = length(path)

        // Away the points where the pen did not turn, and the corners too near another
        while (corners.size > 3) {
            val vertices = vertices()
            val weakest = vertices.indices.minBy { closedTurn(vertices, it) }
            val shortest = vertices.indices.minBy { (vertices[(it + 1) % vertices.size] - vertices[it]).distance }
            val next = (shortest + 1) % vertices.size
            when {
                closedTurn(vertices, weakest) < MIN_CLOSED_TURN -> corners.removeAt(weakest)
                (vertices[next] - vertices[shortest]).distance < MIN_SIDE * perimeter ->
                    corners.removeAt(if (closedTurn(vertices, shortest) < closedTurn(vertices, next)) shortest else next)
                else -> break
            }
        }
        if (corners.size !in 3..MAX_POLYGON_SIDES) return null
        if (vertices().let { v -> v.indices.any { closedTurn(v, it) < MIN_CLOSED_TURN } }) return null

        // The middle of the points of each side, and its line
        val sides = corners.indices.map { k ->
            val from = corners[k]
            val to = corners[(k + 1) % corners.size]
            val points = if (to > from) path.subList(from, to + 1) else path.subList(from, path.lastIndex) + path.subList(0, to + 1)
            val trim = (points.size * SIDE_ENDS).toInt()
            points.subList(trim, points.size - trim).ifEmpty { points }
        }
        val lines = sides.map(::fitLine)
        val distance = sides.indices.map { k -> sides[k].map { lines[k].distanceTo(it) } }.flatten().average().toFloat()
        if (distance > STRAIGHTNESS * epsilon) return null
        val vertices = corners.indices.map { k ->
            lines[(k + corners.size - 1) % corners.size].intersection(lines[k]) ?: path[corners[k]]
        }
        return vertices to distance
    }

    /** A line through [point] along the unit vector [direction]. */
    private class FitLine(val point: Point, val direction: Point) {
        fun distanceTo(p: Point): Float = abs((p.x - point.x) * direction.y - (p.y - point.y) * direction.x)

        /** Where it crosses [other], or `null` when they are nearly parallel. */
        fun intersection(other: FitLine): Point? {
            val cross = direction.x * other.direction.y - direction.y * other.direction.x
            if (abs(cross) < 0.1f) return null
            val delta = other.point - point
            val t = (delta.x * other.direction.y - delta.y * other.direction.x) / cross
            return point + direction * t
        }
    }

    /** The line nearest to [points] (their centroid, along their main axis). */
    private fun fitLine(points: List<Point>): FitLine {
        val center = Point(points.sumOf { it.x.toDouble() }.toFloat() / points.size, points.sumOf { it.y.toDouble() }.toFloat() / points.size)
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (p in points) {
            val d = p - center
            sxx += d.x * d.x
            syy += d.y * d.y
            sxy += d.x * d.y
        }
        val angle = (0.5 * atan2(2 * sxy, sxx - syy)).toFloat()
        return FitLine(center, Point(cos(angle), sin(angle)))
    }

    /**
     * [vertices] as the polygon drawn: four right angles give a rectangle (a square when its sides
     * are nearly equal); a side within a few degrees of 0°, 45° or 90° turns the polygon onto it.
     */
    private fun regularizedPolygon(vertices: List<Point>): Polygon {
        if (vertices.size == 4 && vertices.indices.all { abs(closedTurn(vertices, it) - QUARTER) < RIGHT_ANGLE }) {
            return rectangle(vertices)
        }
        val center = Point(vertices.sumOf { it.x.toDouble() }.toFloat() / vertices.size, vertices.sumOf { it.y.toDouble() }.toFloat() / vertices.size)
        val correction = vertices.indices.map { i ->
            val side = vertices[(i + 1) % vertices.size] - vertices[i]
            val angle = atan2(side.y, side.x)
            snapAngle(angle, QUARTER / 2f) - angle
        }.filter { it != 0f }.minByOrNull { abs(it) } ?: return Polygon(vertices)
        return Polygon(vertices.map { (it - center).rotated(correction) + center })
    }

    /** The rectangle of the four corners [vertices], drawn in their order from the first. */
    private fun rectangle(vertices: List<Point>): Polygon {
        // The direction of the sides: the mean of their angles modulo 90°
        var sx = 0.0
        var sy = 0.0
        for (i in vertices.indices) {
            val side = vertices[(i + 1) % 4] - vertices[i]
            val angle = 4.0 * atan2(side.y, side.x)
            sx += cos(angle)
            sy += sin(angle)
        }
        val rotation = snapAngle((atan2(sy, sx) / 4.0).toFloat(), QUARTER / 2f)
        val center = Point(vertices.sumOf { it.x.toDouble() }.toFloat() / 4f, vertices.sumOf { it.y.toDouble() }.toFloat() / 4f)
        val local = vertices.map { (it - center).rotated(-rotation) }
        var halfWidth = local.map { abs(it.x) }.average().toFloat()
        var halfHeight = local.map { abs(it.y) }.average().toFloat()
        if (abs(halfWidth - halfHeight) <= SQUARE * max(halfWidth, halfHeight)) {
            halfWidth = (halfWidth + halfHeight) / 2f
            halfHeight = halfWidth
        }
        // Each corner drawn becomes the corner of its quadrant, so the order and the direction stay
        val corners = local.map { Point(if (it.x < 0f) -halfWidth else halfWidth, if (it.y < 0f) -halfHeight else halfHeight) }
        if (corners.toSet().size < 4) {
            val square = listOf(Point(-halfWidth, -halfHeight), Point(halfWidth, -halfHeight), Point(halfWidth, halfHeight), Point(-halfWidth, halfHeight))
            return Polygon(square.map { it.rotated(rotation) + center })
        }
        return Polygon(corners.map { it.rotated(rotation) + center })
    }

    // ==========================================================
    // Geometry
    // ==========================================================

    /** [points] without the ones at the end within [radius] of the last one (the pen held still), the first of them kept. */
    private fun withoutHold(points: List<Point>, radius: Float): List<Point> {
        if (points.isEmpty()) return points
        val last = points.last()
        var i = points.lastIndex
        while (i > 0 && (points[i - 1] - last).distance <= radius) i--
        return points.subList(0, i + 1)
    }

    /** [path] up to the point of its last part nearest to its start: a closed stroke without the part drawn past it. */
    private fun closeLoop(path: List<Point>): List<Point> {
        val from = (path.size * (1f - LOOP_END)).toInt()
        val end = (from until path.size).minBy { (path[it] - path[0]).distance }
        return path.subList(0, end + 1) + path[0]
    }

    private fun resample(points: List<Point>, spacing: Float): List<Point> {
        val result = mutableListOf(points.first())
        var carried = 0f
        for (i in 1 until points.size) {
            var from = points[i - 1]
            val to = points[i]
            var segment = (to - from).distance
            while (carried + segment >= spacing) {
                val t = (spacing - carried) / segment
                from += (to - from) * t
                result += from
                segment = (to - from).distance
                carried = 0f
            }
            carried += segment
        }
        if ((result.last() - points.last()).distance > spacing / 2f) result += points.last()
        return result
    }

    /** The corners of [path] from [first] to [last] (both included) by Ramer–Douglas–Peucker. */
    private fun simplify(path: List<Point>, first: Int, last: Int, epsilon: Float): List<Int> {
        var farthest = -1
        var distance = 0f
        for (i in first + 1 until last) {
            val d = distanceToSegment(path[i], path[first], path[last])
            if (d > distance) {
                distance = d
                farthest = i
            }
        }
        if (farthest < 0 || distance <= epsilon) return listOf(first, last)
        return simplify(path, first, farthest, epsilon) + simplify(path, farthest, last, epsilon).drop(1)
    }

    /**
     * Whether the direction of [path] turns by most of [turn] within a few points of [index]: a
     * corner, where a curve turns little by little.
     */
    private fun isSharp(path: List<Point>, index: Int, turn: Float): Boolean {
        val window = maxOf(2, (path.size * CORNER_WINDOW).toInt())
        val before = path[maxOf(0, index - window)]
        val after = path[minOf(path.lastIndex, index + window)]
        return turn(before, path[index], after) >= SHARPNESS * turn
    }

    private fun segment(path: List<Point>, corners: List<Int>, index: Int): Float = (path[corners[index + 1]] - path[corners[index]]).distance

    /** How much the direction turns at [b], from [a] to [c], in radians (0 straight on, π back). */
    private fun turn(a: Point, b: Point, c: Point): Float = angleBetween(b - a, c - b)

    private fun closedTurn(vertices: List<Point>, i: Int): Float =
        turn(vertices[(i + vertices.size - 1) % vertices.size], vertices[i], vertices[(i + 1) % vertices.size])

    private fun angleBetween(u: Point, v: Point): Float {
        val lengths = u.distance * v.distance
        if (lengths == 0f) return 0f
        return acos(((u.x * v.x + u.y * v.y) / lengths).coerceIn(-1f, 1f))
    }

    /** [angle] onto the nearest multiple of [step] when within [SNAP_ANGLE] of it. */
    private fun snapAngle(angle: Float, step: Float): Float {
        val nearest = round(angle / step) * step
        return if (abs(angle - nearest) <= SNAP_ANGLE) nearest else angle
    }

    private fun distanceToSegment(p: Point, a: Point, b: Point): Float {
        val ab = b - a
        val lengthSquared = ab.x * ab.x + ab.y * ab.y
        if (lengthSquared == 0f) return (p - a).distance
        val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / lengthSquared).coerceIn(0f, 1f)
        return (p - (a + ab * t)).distance
    }

    /** The mean distance of [points] from the polyline through [vertices] (closed or not). */
    private fun meanDistance(points: List<Point>, vertices: List<Point>, closed: Boolean): Float {
        val segments = if (closed) vertices.indices.map { vertices[it] to vertices[(it + 1) % vertices.size] } else vertices.zipWithNext()
        return points.map { p -> segments.minOf { (a, b) -> distanceToSegment(p, a, b) } }.average().toFloat()
    }

    private fun length(points: List<Point>): Float = points.zipWithNext().sumOf { (a, b) -> (b - a).distance.toDouble() }.toFloat()

    private fun diagonal(points: List<Point>): Float =
        Point(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y }).distance

    private const val QUARTER = (PI / 2).toFloat()
    private const val DEGREE = (PI / 180).toFloat()

    /** The radius of the pen held still, and the size of a dot, in [recognize]'s tolerances. */
    private const val HOLD_RADIUS = 1.5f
    private const val MIN_SIZE = 4f

    /** The points a stroke is resampled to. */
    private const val RESAMPLED_POINTS = 160f

    /** How near its ends are for a stroke to be closed, as a fraction of the diagonal of its bounds. */
    private const val CLOSED_GAP = 0.2f

    /** How much of a closed stroke may have been drawn past its start, as a fraction of it. */
    private const val LOOP_END = 0.3f

    /** How far a line may stray from its ends' segment, as a fraction of its length. */
    private const val LINE_DEVIATION = 0.05f

    /** How far a side may stray from the segment of its corners, as a fraction of the diagonal. */
    private const val CORNER_DEVIATION = 0.04f
    private const val CLOSED_CORNER_DEVIATION = 0.035f

    /** How near its sides the points of a polygon or a broken line stay on average, as a fraction of the deviation allowed. */
    private const val STRAIGHTNESS = 0.3f

    /** A segment at an end shorter than this fraction of the stroke is a flick of the pen. */
    private const val HOOK = 0.08f
    private const val MAX_POLYLINE_SEGMENTS = 5
    private const val MIN_TURN = 30f * DEGREE

    /** A corner turns by this fraction of its angle within this fraction of the points around it. */
    private const val SHARPNESS = 0.6f
    private const val CORNER_WINDOW = 0.035f

    /** The head of an arrow: its size as a fraction of the shaft, how far it turns back, its segments. */
    private const val HEAD_MIN = 0.08f
    private const val HEAD_MAX = 0.5f
    private const val HEAD_MAX_DRAWN = 0.3f
    private const val HEAD_TURN = 110f * DEGREE
    private const val MAX_HEAD_SEGMENTS = 4

    /** How far from an ellipse the points may stray, as a fraction of its radius: on average, at most. */
    private const val ELLIPSE_MEAN_ERROR = 0.08
    private const val ELLIPSE_MAX_ERROR = 0.25f

    /** How much the radii of a circle, the sides of a square may differ, as a fraction of the larger. */
    private const val ROUND = 0.12f
    private const val SQUARE = 0.1f

    /** A corner of a polygon turns at least this much; a polygon has at most this many sides, each at least this fraction of the perimeter. */
    private const val MIN_CLOSED_TURN = 25f * DEGREE
    private const val MAX_POLYGON_SIDES = 6
    private const val MIN_SIDE = 0.05f

    /** The part of a side at each of its ends left out of its line (the corners, drawn round). */
    private const val SIDE_ENDS = 0.15f

    /** How far from 90° the corners of a rectangle may be. */
    private const val RIGHT_ANGLE = 15f * DEGREE

    /** How near an angle snaps onto a multiple of 45°. */
    private const val SNAP_ANGLE = 6f * DEGREE
}
