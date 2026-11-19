package io.github.valerioisufi.drawview.model.shape

import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.geometry.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapeRecognizerTest {

    // ==========================================================
    // Lines and arrows
    // ==========================================================

    @Test
    fun aLineNearlyLevelIsLevel() {
        val line = assertIs<Line>(recognize(drawn(listOf(Point(20f, 50f), Point(90f, 52f)))))
        assertNear(Point(20f, 50f), line.start, 1f)
        assertEquals(line.start.y, line.end.y, 0.001f, "levelled")
        assertEquals(70f, line.end.x - line.start.x, 1.5f)
    }

    @Test
    fun aLineAtAnotherAngleKeepsIt() {
        val end = Point(20f + 60f * cos(30.0 * PI / 180).toFloat(), 20f + 60f * sin(30.0 * PI / 180).toFloat())
        val line = assertIs<Line>(recognize(drawn(listOf(Point(20f, 20f), end))))
        assertEquals(30f, degrees(line.end - line.start), 2f)
    }

    @Test
    fun aLineNearlyDiagonalIsDiagonal() {
        val line = assertIs<Line>(recognize(drawn(listOf(Point(10f, 10f), Point(50f, 53f)))))
        assertEquals(45f, degrees(line.end - line.start), 0.01f)
    }

    @Test
    fun aFlickOfThePenAtTheEndOfALineIsNoSegment() {
        val line = assertIs<Line>(recognize(drawn(listOf(Point(10f, 40f), Point(80f, 40f), Point(82f, 37f)))))
        assertEquals(0f, degrees(line.end - line.start), 0.01f)
        assertEquals(70f, line.end.x - line.start.x, 2f)
    }

    @Test
    fun aShaftWithAHeadDrawnBackAndForthIsAnArrow() {
        val tip = Point(80f, 30f)
        val arrow = assertIs<Arrow>(recognize(drawn(listOf(Point(20f, 30f), tip, Point(70f, 24f), tip, Point(70f, 36f)))))
        assertNear(tip, arrow.tip, 1.5f)
        assertEquals(0f, degrees(arrow.tip - arrow.tail), 0.01f)
        assertTrue(arrow.headLength in 8f..14f, "head of ${arrow.headLength}")
    }

    @Test
    fun aShaftWithAClosedHeadIsAnArrow() {
        val tip = Point(30f, 90f)
        val arrow = assertIs<Arrow>(recognize(drawn(listOf(Point(30f, 20f), tip, Point(24f, 80f), Point(36f, 80f), tip))))
        assertNear(tip, arrow.tip, 1.5f)
        assertEquals(90f, degrees(arrow.tip - arrow.tail), 0.01f)
    }

    // ==========================================================
    // Broken lines
    // ==========================================================

    @Test
    fun theAxesOfAPlotAreABrokenLineAtRightAngles() {
        val shape = assertIs<Polyline>(recognize(drawn(listOf(Point(20f, 10f), Point(20f, 70f), Point(90f, 70f)))))
        assertEquals(3, shape.vertices.size)
        val (a, b, c) = shape.vertices
        assertEquals(90f, degrees(b - a), 0.01f)
        assertEquals(0f, degrees(c - b), 0.01f)
    }

    @Test
    fun aZigzagOfFourSegmentsIsABrokenLine() {
        val shape = assertIs<Polyline>(recognize(drawn(listOf(Point(10f, 50f), Point(30f, 20f), Point(50f, 50f), Point(70f, 20f), Point(90f, 50f)))))
        assertEquals(5, shape.vertices.size)
    }

    // ==========================================================
    // Ellipses
    // ==========================================================

    @Test
    fun aRoundLoopIsACircle() {
        val ellipse = assertIs<Ellipse>(recognize(drawn(ellipse(Point(50f, 50f), 25f, 24f, 0f))))
        assertTrue(ellipse.isCircle)
        assertNear(Point(50f, 50f), ellipse.center, 1.5f)
        assertEquals(24.5f, ellipse.radiusX, 1.5f)
    }

    @Test
    fun aCircleDrawnPastItsStartIsACircle() {
        val ellipse = assertIs<Ellipse>(recognize(drawn(ellipse(Point(50f, 50f), 20f, 20f, 0f, turns = 1.15f))))
        assertTrue(ellipse.isCircle)
        assertNear(Point(50f, 50f), ellipse.center, 1.5f)
        assertEquals(20f, ellipse.radiusX, 1.5f)
    }

    @Test
    fun anEllipseKeepsItsAxes() {
        val ellipse = assertIs<Ellipse>(recognize(drawn(ellipse(Point(60f, 40f), 40f, 18f, (30 * PI / 180).toFloat()))))
        assertTrue(!ellipse.isCircle)
        assertEquals(40f, ellipse.radiusX, 2f)
        assertEquals(18f, ellipse.radiusY, 2f)
        val rotation = ((ellipse.rotation * 180 / PI).toFloat() % 180f + 180f) % 180f
        assertEquals(30f, rotation, 3f)
    }

    // ==========================================================
    // Polygons
    // ==========================================================

    @Test
    fun aBoxIsARectangleOnTheAxes() {
        val polygon = assertIs<Polygon>(recognize(drawn(closed(Point(20f, 20f), Point(80f, 21f), Point(81f, 50f), Point(19f, 51f)))))
        assertEquals(4, polygon.vertices.size)
        val xs = polygon.vertices.map { it.x }.toSet()
        val ys = polygon.vertices.map { it.y }.toSet()
        assertEquals(2, xs.size, "vertical sides")
        assertEquals(2, ys.size, "horizontal sides")
        assertEquals(61f, xs.max() - xs.min(), 2f)
        assertEquals(30f, ys.max() - ys.min(), 2f)
        // Started where the stroke started
        assertNear(Point(20f, 20f), polygon.vertices.first(), 2f)
    }

    @Test
    fun aBoxNearlySquareIsASquare() {
        val polygon = assertIs<Polygon>(recognize(drawn(closed(Point(20f, 20f), Point(60f, 20f), Point(60f, 57f), Point(20f, 57f)))))
        val sides = polygon.vertices.indices.map { (polygon.vertices[(it + 1) % 4] - polygon.vertices[it]).distance }
        assertTrue(sides.all { abs(it - sides[0]) < 0.01f }, "sides $sides")
    }

    @Test
    fun aTriangleIsATriangle() {
        val polygon = assertIs<Polygon>(recognize(drawn(closed(Point(50f, 10f), Point(85f, 70f), Point(15f, 71f)))))
        assertEquals(3, polygon.vertices.size)
        // Its base, nearly level, is level
        assertEquals(polygon.vertices[1].y, polygon.vertices[2].y, 0.01f)
    }

    @Test
    fun aHexagonIsAPolygon() {
        val corners = (0 until 6).map { i -> Point(50f + 30f * cos(i * PI / 3).toFloat(), 50f + 30f * sin(i * PI / 3).toFloat()) }
        val polygon = assertIs<Polygon>(recognize(drawn(closed(*corners.toTypedArray()))))
        assertEquals(6, polygon.vertices.size)
    }

    // ==========================================================
    // No shape
    // ==========================================================

    @Test
    fun handwritingIsNoShape() {
        assertNull(recognize(drawn(word(), wobble = 0f)))
    }

    @Test
    fun aSpiralIsNoShape() {
        val spiral = (0..400).map { i ->
            val t = i / 400f * 3f * 2f * PI.toFloat()
            val r = 4f + t * 2f
            Point(50f + r * cos(t), 50f + r * sin(t))
        }
        assertNull(recognize(drawn(spiral, wobble = 0f)))
    }

    @Test
    fun aScribbleIsNoShape() {
        val random = Random(7)
        val scribble = (0..12).map { Point(20f + random.nextFloat() * 50f, 20f + random.nextFloat() * 40f) }
        assertNull(recognize(drawn(scribble)))
    }

    @Test
    fun aCurveIsNoShape() {
        val s = (0..200).map { i ->
            val t = i / 200f
            Point(20f + 60f * t, 50f + 20f * sin(t * 2f * PI.toFloat()))
        }
        assertNull(recognize(drawn(s, wobble = 0f)))
    }

    @Test
    fun anOpenArcIsNoShape() {
        assertNull(recognize(drawn(ellipse(Point(50f, 50f), 25f, 25f, 0f, turns = 0.6f))))
    }

    @Test
    fun aDotIsNoShape() {
        assertNull(recognize(drawn(listOf(Point(50f, 50f), Point(51f, 50.5f)))))
    }

    @Test
    fun theShapesAreRecognizedWhateverTheHand() {
        val shapes: List<Pair<List<Point>, (Shape?) -> Boolean>> = listOf(
            listOf(Point(20f, 50f), Point(90f, 52f)) to { it is Line },
            listOf(Point(20f, 30f), Point(80f, 30f), Point(70f, 24f), Point(80f, 30f), Point(70f, 36f)) to { it is Arrow },
            listOf(Point(20f, 10f), Point(20f, 70f), Point(90f, 70f)) to { it is Polyline && it.vertices.size == 3 },
            ellipse(Point(50f, 50f), 25f, 24f, 0f) to { it is Ellipse && it.isCircle },
            ellipse(Point(50f, 50f), 30f, 15f, 0.3f) to { it is Ellipse && !it.isCircle },
            closed(Point(20f, 20f), Point(80f, 21f), Point(81f, 50f), Point(19f, 51f)) to { it is Polygon && it.vertices.size == 4 },
            closed(Point(50f, 10f), Point(85f, 70f), Point(15f, 71f)) to { it is Polygon && it.vertices.size == 3 },
            ellipse(Point(50f, 50f), 25f, 25f, 0f, turns = 0.6f) to { it == null },
            (0..200).map { i -> Point(20f + 0.3f * i, 50f + 20f * sin(i / 200f * 2f * PI.toFloat())) } to { it == null },
            roundedBox(Point(20f, 20f), Point(80f, 55f), 4f) to { it is Polygon && it.vertices.size == 4 },
            word() to { it == null },
        )
        val wrong = mutableListOf<String>()
        for ((index, entry) in shapes.withIndex()) {
            for (seed in 1..30) for (wobble in listOf(0.3f, 0.8f, 1.5f)) {
                val shape = recognize(drawn(entry.first, wobble = wobble, seed = seed))
                if (!entry.second(shape)) wrong += "shape $index, seed $seed, wobble $wobble: $shape"
            }
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} wrong:\n" + wrong.take(20).joinToString("\n"))
    }

    // ==========================================================
    // The inputs of a shape
    // ==========================================================

    @Test
    fun theInputsOfAShapeFollowItEvenly() {
        val drawn = listOf(StrokePoint(0f, 0f, 0.2f, 1000L), StrokePoint(5f, 0f, 0.6f, 1010L), StrokePoint(10f, 0f, 0.5f, 1020L))
        val inputs = Polygon(listOf(Point(0f, 0f), Point(20f, 0f), Point(20f, 10f))).strokeInputs(drawn)
        assertTrue(inputs.all { it.pressure == 0.5f }, "the median pressure")
        assertEquals(1000L, inputs.first().timestampMs)
        assertTrue(inputs.zipWithNext().all { (a, b) -> b.timestampMs > a.timestampMs }, "times going on")
        assertTrue(inputs.zipWithNext().all { (a, b) -> Point(b.x - a.x, b.y - a.y).distance <= SHAPE_INPUT_SPACING_MM + 0.001f })
        assertNear(Point(0f, 0f), Point(inputs.last().x, inputs.last().y), 0.001f)
        // A stop at each corner
        assertTrue(inputs.count { it.x == 20f && it.y == 0f } > 1)
    }

    @Test
    fun theHeadOfAnArrowIsAtItsTip() {
        val arrow = Arrow(Point(0f, 0f), Point(40f, 0f), 10f)
        val (left, right) = arrow.wings
        assertEquals(10f, (left - arrow.tip).distance, 0.001f)
        assertTrue(left.x < 40f && right.x < 40f && left.y * right.y < 0f)
    }

    // ==========================================================
    // Strokes drawn by hand
    // ==========================================================

    private fun recognize(points: List<Point>) = ShapeRecognizer.recognize(points, TOLERANCE)

    /**
     * The stroke a hand draws along [path]: a point every half millimeter, a slow drift of the hand
     * up to [wobble] millimeters (smooth over the page), a jitter, and the pen held still at the end.
     */
    private fun drawn(path: List<Point>, wobble: Float = 0.8f, seed: Int = 1): List<Point> {
        val random = Random(seed)
        val dense = mutableListOf<Point>()
        for ((a, b) in path.zipWithNext()) {
            val steps = maxOf(1, ((b - a).distance / 0.5f).toInt())
            for (i in 0 until steps) dense += a + (b - a) * (i.toFloat() / steps)
        }
        dense += path.last()
        val phases = List(4) { random.nextFloat() * 6f }
        val result = dense.map { p ->
            val drift = Point(
                sin(p.x / 17f + phases[0]) * 0.6f + sin(p.y / 23f + phases[1]) * 0.4f,
                sin(p.y / 19f + phases[2]) * 0.6f + sin(p.x / 29f + phases[3]) * 0.4f
            ) * wobble
            p + drift + Point(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f) * 0.3f
        }
        val last = result.last()
        val hold = (0 until 30).map { last + Point(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f) * 0.4f }
        return result + hold
    }

    /** A cursive word: loops along a line. */
    private fun word(): List<Point> = (0..400).map { i ->
        val t = i / 400f * 5f * 2f * PI.toFloat()
        Point(10f + t * 2.2f + 4f * cos(t + PI.toFloat() / 2f), 50f - 5f * sin(t + PI.toFloat() / 2f))
    }

    /** A box from [topLeft] to [bottomRight] whose corners are rounded with [radius]. */
    private fun roundedBox(topLeft: Point, bottomRight: Point, radius: Float): List<Point> {
        val (l, t) = topLeft
        val (r, b) = bottomRight
        fun arc(cx: Float, cy: Float, from: Double) = (0..8).map { i ->
            val angle = from + i / 8.0 * PI / 2
            Point(cx + radius * cos(angle).toFloat(), cy + radius * sin(angle).toFloat())
        }
        return arc(r - radius, t + radius, -PI / 2) + arc(r - radius, b - radius, 0.0) + arc(l + radius, b - radius, PI / 2) +
            arc(l + radius, t + radius, PI) + Point(r - radius, t)
    }

    private fun closed(vararg corners: Point): List<Point> = corners.toList() + corners.first()

    private fun ellipse(center: Point, rx: Float, ry: Float, rotation: Float, turns: Float = 1f): List<Point> =
        (0..200).map { i ->
            val angle = i / 200f * turns * 2f * PI.toFloat()
            Point(rx * cos(angle), ry * sin(angle)).rotated(rotation) + center
        }

    private fun degrees(v: Point): Float = (atan2(v.y, v.x) * 180.0 / PI).toFloat()

    private fun assertNear(expected: Point, actual: Point, within: Float) {
        assertTrue((expected - actual).distance <= within, "expected $expected, got $actual")
    }

    private companion object {
        const val TOLERANCE = 0.6f
    }
}
