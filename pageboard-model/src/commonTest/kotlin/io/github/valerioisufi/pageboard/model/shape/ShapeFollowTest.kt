package io.github.valerioisufi.pageboard.model.shape

import io.github.valerioisufi.pageboard.model.geometry.Point
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A shape that snapped following the pen still down (see `docs/plans/shape-adjust.md`). */
class ShapeFollowTest {

    private fun assertNear(expected: Point, actual: Point) {
        assertTrue((expected - actual).distance < 0.001f, "expected $expected, was $actual")
    }

    @Test
    fun aLineTurnsAndGrowsFromItsStart() {
        val line = Line(Point(0f, 0f), Point(10f, 0f))
        val moved = line.followed(line.pivot, Point(10f, 0f), Point(0f, 20f)) as Line
        assertNear(Point(0f, 0f), moved.start)
        assertNear(Point(0f, 20f), moved.end)
    }

    @Test
    fun anArrowKeepsTheLengthOfItsHead() {
        val arrow = Arrow(Point(0f, 0f), Point(10f, 0f), headLength = 3f)
        val moved = arrow.followed(arrow.pivot, Point(10f, 0f), Point(30f, 0f)) as Arrow
        assertNear(Point(30f, 0f), moved.tip)
        assertEquals(3f, moved.headLength)
    }

    @Test
    fun aClosedShapeTurnsAndGrowsAroundItsMiddle() {
        val square = Polygon(listOf(Point(0f, 0f), Point(10f, 0f), Point(10f, 10f), Point(0f, 10f)))
        assertNear(Point(5f, 5f), square.pivot)
        // The corner held at (10, 10) taken twice as far: twice as large around the middle
        val larger = square.followed(square.pivot, Point(10f, 10f), Point(15f, 15f)) as Polygon
        assertNear(Point(-5f, -5f), larger.vertices[0])
        assertNear(Point(15f, 15f), larger.vertices[2])

        val circle = Ellipse(Point(0f, 0f), 5f, 5f)
        val turned = circle.followed(circle.pivot, Point(5f, 0f), Point(0f, 10f)) as Ellipse
        assertEquals(10f, turned.radiusX, 0.001f)
        assertEquals((PI / 2).toFloat(), turned.rotation, 0.001f)
    }

    @Test
    fun aPenOnThePivotLeavesTheShapeAsItIs() {
        val line = Line(Point(0f, 0f), Point(10f, 0f))
        assertSame(line, line.followed(line.pivot, Point(10f, 0f), Point(0.2f, 0f)))
    }
}
