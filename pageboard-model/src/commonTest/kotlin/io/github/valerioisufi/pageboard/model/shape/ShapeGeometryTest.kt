package io.github.valerioisufi.pageboard.model.shape

import io.github.valerioisufi.pageboard.model.geometry.Point
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
        }
        // The shapes with no sharp corner reach the edges of their box with their outline
        for (kind in listOf(ShapeKind.RECTANGLE, ShapeKind.ROUNDED_RECTANGLE, ShapeKind.ELLIPSE)) {
            val points = ShapeGeometry.paths(shape(kind, width = 2f), 40f, 20f).single().points
            near(1f, points.minOf { it.x }, 0.02f)
            near(39f, points.maxOf { it.x }, 0.02f)
            near(1f, points.minOf { it.y }, 0.02f)
            near(19f, points.maxOf { it.y }, 0.02f)
        }
    }

    @Test
    fun theMitersOfASharpCornerStayInsideTheBox() {
        // A right triangle in a flat box: its corner at the bottom right is sharp, its miter
        // reaches along the bisector of its angle
        val half = 0.5f
        val path = ShapeGeometry.paths(shape(ShapeKind.RIGHT_TRIANGLE, width = 2 * half), 40f, 10f).single().points
        val corner = path[1]
        val angle = kotlin.math.atan2(path[2].y - path[0].y, path[1].x - path[2].x)
        val reach = half / kotlin.math.sin(angle / 2f)
        val tipX = corner.x + reach * kotlin.math.cos(angle / 2f)
        near(40f, tipX, 0.05f)
        // Its sides close to the box: not shrunk more than needed
        assertTrue(path[0].x < 1f && path[2].y > 9f, "$path")
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
        // Its miter reaches the top of the box: 0.5 mm over the sine of half its angle
        near(0.5f / kotlin.math.sin(kotlin.math.atan2(0.5f, 1f)), apex.y, 0.05f)
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
        // Its tip pulled in by its miter, which ends at the end of the line
        val tip = head.points[1]
        near(30f - 0.5f / kotlin.math.sin(28f * kotlin.math.PI.toFloat() / 180f), tip.x)
        near(height / 2f, tip.y)
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
        assertTrue((head[0] - head[1]).distance <= 4f * 0.45f + 1e-3f, "$head")
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
    fun theDrawingOfAShape() {
        val solid = ShapeGeometry.drawing(shape(ShapeKind.RECTANGLE, fill = 0x33FF0000), 40f, 20f)
        assertEquals(1, solid.fills.size)
        assertEquals(0x33FF0000, solid.fills.single().argb)
        assertTrue(solid.lines.single().closed)

        val dashed = ShapeGeometry.drawing(shape(ShapeKind.RECTANGLE).copy(dash = ShapeDash.DASHED), 40f, 20f)
        assertTrue(dashed.lines.size in 10..30, "${dashed.lines.size} dashes")
        assertTrue(dashed.lines.none { it.closed })
        // A dash turning a corner keeps it
        assertTrue(dashed.lines.any { it.points.size > 2 })

        val dotted = ShapeGeometry.drawing(shape(ShapeKind.LINE).copy(dash = ShapeDash.DOTTED), 30f, 4f)
        assertEquals(Point(0f, 2f), dotted.dots.first().center)
        assertEquals(Point(30f, 2f), dotted.dots.last().center)
        assertTrue(dotted.lines.isEmpty())

        // A filled head is filled in the color of the line, even dotted
        val arrow = shape(ShapeKind.LINE).copy(dash = ShapeDash.DOTTED, endHead = ShapeHead.FILLED)
        val heads = ShapeGeometry.drawing(arrow, 30f, ShapeGeometry.lineHeight(arrow))
        assertEquals(1, heads.fills.size)
        assertEquals(0xFF000000.toInt(), heads.fills.single().argb)
        assertTrue(heads.lines.isEmpty())
    }

    @Test
    fun aHugeBoxHasABoundedDrawing() {
        val value = shape(ShapeKind.ELLIPSE, ShapeElement.MIN_WIDTH_MM).copy(dash = ShapeDash.DOTTED)
        val drawing = ShapeGeometry.drawing(value, 1e7f, 1e7f)
        assertTrue(drawing.dots.size <= ShapeGeometry.MAX_DASHES)
        val dashed = ShapeGeometry.drawing(shape(ShapeKind.RECTANGLE, ShapeElement.MIN_WIDTH_MM).copy(dash = ShapeDash.DASHED), 1e7f, 1e7f)
        assertTrue(dashed.lines.size <= ShapeGeometry.MAX_DASHES)
    }

    @Test
    fun theEraserTouchesTheOutlineOrTheFill() {
        val empty = shape(ShapeKind.RECTANGLE)
        assertTrue(ShapeGeometry.touchesSegment(empty, Point(-5f, 10f), Point(5f, 10f), 0.5f, 40f, 20f))
        assertFalse(ShapeGeometry.touchesSegment(empty, Point(10f, 10f), Point(30f, 10f), 0.5f, 40f, 20f))
        assertTrue(ShapeGeometry.touchesSegment(empty, Point(10f, 10f), Point(30f, 10f), 9f, 40f, 20f), "a wide eraser reaches it")
        val full = shape(ShapeKind.RECTANGLE, fill = 0x33000000)
        assertTrue(ShapeGeometry.touchesSegment(full, Point(10f, 10f), Point(30f, 10f), 0.5f, 40f, 20f))
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
