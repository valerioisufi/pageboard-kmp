package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Rect

/**
 * Outline of a stroke: closed polygons (x, y pairs, in stroke units: page millimeters for the
 * strokes of a page) whose union, filled with the non-zero winding rule, is the shape of the
 * stroke. The polygons can overlap (filled one by one, a translucent stroke would be darker where
 * they do): they are turned the same way, so that the parts where they overlap are filled too.
 */
public class StrokeOutline(polygons: List<FloatArray>) {
    public val polygons: List<FloatArray> = polygons.map { if (signedArea(it) < 0f) reversed(it) else it }

    public val isEmpty: Boolean get() = polygons.all { it.size < 6 }

    /** Total number of vertices across all closed polygons in this outline. */
    public val vertexCount: Int get() = polygons.sumOf { it.size / 2 }

    /** Axis-aligned bounds of every vertex in this outline, or [Rect.Zero] when empty. */
    public fun computeBounds(): Rect {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (polygon in polygons) {
            var i = 0
            while (i + 1 < polygon.size) {
                val x = polygon[i]
                val y = polygon[i + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                i += 2
            }
        }
        return if (minX <= maxX && minY <= maxY) Rect(minX, minY, maxX, maxY) else Rect.Zero
    }

    /** This outline with every vertex mapped by [transform]. */
    public fun transformed(transform: Matrix2D): StrokeOutline = StrokeOutline(
        polygons.map { polygon -> polygon.copyOf().also { transform.mapPoints(it) } }
    )

    private companion object {
        /** Twice the area of [polygon], positive when it turns from the x axis to the y axis. */
        fun signedArea(polygon: FloatArray): Float {
            var area = 0f
            var i = 0
            while (i + 1 < polygon.size) {
                val j = (i + 2) % polygon.size
                area += polygon[i] * polygon[j + 1] - polygon[j] * polygon[i + 1]
                i += 2
            }
            return area
        }

        fun reversed(polygon: FloatArray): FloatArray {
            val count = polygon.size / 2
            return FloatArray(count * 2) { polygon[(count - 1 - it / 2) * 2 + it % 2] }
        }
    }
}

/**
 * The triangles of this mesh as an outline, all turning the same way so that their union is
 * filled with the non-zero winding rule (where the engine cannot give the outline of the stroke).
 */
public fun StrokeMesh.triangleOutline(): StrokeOutline {
    val polygons = ArrayList<FloatArray>(triangleCount)
    for (partition in partitions) {
        val p = partition.positions
        val indices = partition.indices
        var i = 0
        while (i + 2 < indices.size) {
            val a = (indices[i].toInt() and 0xFFFF) * 2
            val b = (indices[i + 1].toInt() and 0xFFFF) * 2
            val c = (indices[i + 2].toInt() and 0xFFFF) * 2
            i += 3
            val cross = (p[b] - p[a]) * (p[c + 1] - p[a + 1]) - (p[b + 1] - p[a + 1]) * (p[c] - p[a])
            when {
                cross > 0f -> polygons.add(floatArrayOf(p[a], p[a + 1], p[b], p[b + 1], p[c], p[c + 1]))
                cross < 0f -> polygons.add(floatArrayOf(p[a], p[a + 1], p[c], p[c + 1], p[b], p[b + 1]))
                // A flat triangle covers nothing
            }
        }
    }
    return StrokeOutline(polygons)
}
