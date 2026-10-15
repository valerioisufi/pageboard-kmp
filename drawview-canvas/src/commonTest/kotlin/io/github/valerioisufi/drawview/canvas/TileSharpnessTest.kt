package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.render.tile.Tile
import io.github.valerioisufi.drawview.canvas.render.tile.TileKey
import io.github.valerioisufi.drawview.canvas.render.tile.TileManager
import io.github.valerioisufi.drawview.canvas.render.tile.TilePyramid
import io.github.valerioisufi.drawview.canvas.render.tile.TileState
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

    @Test
    fun tilesAreMagnifiedOnDenseScreensOnly() {
        fun lod(scale: Float, density: Float) = TilePyramid.calculateLod(scale, TilePyramid.maxMagnification(density))

        // 2x and more: the nearest LOD, magnified up to √2
        assertEquals(0, lod(1f, 2f))
        assertEquals(0, lod(1.3f, 2f))
        assertEquals(1, lod(1.5f, 3f))
        assertEquals(-1, lod(0.6f, 2f))
        // 1x to 1.75x: the LOD above as soon as the tiles would be magnified
        assertEquals(0, lod(1f, 1f))
        assertEquals(0, lod(1.04f, 1.25f))
        assertEquals(1, lod(1.1f, 1f))
        assertEquals(1, lod(1.3f, 1.5f))
        assertEquals(1, lod(2f, 1.75f))
        assertEquals(0, lod(0.6f, 1f))
        // The nearest LOD by default
        assertEquals(0, TilePyramid.calculateLod(1.3f))
        assertEquals(1, TilePyramid.calculateLod(1.5f))
    }

    @Test
    fun aScreenOfLowDensityShowsTheTilesOfTheLodAbove() {
        val manager = TileManager()
        val document = DrawDocument("doc", "Doc")
        val page = document.pages[0]
        val pageRect = Rect(0f, 0f, 400f, 400f)
        // Every tile of the page at LOD 0 red, at LOD 1 blue: nothing is rasterized meanwhile
        for (lod in 0..1) {
            val color = if (lod == 0) Color.Red else Color.Blue
            TilePyramid.getPageIntersectingTileRange(0f, 0f, 400f, 400f, 400f, 400f, lod).forEach { col, row ->
                val bitmap = manager.tileCache.obtainBitmap()
                Canvas(bitmap).drawRect(Rect(0f, 0f, 512f, 512f), Paint().apply { this.color = color })
                manager.tileCache.put(Tile(TileKey(page.renderKey, lod, col, row), bitmap, TileState.READY))
            }
        }

        // Zoomed to 130%
        val camera = Matrix2D().postScale(1.3f, 1.3f)
        fun centerOn(density: Float): Color {
            val screen = ImageBitmap(200, 200)
            manager.drawTiles(Canvas(screen), camera, Rect(0f, 0f, 200f, 200f), document, listOf(pageRect), density = density)
            return screen.toPixelMap()[100, 100]
        }
        assertEquals(Color.Blue, centerOn(density = 1f))
        assertEquals(Color.Red, centerOn(density = 2f))
        manager.cleanup()
    }
}
