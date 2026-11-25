package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.geometry.AffineTransform
import io.github.valerioisufi.pageboard.model.geometry.HitTesting
import io.github.valerioisufi.pageboard.model.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutlineHitTestingTest {

    private fun squareOutlineMesh(left: Float, top: Float, right: Float, bottom: Float): StrokeMesh =
        StrokeMesh(
            positions = FloatArray(0),
            indices = ShortArray(0),
            color = 0xFF000000.toInt(),
            tool = StrokeTool.MARKER,
            size = 2f,
            outline = StrokeOutline(
                listOf(floatArrayOf(left, top, right, top, right, bottom, left, bottom))
            )
        )

    @Test
    fun outlineOnlyMeshSupportsEmptyBoundsAndTransform() {
        val mesh = squareOutlineMesh(10f, 20f, 30f, 40f)
        assertFalse(mesh.isEmpty)
        assertTrue(mesh.positions.isEmpty())
        assertTrue(mesh.indices.isEmpty())
        assertEquals(Rect(10f, 20f, 30f, 40f), mesh.computeBounds())

        val moved = mesh.transformed(AffineTransform(tx = 5f, ty = -3f).toMatrix())
        assertEquals(Rect(15f, 17f, 35f, 37f), moved.computeBounds())
    }

    @Test
    fun outlineCapsuleHitTestingDetectsInteriorEdgeAndMisses() {
        val mesh = squareOutlineMesh(10f, 10f, 20f, 20f)
        // Segment wholly inside the outline polygon
        assertTrue(HitTesting.meshIntersectsCapsule(mesh, 12f, 15f, 18f, 15f, radius = 0.2f))
        // Segment outside but within capsule radius of an edge
        assertTrue(HitTesting.meshIntersectsCapsule(mesh, 5f, 9f, 25f, 9f, radius = 1.5f))
        // Segment safely outside beyond radius
        assertFalse(HitTesting.meshIntersectsCapsule(mesh, 5f, 5f, 25f, 5f, radius = 1.5f))
    }

    @Test
    fun outlinePolygonHitTestingDetectsOverlapContainmentAndDisjoint() {
        val mesh = squareOutlineMesh(20f, 20f, 40f, 40f)
        // Lasso surrounding the outline
        val outer = floatArrayOf(10f, 10f, 50f, 10f, 50f, 50f, 10f, 50f)
        assertTrue(HitTesting.meshIntersectsPolygon(mesh, outer, HitTesting.polygonBounds(outer)))

        // Small lasso entirely inside the outline polygon
        val inner = floatArrayOf(25f, 25f, 35f, 25f, 35f, 35f, 25f, 35f)
        assertTrue(HitTesting.meshIntersectsPolygon(mesh, inner, HitTesting.polygonBounds(inner)))

        // Crossing lasso edge
        val crossing = floatArrayOf(35f, 10f, 60f, 10f, 60f, 30f, 35f, 30f)
        assertTrue(HitTesting.meshIntersectsPolygon(mesh, crossing, HitTesting.polygonBounds(crossing)))

        // Disjoint lasso
        val disjoint = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertFalse(HitTesting.meshIntersectsPolygon(mesh, disjoint, HitTesting.polygonBounds(disjoint)))
    }
}
