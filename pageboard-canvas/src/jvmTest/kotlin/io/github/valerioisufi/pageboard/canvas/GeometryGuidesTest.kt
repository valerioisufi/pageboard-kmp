package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.geometry.Offset
import io.github.valerioisufi.pageboard.canvas.controller.GeometryGuidesManager
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.model.Document
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class GeometryGuidesTest {

    private fun withController(block: (PageboardController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = PageboardController(document = Document(), coroutineScope = scope)
        try {
            block(controller)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rulerTogglesAndProjectsInkOntoTopEdge() = withController { controller ->
        val guides = controller.geometryGuides
        assertFalse(guides.isRulerVisible)
        guides.toggleRuler()
        val ruler = assertNotNull(guides.ruler)
        assertTrue(guides.isRulerVisible)

        val topEdgeY = ruler.centerY - ruler.thicknessPx / 2f
        val start = Offset(ruler.centerX - 60f, topEdgeY + 6f)
        assertTrue(guides.beginInkStroke(start, density = 1f))

        val moved = guides.snapInkPoint(Offset(ruler.centerX + 40f, topEdgeY + 12f), density = 1f)
        assertEquals(ruler.centerX + 40f, moved.x, 0.01f)
        assertEquals(topEdgeY, moved.y, 0.01f)

        guides.endInkStroke()
        guides.toggleRuler()
        assertNull(guides.ruler)
    }

    @Test
    fun rulerBodyMovesAndEndsRotateWithAngleSnap() = withController { controller ->
        val guides = controller.geometryGuides
        guides.toggleRuler()
        val ruler = assertNotNull(guides.ruler)

        val bodyHit = guides.hitTest(Offset(ruler.centerX, ruler.centerY), density = 1f)
        assertEquals(GeometryGuidesManager.GuideHit.RULER_BODY, bodyHit)
        guides.drag(bodyHit!!, Offset(ruler.centerX, ruler.centerY), Offset(ruler.centerX + 25f, ruler.centerY - 15f))
        assertEquals(ruler.centerX + 25f, guides.ruler!!.centerX, 0.01f)
        assertEquals(ruler.centerY - 15f, guides.ruler!!.centerY, 0.01f)

        val moved = guides.ruler!!
        val endX = moved.centerX + moved.lengthPx / 2f - 18f
        val rotateHit = guides.hitTest(Offset(endX, moved.centerY), density = 1f)
        assertEquals(GeometryGuidesManager.GuideHit.RULER_ROTATE_END, rotateHit)
        // Target near 15 degrees -> snaps cleanly to 15°
        guides.drag(rotateHit!!, Offset(endX, moved.centerY), Offset(moved.centerX + 100f, moved.centerY + 26f), free = false)
        assertTrue(abs(guides.ruler!!.angleDeg - 15f) < 0.1f)
    }

    @Test
    fun protractorTogglesAndProjectsInkOntoArc() = withController { controller ->
        val guides = controller.geometryGuides
        guides.toggleProtractor()
        val protractor = assertNotNull(guides.protractor)

        val apex = Offset(protractor.centerX, protractor.centerY - protractor.radiusPx + 4f)
        assertTrue(guides.beginInkStroke(apex, density = 1f))
        val snapped = guides.snapInkPoint(Offset(protractor.centerX + protractor.radiusPx * 0.7f, protractor.centerY - protractor.radiusPx * 0.7f), density = 1f)
        val radius = hypot(snapped.x - protractor.centerX, snapped.y - protractor.centerY)
        assertEquals(protractor.radiusPx, radius, 0.05f)
        assertTrue(snapped.y <= protractor.centerY + 0.01f)

        guides.endInkStroke()
        guides.hideAll()
        assertFalse(guides.isProtractorVisible)
    }
}
