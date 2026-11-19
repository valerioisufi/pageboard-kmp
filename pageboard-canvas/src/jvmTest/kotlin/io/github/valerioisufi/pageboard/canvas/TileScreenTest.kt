package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import io.github.valerioisufi.pageboard.canvas.render.tile.Tile
import io.github.valerioisufi.pageboard.canvas.render.tile.TileKey
import io.github.valerioisufi.pageboard.canvas.render.tile.TileManager
import io.github.valerioisufi.pageboard.canvas.render.tile.TilePyramid
import io.github.valerioisufi.pageboard.canvas.render.tile.TileState
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tiles as they are drawn on screen, at zoom levels between two levels of detail.
 */
class TileScreenTest {

    @Test
    fun visibleTilesAreRasterizedOnceAndKept() {
        // A Retina window zoomed just past a change of LOD (at 1.05): every tile covers the fewest
        // screen pixels, the tiles on screen exceed the memory budget of the cache
        val viewport = Rect(0f, 0f, 2880f, 1800f)
        val pageRect = Rect(16f, 16f, 2864f, 16f + 2848f * 1.4142f)
        val scale = 1.06f
        val originX = 300f
        val originY = 1000f
        val camera = Matrix2D().postScale(scale, scale).postTranslate(-originX * scale, -originY * scale)

        val rasterized = AtomicInteger()
        val manager = TileManager(onInvalidate = { rasterized.incrementAndGet() })
        val doc = DrawDocument("doc", "Doc")
        val canvas = Canvas(ImageBitmap(64, 64))

        // Draw frames until nothing is rasterized anymore (bounded in time)
        var last = -1
        var stableFrames = 0
        var frames = 0
        while (stableFrames < 5 && frames < 100) {
            manager.drawTiles(canvas, camera, viewport, doc, listOf(pageRect))
            Thread.sleep(100)
            val now = rasterized.get()
            if (now == last) stableFrames++ else stableFrames = 0
            last = now
            frames++
        }

        val lod = TilePyramid.calculateLod(scale)
        val visible = TilePyramid.getPageIntersectingTileRange(
            originX - pageRect.left, originY - pageRect.top,
            originX + viewport.width / scale - pageRect.left, originY + viewport.height / scale - pageRect.top,
            pageRect.width, pageRect.height, lod
        )
        assertTrue(visible.count * TilePyramid.TILE_SIZE * TilePyramid.TILE_SIZE * 4 > 32 * 1024 * 1024)
        assertTrue(stableFrames >= 5, "tiles are still rasterized after $frames frames ($last tiles)")
        visible.forEach { col, row ->
            val tile = manager.tileCache.get(TileKey(doc.pages[0].renderKey, lod, col, row))
            assertEquals(TileState.READY, tile?.state, "tile $col,$row")
        }
        manager.cleanup()
    }

    @Test
    fun adjacentTilesLeaveNoSeam() {
        val manager = TileManager()
        val doc = DrawDocument("doc", "Doc")
        val page = doc.pages[0]
        for (col in 0..1) for (row in 0..1) {
            val bitmap = manager.tileCache.obtainBitmap()
            Canvas(bitmap).drawRect(Rect(0f, 0f, 512f, 512f), Paint().apply { color = Color.Black })
            manager.tileCache.put(Tile(TileKey(page.renderKey, 0, col, row), bitmap, TileState.READY))
        }

        // The zooms drawing the tiles of LOD 0
        for (scale in listOf(0.6f, 0.73f, 0.917f, 1.04f)) {
            val screen = ImageBitmap(800, 800)
            val canvas = Canvas(screen)
            canvas.drawRect(Rect(0f, 0f, 800f, 800f), Paint().apply { color = Color.White })
            val camera = Matrix2D().postScale(scale, scale).postTranslate(3.3f, 2.7f)
            val viewport = Rect(0f, 0f, 512f * scale * 1.5f, 512f * scale * 1.5f)
            manager.drawTiles(canvas, camera, viewport, doc, listOf(Rect(0f, 0f, 1024f, 1024f)))

            val pixels = screen.toPixelMap()
            val seamX = (512f * scale + 3.3f).toInt()
            for (x in seamX - 1..seamX + 1) {
                assertEquals(Color.Black, pixels[x, 100], "scale $scale, x $x")
            }
        }
        manager.cleanup()
    }

    @Test
    fun aCellWithoutItsTileShowsTheOverviewWithTheDescendantsOverIt() {
        val manager = TileManager()
        val doc = DrawDocument("doc", "Doc")
        val pageKey = doc.pages[0].renderKey
        fun tile(key: TileKey, color: Color) {
            val bitmap = manager.tileCache.obtainBitmap()
            Canvas(bitmap).drawRect(Rect(0f, 0f, 512f, 512f), Paint().apply { this.color = color })
            manager.tileCache.put(Tile(key, bitmap, TileState.READY))
        }
        // As after a zoom in: the overview, and a tile of level 1, far from level 0 on screen
        tile(TileKey(pageKey, TilePyramid.OVERVIEW_LOD, 0, 0), Color.Red)
        tile(TileKey(pageKey, 1, 0, 0), Color.Black)

        val screen = ImageBitmap(400, 400)
        val canvas = Canvas(screen)
        canvas.drawRect(Rect(0f, 0f, 400f, 400f), Paint().apply { color = Color.White })
        manager.drawTiles(canvas, Matrix2D(), Rect(0f, 0f, 400f, 400f), doc, listOf(Rect(0f, 0f, 1024f, 1024f)))

        val pixels = screen.toPixelMap()
        // Inside the tile of level 1 (256 wide), and in the rest of the cell of level 0
        assertEquals(Color.Black, pixels[100, 100])
        assertEquals(Color.Red, pixels[350, 350])
        manager.cleanup()
    }
}
