package io.github.valerioisufi.drawview.model.shape

import io.github.valerioisufi.drawview.model.geometry.Point
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShapeGeometryTest {
    private fun shape(kind: ShapeKind, width: Float = 1f, fill: Int? = null) = ShapeElement(kind, stroke = 0xFF000000.toInt(), widthMm = width, fill = fill)

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.01f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")

    @Test
    fun everyShapeOfABoxStaysInsideItsBoxWithItsOutline() {
        for (kind in ShapeKind.entries.filter { !it.isLine }) {
            val value = shape(kind, width = 2f)
            val paths = ShapeGeometry.paths(value, 40f, 20f)
            assertEquals(1, paths.size, "$kind")
            val path = paths.single()
            assertTrue(path.closed, "$kind")
            for (point in path.points) {
                assertTrue(point.x >= 1f - 1e-3f && point.x <= 39f + 1e-3f, "$kind: $point")
                assertTrue(point.y >= 1f - 1e-3f && point.y <= 19f + 1e-3f, "$kind: $point")
            }
            // The outline reaches the edges of the box it fills
            near(1f, path.points.minOf { it.x }, 0.02f)
            near(39f, path.points.maxOf { it.x }, 0.02f)
            near(1f, path.points.minOf { it.y }, 0.02f)
            near(19f, path.points.maxOf { it.y }, 0.02f)
        }
    }

    @Test
    fun theVerticesOfThePolygons() {
        assertEquals(4, ShapeGeometry.paths(shape(ShapeKind.RECTANGLE), 10f, 10f).single().points.size)
        assertEquals(3, ShapeGeometry.paths(shape(ShapeKind.TRIANGLE), 10f, 10f).single().points.size)
        assertEquals(3, ShapeGeometry.paths(shape(ShapeKind.RIGHT_TRIANGLE), 10f, 10f).single().points.size)
        assertEquals(4, ShapeGeometry.paths(shape(ShapeKind.DIAMOND), 10f, 10f).single().points.size)
        assertEquals(5, ShapeGeometry.paths(shape(ShapeKind.PENTAGON), 10f, 10f).single().points.size)
        assertEquals(6, ShapeGeometry.paths(shape(ShapeKind.HEXAGON), 10f, 10f).single().points.size)
        val apex = ShapeGeometry.paths(shape(ShapeKind.TRIANGLE), 10f, 10f).single().points.first()
        near(5f, apex.x)
        near(0.5f, apex.y)
    }

    @Test
    fun anEllipseIsSmoothAtAnySize() {
        val small = ShapeGeometry.paths(shape(ShapeKind.ELLIPSE, 0.1f), 2f, 2f).single().points
        val large = ShapeGeometry.paths(shape(ShapeKind.ELLIPSE, 0.1f), 200f, 100f).single().points
        assertTrue(small.size >= 4)
        assertTrue(large.size in 100..720, "${large.size}")
        // Every point on the ellipse
        for (p in large) {
            val nx = (p.x - 100f) / 99.95f
            val ny = (p.y - 50f) / 49.95f
            near(1f, nx * nx + ny * ny, 0.001f)
        }
    }

    @Test
    fun aLineGoesAcrossTheMiddleOfItsBoxWithItsHeads() {
        val plain = ShapeGeometry.paths(shape(ShapeKind.LINE), 30f, 4f)
        assertEquals(1, plain.size)
        assertEquals(listOf(Point(0f, 2f), Point(30f, 2f)), plain.single().points)

        val arrow = shape(ShapeKind.LINE).copy(endHead = ShapeHead.OPEN)
        val height = ShapeGeometry.lineHeight(arrow)
        val paths = ShapeGeometry.paths(arrow, 30f, height)
        assertEquals(2, paths.size)
        val head = paths[1]
        assertFalse(head.closed)
        assertEquals(Point(30f, height / 2f), head.points[1])
        // The head is inside the box
        for (p in head.points) assertTrue(p.y in 0f..height, "$p in $height")

        val filled = shape(ShapeKind.LINE).copy(startHead = ShapeHead.FILLED, endHead = ShapeHead.FILLED)
        val filledPaths = ShapeGeometry.paths(filled, 30f, ShapeGeometry.lineHeight(filled))
        assertEquals(3, filledPaths.size)
        assertTrue(filledPaths[1].filled && filledPaths[1].solid && filledPaths[1].closed)
        // The shaft stops at the bases of the heads
        assertTrue(filledPaths[0].points.first().x > 0f)
        assertTrue(filledPaths[0].points.last().x < 30f)
    }

    @Test
    fun theHeadOfAShortLineIsBounded() {
        val arrow = shape(ShapeKind.LINE, width = 2f).copy(endHead = ShapeHead.OPEN)
        val head = ShapeGeometry.paths(arrow, 4f, ShapeGeometry.lineHeight(arrow))[1].points
        assertTrue(head.first().x >= 4f - 4f * 0.45f - 1e-3f, "${head.first()}")
    }

    @Test
    fun theBoxOfALineAndItsEnds() {
        val value = shape(ShapeKind.LINE)
        val start = Point(10f, 10f)
        val end = Point(40f, 50f)
        val box = ShapeGeometry.lineBox(start, end, value)
        near(50f, box.width)
        near(ShapeGeometry.lineHeight(value), box.height)
        val (a, b) = ShapeGeometry.lineEnds(box)
        near(start.x, a.x); near(start.y, a.y)
        near(end.x, b.x); near(end.y, b.y)
        val back = box.toLocal(box.toPage(Point(3f, 1f)))
        near(3f, back.x); near(1f, back.y)
    }

    @Test
    fun anOutlineIsHitNearItAndAFillInsideIt() {
        val empty = shape(ShapeKind.RECTANGLE)
        assertTrue(ShapeGeometry.hits(empty, Point(0.5f, 10f), 40f, 20f, 1f))
        assertTrue(ShapeGeometry.hits(empty, Point(20f, 19.8f), 40f, 20f, 1f))
        assertFalse(ShapeGeometry.hits(empty, Point(20f, 10f), 40f, 20f, 1f))
        val full = shape(ShapeKind.RECTANGLE, fill = 0x33FF0000)
        assertTrue(ShapeGeometry.hits(full, Point(20f, 10f), 40f, 20f, 1f))
        val ellipse = shape(ShapeKind.ELLIPSE, fill = 0x33FF0000)
        assertFalse(ShapeGeometry.hits(ellipse, Point(2f, 2f), 40f, 20f, 0.5f))
        val line = shape(ShapeKind.LINE)
        assertTrue(ShapeGeometry.hits(line, Point(15f, 2.4f), 30f, 4f, 0.5f))
        assertFalse(ShapeGeometry.hits(line, Point(15f, 3.9f), 30f, 4f, 0.5f))
    }

    @Test
    fun theAnchors() {
        val rectangle = ShapeGeometry.anchors(shape(ShapeKind.RECTANGLE, ShapeElement.MIN_WIDTH_MM), 40f, 20f)
        assertEquals(9, rectangle.size)
        assertTrue(rectangle.any { abs(it.x - 20f) < 0.1f && abs(it.y - 10f) < 0.1f })
        val ellipse = ShapeGeometry.anchors(shape(ShapeKind.ELLIPSE), 40f, 20f)
        assertEquals(listOf(Point(20f, 0f), Point(40f, 10f), Point(20f, 20f), Point(0f, 10f), Point(20f, 10f)), ellipse)
        val line = ShapeGeometry.anchors(shape(ShapeKind.LINE), 30f, 4f)
        assertEquals(listOf(Point(0f, 2f), Point(15f, 2f), Point(30f, 2f)), line)
    }

    @Test
    fun aValueOutsideItsBoundsIsNoShape() {
        assertFailsWith<IllegalArgumentException> { shape(ShapeKind.RECTANGLE, width = 0f) }
        assertFailsWith<IllegalArgumentException> { shape(ShapeKind.RECTANGLE, width = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { shape(ShapeKind.RECTANGLE, width = 21f) }
        assertFailsWith<IllegalArgumentException> { shape(ShapeKind.RECTANGLE).copy(endHead = ShapeHead.OPEN) }
        assertFailsWith<IllegalArgumentException> { shape(ShapeKind.LINE, fill = 0) }
    }

    @Test
    fun aKindChangedKeepsTheStyleItCan() {
        val arrow = shape(ShapeKind.LINE).copy(endHead = ShapeHead.FILLED, dash = ShapeDash.DASHED)
        val box = arrow.withKind(ShapeKind.RECTANGLE)
        assertEquals(ShapeHead.NONE, box.endHead)
        assertEquals(ShapeDash.DASHED, box.dash)
        val filled = shape(ShapeKind.ELLIPSE, fill = 0x33000000)
        assertEquals(null, filled.withKind(ShapeKind.LINE).fill)
        assertEquals(ShapeKind.ELLIPSE, filled.withStyleOf(arrow).kind)
    }
}
