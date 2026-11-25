package io.github.valerioisufi.pageboard.ink

import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.shape.Polygon
import io.github.valerioisufi.pageboard.model.shape.Shape
import io.github.valerioisufi.pageboard.model.shape.strokeInputs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The strokes of the shapes as Google Ink models them: their corners stay sharp. */
class ShapeStrokeTest {

    @Test
    fun theCornersOfARectangleStaySharp() {
        val corners = listOf(Point(10f, 10f), Point(70f, 10f), Point(70f, 40f), Point(10f, 40f))
        assertCornersReached(Polygon(corners), corners, center = Point(40f, 25f))
    }

    @Test
    fun theCornersOfATriangleStaySharp() {
        val corners = listOf(Point(40f, 10f), Point(70f, 60f), Point(10f, 60f))
        assertCornersReached(Polygon(corners), corners, center = Point(40f, 43f))
    }

    /**
     * The ink of [shape] reaches past each of [corners], away from [center], by nearly half its
     * width, as a round tip on the corner does; a corner cut short would not.
     */
    private fun assertCornersReached(shape: Shape, corners: List<Point>, center: Point) {
        for (family in listOf(StrokeTool.PRESSURE_PEN, StrokeTool.MARKER)) {
            val brush = BrushConfig(tool = family, size = SIZE, color = BLACK, epsilon = 0.02f)
            val drawn = listOf(StrokePoint(0f, 0f, 0.5f, 0L), StrokePoint(1f, 0f, 0.5f, 400L))
            val mesh = assertNotNull(InkStrokeEngine.buildMesh(shape.strokeInputs(drawn), brush, InputToolType.STYLUS))
            for (corner in corners) {
                val outward = (corner - center).let { it * (1f / it.distance) }
                var reach = Float.NEGATIVE_INFINITY
                val vertexArrays = mesh.outline?.polygons ?: mesh.partitions.map { it.positions }
                for (p in vertexArrays) {
                    for (i in 0 until p.size / 2) {
                        val dx = p[i * 2] - corner.x
                        val dy = p[i * 2 + 1] - corner.y
                        if (sqrt(dx * dx + dy * dy) < SIZE * 2f) reach = maxOf(reach, dx * outward.x + dy * outward.y)
                    }
                }
                assertTrue(reach >= SIZE / 2f * 0.8f, "$family: ink reaches $reach mm past the corner $corner, its half width ${SIZE / 2f}")
            }
        }
    }

    private companion object {
        const val SIZE = 1.0f
        const val BLACK = 0xFF000000.toInt()
    }
}
