package io.github.valerioisufi.drawview.model.shape

import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShapeConstraintsTest {
    private fun near(expected: Float, actual: Float, tolerance: Float = 0.01f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")

    @Test
    fun aLineNearlyLevelBecomesLevel() {
        val line = ShapeConstraints.line(Point(0f, 0f), Point(50f, 2f), forced = false)
        assertTrue(line.snapped)
        near(0f, line.angleDegrees)
        near(0f, line.end.y)
        near(Point(50f, 2f).distance, line.length(Point(0f, 0f)))
    }

    @Test
    fun aLineUpAtFortyFiveDegreesIsReadCounterclockwise() {
        // y goes down on the page: up and to the right is 45°
        val line = ShapeConstraints.line(Point(0f, 0f), Point(30f, -31f), forced = false)
        assertTrue(line.snapped)
        near(45f, line.angleDegrees)
        near(line.end.x, -line.end.y)
    }

    @Test
    fun aLineBetweenTheStepsStaysWhereItIs() {
        val pointer = Point(40f, 15f) // about 20.6° down from the right, read as 339.4°
        val line = ShapeConstraints.line(Point(0f, 0f), pointer, forced = false)
        assertFalse(line.snapped)
        assertEquals(pointer, line.end)
        val forced = ShapeConstraints.line(Point(0f, 0f), pointer, forced = true)
        assertTrue(forced.snapped)
        near(345f, forced.angleDegrees)
    }

    @Test
    fun aBoxNearlySquareBecomesSquare() {
        val box = ShapeConstraints.box(Point(10f, 10f), Point(40f, 38.5f), forced = false, fromCenter = false)
        assertTrue(box.regular)
        assertEquals(Rect(10f, 10f, 40f, 40f), box.box)
        val free = ShapeConstraints.box(Point(10f, 10f), Point(40f, 30f), forced = false, fromCenter = false)
        assertFalse(free.regular)
        assertEquals(Rect(10f, 10f, 40f, 30f), free.box)
        val forced = ShapeConstraints.box(Point(10f, 10f), Point(40f, 30f), forced = true, fromCenter = false)
        assertEquals(Rect(10f, 10f, 40f, 40f), forced.box)
    }

    @Test
    fun aBoxDrawnUpAndLeftOrFromItsCenter() {
        val back = ShapeConstraints.box(Point(40f, 40f), Point(10f, 20f), forced = false, fromCenter = false)
        assertEquals(Rect(10f, 20f, 40f, 40f), back.box)
        val centered = ShapeConstraints.box(Point(40f, 40f), Point(50f, 45f), forced = false, fromCenter = true)
        assertEquals(Rect(30f, 35f, 50f, 45f), centered.box)
    }
}
