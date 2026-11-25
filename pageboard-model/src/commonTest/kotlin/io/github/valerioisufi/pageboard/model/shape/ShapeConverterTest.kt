package io.github.valerioisufi.pageboard.model.shape

import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.geometry.Point
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShapeConverterTest {
    private val pen = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 0.8f, color = 0xFF1E88E5.toInt())

    @Test
    fun convertsLineAndArrowPreservingEndsAndStyle() {
        val (lineVal, lineBox) = ShapeConverter.toShapeElement(Line(Point(10f, 20f), Point(50f, 20f)), pen)
        assertEquals(ShapeKind.LINE, lineVal.kind)
        assertEquals(ShapeHead.NONE, lineVal.endHead)
        val (a, b) = ShapeGeometry.lineEnds(lineBox)
        assertTrue((a - Point(10f, 20f)).distance < 0.05f)
        assertTrue((b - Point(50f, 20f)).distance < 0.05f)

        val (arrowVal, _) = ShapeConverter.toShapeElement(Arrow(Point(0f, 0f), Point(30f, 40f), 4f), pen)
        assertEquals(ShapeKind.LINE, arrowVal.kind)
        assertEquals(ShapeHead.OPEN, arrowVal.endHead)
        assertEquals(pen.color, arrowVal.stroke)
    }

    @Test
    fun convertsRectangleEllipsePolylineAndFreePolygon() {
        val rect = Polygon(listOf(Point(10f, 10f), Point(40f, 10f), Point(40f, 30f), Point(10f, 30f)))
        val (rectVal, rectBox) = ShapeConverter.toShapeElement(rect, pen)
        assertEquals(ShapeKind.RECTANGLE, rectVal.kind)
        assertTrue(abs(rectBox.width - (30f + pen.size)) < 0.1f)

        val tri = Polygon(listOf(Point(20f, 10f), Point(40f, 30f), Point(10f, 30f)))
        val (triVal, triBox) = ShapeConverter.toShapeElement(tri, pen)
        assertEquals(ShapeKind.FREE_POLYGON, triVal.kind)
        assertNotNull(triVal.vertices)
        assertEquals(3, ShapeGeometry.verticesInPage(triBox, triVal).size)

        val poly = Polyline(listOf(Point(0f, 0f), Point(10f, 15f), Point(25f, 5f)))
        val (polyVal, polyBox) = ShapeConverter.toShapeElement(poly, pen)
        assertEquals(ShapeKind.POLYLINE, polyVal.kind)
        val restored = ShapeGeometry.verticesInPage(polyBox, polyVal)
        assertEquals(3, restored.size)
        assertTrue((restored[1] - Point(10f, 15f)).distance < 0.05f)

        val ell = Ellipse(Point(50f, 60f), 15f, 10f, 0f)
        val (ellVal, ellBox) = ShapeConverter.toShapeElement(ell, pen)
        assertEquals(ShapeKind.ELLIPSE, ellVal.kind)
        assertTrue(abs(ellBox.center.x - 50f) < 0.05f && abs(ellBox.center.y - 60f) < 0.05f)
    }
}
