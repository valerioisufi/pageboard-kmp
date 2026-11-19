package io.github.valerioisufi.pageboard.ink

import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.remodelingBrush
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stroke modeled from its inputs at once ([InkStrokeEngine.buildMesh]) has the shape of the
 * stroke of the same inputs added one by one, as the editor adds them. Without behaviors over time
 * or distance (the marker, the highlighter) Ink gives the same vertices but for a rounding; with
 * them (the pressure pen, the dashed line) it simplifies the outline in another way, and the
 * shapes differ at the edges (see `docs/plans/performance.md`, phase 6).
 */
class WholeStrokeModelingTest {

    @Test
    fun everyStrokeKeepsItsShape() {
        for (tool in listOf(StrokeTool.PRESSURE_PEN, StrokeTool.MARKER, StrokeTool.HIGHLIGHTER, StrokeTool.DASHED_LINE)) {
            for (toolType in listOf(InputToolType.STYLUS, InputToolType.TOUCH)) {
                for (size in listOf(0.3f, 2f, 8f)) {
                    for ((name, points) in strokes()) {
                        val label = "$name, $tool, $toolType, size $size"
                        val brush = remodelingBrush(tool, size, COLOR)
                        val drawn = oneByOne(points, brush, toolType)
                        val whole = InkStrokeEngine.buildMesh(points, brush, toolType)
                        if (drawn == null || whole == null) {
                            assertEquals(drawn == null, whole == null, "$label: a mesh on one side only")
                            continue
                        }
                        assertEquals(drawn.points, whole.points, "$label: inputs")
                        assertEquals(drawn.inputToolType, whole.inputToolType, "$label: tool type")
                        // Measured: the pen 1% of its size for the strokes as written, 3.5% for a
                        // scribble; the dashes of a dashed line move along it, 14% in a scribble
                        val tolerance = when (tool) {
                            StrokeTool.PRESSURE_PEN -> max(3 * CELL, 0.05f * size)
                            StrokeTool.DASHED_LINE -> max(0.1f, 0.25f * size)
                            else -> 3 * CELL
                        }
                        val apart = farthestApart(drawn, whole)
                        assertTrue(apart <= tolerance, "$label: the shapes are $apart mm apart (at most $tolerance)")
                    }
                }
            }
        }
    }

    private fun oneByOne(points: List<StrokePoint>, brush: BrushConfig, toolType: InputToolType): StrokeMesh? {
        val engine = InkStrokeEngine()
        return try {
            engine.startStroke(brush)
            for (p in points) engine.addInput(p.x, p.y, p.pressure, p.timestampMs, toolType)
            engine.finishStroke()
        } finally {
            engine.release()
        }
    }

    /** Strokes as they are written, and as they come from other devices or from old documents. */
    private fun strokes(): List<Pair<String, List<StrokePoint>>> {
        val random = Random(7)
        return listOf(
            "a word" to List(120) { i -> StrokePoint(20f + i * 0.15f, 30f + 2.5f * sin(i * 0.3f), 0.4f + 0.3f * sin(i * 0.1f), 1_000L + i * 8L) },
            "a loop" to List(60) { i -> StrokePoint(50f + 4f * cos(i * 0.2f), 50f + 4f * sin(i * 0.2f), 0.6f, i * 7L + 3L) },
            "a long line" to List(1_000) { i -> StrokePoint(i * 0.05f, 10f + 0.01f * (i % 37), 0.5f + 0.4f * sin(i * 0.01f), 1L + i * 4L) },
            "a dot" to listOf(StrokePoint(5f, 5f, 0.5f, 100L)),
            "two inputs" to listOf(StrokePoint(5f, 5f, 0.5f, 100L), StrokePoint(6f, 5.5f, 0.7f, 108L)),
            "repeated inputs" to List(40) { i -> StrokePoint(10f + (i / 3) * 0.4f, 12f, 0.5f, 200L + (i / 3) * 8L) },
            "inputs at one time" to List(30) { i -> StrokePoint(10f + i * 0.3f, 12f + i * 0.1f, 0.5f, 500L + (i / 5) * 16L) },
            "time going back" to List(30) { i -> StrokePoint(10f + i * 0.3f, 12f, 0.5f, 500L + (if (i % 4 == 3) -20L else 0L) + i * 8L) },
            "no times" to List(30) { i -> StrokePoint(10f + i * 0.3f, 12f + i * 0.2f, 0.5f, 0L) },
            "pressure out of range" to List(30) { i -> StrokePoint(10f + i * 0.3f, 12f, if (i % 2 == 0) 1.4f else -0.3f, 300L + i * 8L) },
            "no pressure first" to List(30) { i -> StrokePoint(10f + i * 0.3f, 12f, if (i == 0) -1f else 0.6f, 300L + i * 8L) },
            "a scribble" to List(300) { i -> StrokePoint(30f + random.nextFloat() * 6f, 30f + random.nextFloat() * 6f, random.nextFloat(), 10L + i * random.nextLong(0L, 12L)) }
        )
    }

    /**
     * How far apart the filled shapes of [a] and [b] are, in millimeters: the depth of the widest
     * area that only one of them covers, both drawn on a grid of [CELL].
     */
    private fun farthestApart(a: StrokeMesh, b: StrokeMesh): Float {
        val ra = a.computeBounds()
        val rb = b.computeBounds()
        val left = min(ra.left, rb.left) - MARGIN
        val top = min(ra.top, rb.top) - MARGIN
        val width = ((max(ra.right, rb.right) + MARGIN - left) / CELL).toInt() + 1
        val height = ((max(ra.bottom, rb.bottom) + MARGIN - top) / CELL).toInt() + 1
        val coveredA = cover(a, left, top, width, height)
        val coveredB = cover(b, left, top, width, height)

        // Distance in cells from the cells both cover or neither covers
        val depth = IntArray(width * height) { -1 }
        val queue = IntArray(width * height)
        var head = 0
        var tail = 0
        for (i in depth.indices) {
            if (coveredA[i] == coveredB[i]) {
                depth[i] = 0
                queue[tail++] = i
            }
        }
        var deepest = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            val y = i / width
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                val j = ny * width + nx
                if (depth[j] < 0) {
                    depth[j] = depth[i] + 1
                    deepest = max(deepest, depth[j])
                    queue[tail++] = j
                }
            }
        }
        return deepest * CELL
    }

    /** The cells whose centers the triangles of [mesh] cover. */
    private fun cover(mesh: StrokeMesh, left: Float, top: Float, width: Int, height: Int): BooleanArray {
        val covered = BooleanArray(width * height)
        for (partition in mesh.partitions) {
            val p = partition.positions
            val indices = partition.indices
            for (t in 0 until indices.size / 3) {
                val i0 = indices[3 * t].toInt() and 0xFFFF
                val i1 = indices[3 * t + 1].toInt() and 0xFFFF
                val i2 = indices[3 * t + 2].toInt() and 0xFFFF
                val x0 = (p[2 * i0] - left) / CELL
                val y0 = (p[2 * i0 + 1] - top) / CELL
                val x1 = (p[2 * i1] - left) / CELL
                val y1 = (p[2 * i1 + 1] - top) / CELL
                val x2 = (p[2 * i2] - left) / CELL
                val y2 = (p[2 * i2 + 1] - top) / CELL
                val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
                if (area == 0f) continue
                val minX = max(0, floor(min(x0, min(x1, x2))).toInt())
                val maxX = min(width - 1, ceil(max(x0, max(x1, x2))).toInt())
                val minY = max(0, floor(min(y0, min(y1, y2))).toInt())
                val maxY = min(height - 1, ceil(max(y0, max(y1, y2))).toInt())
                for (y in minY..maxY) for (x in minX..maxX) {
                    val cx = x + 0.5f
                    val cy = y + 0.5f
                    val w0 = ((x1 - cx) * (y2 - cy) - (x2 - cx) * (y1 - cy)) / area
                    val w1 = ((x2 - cx) * (y0 - cy) - (x0 - cx) * (y2 - cy)) / area
                    if (w0 >= 0f && w1 >= 0f && 1f - w0 - w1 >= 0f) covered[y * width + x] = true
                }
            }
        }
        return covered
    }

    private companion object {
        const val COLOR = 0xFF2040A0.toInt()

        /** The side of a cell of the grid the shapes are compared on, in millimeters. */
        const val CELL = 0.01f
        const val MARGIN = 0.05f
    }
}
