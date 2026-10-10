package io.github.valerioisufi.drawview.model

import io.github.valerioisufi.drawview.model.geometry.Matrix2D

/**
 * Outline of a stroke: closed polygons (x, y pairs, in stroke units: page millimeters for the
 * strokes of a page) whose union, filled with the non-zero winding rule, is the shape of the
 * stroke. The polygons can overlap (filled one by one, a translucent stroke would be darker where
 * they do): they are turned the same way, so that the parts where they overlap are filled too.
 */
class StrokeOutline(polygons: List<FloatArray>) {
    val polygons: List<FloatArray> = polygons.map { if (signedArea(it) < 0f) reversed(it) else it }

    val isEmpty: Boolean get() = polygons.all { it.size < 6 }

    /** This outline with every vertex mapped by [transform]. */
    fun transformed(transform: Matrix2D): StrokeOutline = StrokeOutline(
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
fun StrokeMesh.triangleOutline(): StrokeOutline {
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
