package io.github.valerioisufi.drawview.canvas

import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The tiles on screen as sharp as the screen allows (see `docs/tile-sharpness.md`).
 */
class TileSharpnessTest {

    @Test
    fun pagesAndCameraStayOnWholePixels() = runTest(UnconfinedTestDispatcher()) {
        val document = DrawDocument().apply { repeat(2) { addPage() } }
        val viewport = DrawViewController(document = document, coroutineScope = backgroundScope).viewport
        // An odd width centers the pages on half a pixel, the pages after the first follow a page
        // of a fractional height, a trackpad scrolls by fractions of a pixel
        viewport.setSize(1001f, 777f)
        viewport.panBy(0f, -123.37f)

        val camera = viewport.getRenderMatrix(Matrix2D())
        assertEquals(1f, camera.a)
        for (rect in viewport.layout.pagesRectOnWindow) {
            val topLeft = camera.mapPoint(rect.left, rect.top)
            assertEquals(round(topLeft.x), topLeft.x, "left of $rect")
            assertEquals(round(topLeft.y), topLeft.y, "top of $rect")
        }
    }
}
