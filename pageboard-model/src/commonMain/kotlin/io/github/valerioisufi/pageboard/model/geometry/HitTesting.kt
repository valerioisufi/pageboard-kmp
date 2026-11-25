package io.github.valerioisufi.pageboard.model.geometry

import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokeOutline
import kotlin.math.max
import kotlin.math.min

/**
 * Exact geometric hit-testing of stroke meshes (and of the lines of the marks of the text of the
 * PDFs), used by the eraser (capsule shaped segments) and by the lasso selection (closed
 * polygons). All coordinates are in page millimeters.
 */
public object HitTesting {

    /**
     * Whether [mesh] (its outline polygons or triangles) lies within [radius] of the segment ([x1], [y1]) - ([x2], [y2]).
     */
    public fun meshIntersectsCapsule(
        mesh: StrokeMesh,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        radius: Float
    ): Boolean {
        val outline = mesh.outline
        if (outline != null && !outline.isEmpty) {
            return outlineIntersectsCapsule(outline, x1, y1, x2, y2, radius)
        }
        val capsuleLeft = min(x1, x2) - radius
        val capsuleTop = min(y1, y2) - radius
        val capsuleRight = max(x1, x2) + radius
        val capsuleBottom = max(y1, y2) + radius
        val radiusSq = radius * radius

        for (partition in mesh.partitions) {
            val p = partition.positions
            val idx = partition.indices
            var t = 0
            while (t + 2 < idx.size) {
                val i0 = (idx[t].toInt() and 0xFFFF) * 2
                val i1 = (idx[t + 1].toInt() and 0xFFFF) * 2
                val i2 = (idx[t + 2].toInt() and 0xFFFF) * 2
                t += 3
                if (i0 + 1 >= p.size || i1 + 1 >= p.size || i2 + 1 >= p.size) continue

                val ax = p[i0]; val ay = p[i0 + 1]
                val bx = p[i1]; val by = p[i1 + 1]
                val cx = p[i2]; val cy = p[i2 + 1]

                // Fast AABB rejection
                if (max(ax, max(bx, cx)) < capsuleLeft || min(ax, min(bx, cx)) > capsuleRight ||
                    max(ay, max(by, cy)) < capsuleTop || min(ay, min(by, cy)) > capsuleBottom
                ) continue

                if (triangleSegmentDistanceSq(ax, ay, bx, by, cx, cy, x1, y1, x2, y2) <= radiusSq) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Whether [mesh] (its outline polygons or triangles) overlaps the closed [polygon] (interleaved x, y coordinates).
     */
    public fun meshIntersectsPolygon(mesh: StrokeMesh, polygon: FloatArray, polygonBounds: Rect): Boolean {
        if (polygon.size < 6) return false
        val outline = mesh.outline
        if (outline != null && !outline.isEmpty) {
            return outlineIntersectsPolygon(outline, polygon, polygonBounds)
        }
        for (partition in mesh.partitions) {
            val p = partition.positions
            val idx = partition.indices
            var t = 0
            while (t + 2 < idx.size) {
                val i0 = (idx[t].toInt() and 0xFFFF) * 2
                val i1 = (idx[t + 1].toInt() and 0xFFFF) * 2
                val i2 = (idx[t + 2].toInt() and 0xFFFF) * 2
                t += 3
                if (i0 + 1 >= p.size || i1 + 1 >= p.size || i2 + 1 >= p.size) continue

                val ax = p[i0]; val ay = p[i0 + 1]
                val bx = p[i1]; val by = p[i1 + 1]
                val cx = p[i2]; val cy = p[i2 + 1]

                if (max(ax, max(bx, cx)) < polygonBounds.left || min(ax, min(bx, cx)) > polygonBounds.right ||
                    max(ay, max(by, cy)) < polygonBounds.top || min(ay, min(by, cy)) > polygonBounds.bottom
                ) continue

                if (triangleIntersectsPolygon(ax, ay, bx, by, cx, cy, polygon)) return true
            }
        }
        return false
    }

    private fun outlineIntersectsCapsule(
        outline: StrokeOutline,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        radius: Float
    ): Boolean {
        val capsuleLeft = min(x1, x2) - radius
        val capsuleTop = min(y1, y2) - radius
        val capsuleRight = max(x1, x2) + radius
        val capsuleBottom = max(y1, y2) + radius
        val radiusSq = radius * radius

        for (poly in outline.polygons) {
            if (poly.size < 6) continue
            val bounds = polygonBounds(poly)
            if (bounds.right < capsuleLeft || bounds.left > capsuleRight ||
                bounds.bottom < capsuleTop || bounds.top > capsuleBottom
            ) continue
            if (pointInPolygonWinding(x1, y1, poly) || pointInPolygonWinding(x2, y2, poly)) {
                return true
            }
            val n = poly.size / 2
            var prevX = poly[(n - 1) * 2]
            var prevY = poly[(n - 1) * 2 + 1]
            for (i in 0 until n) {
                val curX = poly[i * 2]
                val curY = poly[i * 2 + 1]
                if (segmentSegmentDistanceSq(x1, y1, x2, y2, prevX, prevY, curX, curY) <= radiusSq) {
                    return true
                }
                prevX = curX
                prevY = curY
            }
        }
        return false
    }

    private fun outlineIntersectsPolygon(
        outline: StrokeOutline,
        polygon: FloatArray,
        polygonBounds: Rect
    ): Boolean {
        val lassoCount = polygon.size / 2
        for (poly in outline.polygons) {
            if (poly.size < 6) continue
            val bounds = polygonBounds(poly)
            if (bounds.right < polygonBounds.left || bounds.left > polygonBounds.right ||
                bounds.bottom < polygonBounds.top || bounds.top > polygonBounds.bottom
            ) continue
            val n = poly.size / 2
            for (i in 0 until n) {
                if (pointInPolygon(poly[i * 2], poly[i * 2 + 1], polygon)) return true
            }
            for (k in 0 until lassoCount) {
                if (pointInPolygonWinding(polygon[k * 2], polygon[k * 2 + 1], poly)) return true
            }
            var prevAx = poly[(n - 1) * 2]
            var prevAy = poly[(n - 1) * 2 + 1]
            for (i in 0 until n) {
                val curAx = poly[i * 2]
                val curAy = poly[i * 2 + 1]
                var prevLx = polygon[(lassoCount - 1) * 2]
                var prevLy = polygon[(lassoCount - 1) * 2 + 1]
                for (k in 0 until lassoCount) {
                    val curLx = polygon[k * 2]
                    val curLy = polygon[k * 2 + 1]
                    if (segmentsIntersect(prevAx, prevAy, curAx, curAy, prevLx, prevLy, curLx, curLy)) {
                        return true
                    }
                    prevLx = curLx
                    prevLy = curLy
                }
                prevAx = curAx
                prevAy = curAy
            }
        }
        return false
    }

    /** Non-zero winding rule point-in-polygon test matching filled stroke outlines. */
    private fun pointInPolygonWinding(px: Float, py: Float, polygon: FloatArray): Boolean {
        var winding = 0
        val n = polygon.size / 2
        var x1 = polygon[(n - 1) * 2]
        var y1 = polygon[(n - 1) * 2 + 1]
        for (i in 0 until n) {
            val x2 = polygon[i * 2]
            val y2 = polygon[i * 2 + 1]
            if (y1 <= py) {
                if (y2 > py && cross(x1, y1, x2, y2, px, py) > 0f) winding++
            } else {
                if (y2 <= py && cross(x1, y1, x2, y2, px, py) < 0f) winding--
            }
            x1 = x2
            y1 = y2
        }
        return winding != 0
    }

    /**
     * Even-odd point in polygon test.
     */
    public fun pointInPolygon(x: Float, y: Float, polygon: FloatArray): Boolean {
        var inside = false
        val n = polygon.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = polygon[i * 2]; val yi = polygon[i * 2 + 1]
            val xj = polygon[j * 2]; val yj = polygon[j * 2 + 1]
            if ((yi > y) != (yj > y)) {
                val xCross = (xj - xi) * (y - yi) / (yj - yi) + xi
                if (x < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Axis-aligned bounds of an interleaved point list.
     */
    public fun polygonBounds(polygon: FloatArray): Rect {
        if (polygon.size < 2) return Rect.Zero
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var i = 0
        while (i + 1 < polygon.size) {
            minX = min(minX, polygon[i]); maxX = max(maxX, polygon[i])
            minY = min(minY, polygon[i + 1]); maxY = max(maxY, polygon[i + 1])
            i += 2
        }
        return Rect(minX, minY, maxX, maxY)
    }

    private fun triangleIntersectsPolygon(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float,
        polygon: FloatArray
    ): Boolean {
        // A triangle vertex inside the polygon
        if (pointInPolygon(ax, ay, polygon) || pointInPolygon(bx, by, polygon) || pointInPolygon(cx, cy, polygon)) {
            return true
        }
        // A polygon vertex inside the triangle
        val n = polygon.size / 2
        for (i in 0 until n) {
            if (pointInTriangle(polygon[i * 2], polygon[i * 2 + 1], ax, ay, bx, by, cx, cy)) return true
        }
        // Edge crossings
        var j = n - 1
        for (i in 0 until n) {
            val px = polygon[j * 2]; val py = polygon[j * 2 + 1]
            val qx = polygon[i * 2]; val qy = polygon[i * 2 + 1]
            if (segmentsIntersect(px, py, qx, qy, ax, ay, bx, by) ||
                segmentsIntersect(px, py, qx, qy, bx, by, cx, cy) ||
                segmentsIntersect(px, py, qx, qy, cx, cy, ax, ay)
            ) return true
            j = i
        }
        return false
    }

    internal fun pointInTriangle(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float
    ): Boolean {
        // Zero-area triangles (frequent at the extremities of Ink meshes) contain no point:
        // proximity to them is still detected through their edges.
        if (cross(ax, ay, bx, by, cx, cy) == 0f) return false
        val d1 = cross(px, py, ax, ay, bx, by)
        val d2 = cross(px, py, bx, by, cx, cy)
        val d3 = cross(px, py, cx, cy, ax, ay)
        val hasNeg = d1 < 0f || d2 < 0f || d3 < 0f
        val hasPos = d1 > 0f || d2 > 0f || d3 > 0f
        return !(hasNeg && hasPos)
    }

    /**
     * Whether [rect] lies within [radius] of the segment ([x1], [y1]) - ([x2], [y2]).
     */
    public fun rectIntersectsCapsule(rect: Rect, x1: Float, y1: Float, x2: Float, y2: Float, radius: Float): Boolean {
        if (rect.inflate(radius).let { min(x1, x2) > it.right || max(x1, x2) < it.left || min(y1, y2) > it.bottom || max(y1, y2) < it.top }) {
            return false
        }
        // Segment endpoint inside the rectangle: zero distance
        if ((x1 in rect.left..rect.right && y1 in rect.top..rect.bottom) || (x2 in rect.left..rect.right && y2 in rect.top..rect.bottom)) {
            return true
        }
        val radiusSq = radius * radius
        return segmentSegmentDistanceSq(x1, y1, x2, y2, rect.left, rect.top, rect.right, rect.top) <= radiusSq ||
            segmentSegmentDistanceSq(x1, y1, x2, y2, rect.right, rect.top, rect.right, rect.bottom) <= radiusSq ||
            segmentSegmentDistanceSq(x1, y1, x2, y2, rect.right, rect.bottom, rect.left, rect.bottom) <= radiusSq ||
            segmentSegmentDistanceSq(x1, y1, x2, y2, rect.left, rect.bottom, rect.left, rect.top) <= radiusSq
    }

    internal fun segmentsIntersect(
        p1x: Float, p1y: Float, p2x: Float, p2y: Float,
        q1x: Float, q1y: Float, q2x: Float, q2y: Float
    ): Boolean {
        val d1 = cross(q1x, q1y, q2x, q2y, p1x, p1y)
        val d2 = cross(q1x, q1y, q2x, q2y, p2x, p2y)
        val d3 = cross(p1x, p1y, p2x, p2y, q1x, q1y)
        val d4 = cross(p1x, p1y, p2x, p2y, q2x, q2y)
        if (((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
        ) return true
        if (d1 == 0f && onSegment(q1x, q1y, q2x, q2y, p1x, p1y)) return true
        if (d2 == 0f && onSegment(q1x, q1y, q2x, q2y, p2x, p2y)) return true
        if (d3 == 0f && onSegment(p1x, p1y, p2x, p2y, q1x, q1y)) return true
        if (d4 == 0f && onSegment(p1x, p1y, p2x, p2y, q2x, q2y)) return true
        return false
    }

    /**
     * Squared distance between point ([px], [py]) and segment ([ax], [ay]) - ([bx], [by]).
     */
    internal fun pointSegmentDistanceSq(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        var t = if (lenSq > 0f) ((px - ax) * dx + (py - ay) * dy) / lenSq else 0f
        t = t.coerceIn(0f, 1f)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return cx * cx + cy * cy
    }

    private fun segmentSegmentDistanceSq(
        p1x: Float, p1y: Float, p2x: Float, p2y: Float,
        q1x: Float, q1y: Float, q2x: Float, q2y: Float
    ): Float {
        if (segmentsIntersect(p1x, p1y, p2x, p2y, q1x, q1y, q2x, q2y)) return 0f
        return min(
            min(
                pointSegmentDistanceSq(p1x, p1y, q1x, q1y, q2x, q2y),
                pointSegmentDistanceSq(p2x, p2y, q1x, q1y, q2x, q2y)
            ),
            min(
                pointSegmentDistanceSq(q1x, q1y, p1x, p1y, p2x, p2y),
                pointSegmentDistanceSq(q2x, q2y, p1x, p1y, p2x, p2y)
            )
        )
    }

    private fun triangleSegmentDistanceSq(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float,
        s1x: Float, s1y: Float,
        s2x: Float, s2y: Float
    ): Float {
        // Segment endpoint inside the triangle: zero distance
        if (pointInTriangle(s1x, s1y, ax, ay, bx, by, cx, cy) || pointInTriangle(s2x, s2y, ax, ay, bx, by, cx, cy)) {
            return 0f
        }
        return min(
            segmentSegmentDistanceSq(s1x, s1y, s2x, s2y, ax, ay, bx, by),
            min(
                segmentSegmentDistanceSq(s1x, s1y, s2x, s2y, bx, by, cx, cy),
                segmentSegmentDistanceSq(s1x, s1y, s2x, s2y, cx, cy, ax, ay)
            )
        )
    }

    private fun cross(ox: Float, oy: Float, ax: Float, ay: Float, bx: Float, by: Float): Float =
        (ax - ox) * (by - oy) - (ay - oy) * (bx - ox)

    private fun onSegment(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Boolean =
        px >= min(ax, bx) && px <= max(ax, bx) && py >= min(ay, by) && py <= max(ay, by)
}
