package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.pageboard.canvas.camera.CalcPage
import io.github.valerioisufi.pageboard.canvas.geometry.mapRect
import io.github.valerioisufi.pageboard.canvas.render.tile.TileKey
import io.github.valerioisufi.pageboard.canvas.render.tile.TileManager
import io.github.valerioisufi.pageboard.canvas.render.tile.TilePyramid
import io.github.valerioisufi.pageboard.canvas.render.tile.TileRenderer
import io.github.valerioisufi.pageboard.canvas.render.tile.TileState
import io.github.valerioisufi.pageboard.ink.InkStrokeModeller
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.store.testing.SyntheticDocuments
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * The cost of the tiles (see `docs/plans/performance.md`): how long the pages on screen take to show
 * after a jump and after a fling, and how long a dense tile takes to rasterize.
 */
class TileBenchmark {
    private val viewport = Rect(0f, 0f, 1600f, 1000f)

    /** The pages on screen after a jump of 20 pages, then after a fling over the same 20 pages. */
    @Test
    fun fillingTheScreen() {
        val document = SyntheticDocuments.notebook(pages = 30, strokesPerPage = 400, modeller = InkStrokeModeller)
        val rects = CalcPage(density = 2f).apply { calcPagesRectOnWindow(document.pages, viewport) }.pagesRectOnWindow.toList()

        val jump = TileManager()
        showUntilFilled(jump, document, rects, at(rects, 0))
        val jumped = showUntilFilled(jump, document, rects, at(rects, 20))
        report("M6 the screen filled after a jump of 20 pages", "$jumped")
        jump.cleanup()

        val fling = TileManager()
        showUntilFilled(fling, document, rects, at(rects, 0))
        // A page a frame, as a fast fling goes
        val canvas = Canvas(ImageBitmap(64, 64))
        for (page in 1 until 20) {
            fling.drawTiles(canvas, at(rects, page), viewport, document, rects)
            Thread.sleep(FRAME_MS)
        }
        val flung = showUntilFilled(fling, document, rects, at(rects, 20))
        report("M6 the screen filled after a fling over 20 pages", "$flung")
        fling.cleanup()
    }

    /** The screen after a zoom out across 4 levels in 300 ms, from a viewport zoomed by 16 (`docs/plans/tile-zoom-out.md`). */
    @Test
    fun fillingTheScreenAfterAZoomOut() {
        val document = SyntheticDocuments.notebook(pages = 8, strokesPerPage = 1_500, modeller = InkStrokeModeller)
        val rects = CalcPage(density = 2f).apply { calcPagesRectOnWindow(document.pages, viewport) }.pagesRectOnWindow.toList()
        fun zoomed(scale: Float) = Matrix2D()
            .postScale(scale, scale)
            .postTranslate(viewport.center.x * (1f - scale), viewport.center.y * (1f - scale))

        val manager = TileManager()
        val canvas = Canvas(ImageBitmap(64, 64))
        showUntilFilled(manager, document, rects, zoomed(1f))
        showUntilFilled(manager, document, rects, zoomed(16f))
        val frames = 19
        for (frame in 1..frames) {
            manager.drawTiles(canvas, zoomed(16f / 2f.pow(4f * frame / frames)), viewport, document, rects)
            Thread.sleep(FRAME_MS)
        }
        report("M10 the screen filled after a zoom out across 4 levels", "${showUntilFilled(manager, document, rects, zoomed(1f))}")
        manager.cleanup()
    }

    /** A tile of a page of 1,500 strokes, at the levels of detail 0 and 2. */
    @Test
    fun rasterizingADenseTile() {
        val document = SyntheticDocuments.notebook(pages = 1, strokesPerPage = 1_500, modeller = InkStrokeModeller)
        val page = document.pages[0]
        val rect = CalcPage(density = 2f).apply { calcPagesRectOnWindow(document.pages, viewport) }.pagesRectOnWindow[0]
        val renderer = TileRenderer()
        val bitmap = ImageBitmap(TilePyramid.TILE_SIZE, TilePyramid.TILE_SIZE)
        // Tiles in the middle of the writing (a tile of level 2 is 17 mm wide)
        for ((lod, cell) in listOf(0 to (1 to 2), 2 to (4 to 8))) {
            val key = TileKey(page.renderKey, lod, cell.first, cell.second)
            repeat(5) { renderer.renderTile(key, bitmap, document, page, rect) }
            val times = List(20) { measureTime { renderer.renderTile(key, bitmap, document, page, rect) } }.sorted()
            report("M7 a tile of 1,500 strokes at level $lod", "${times[times.size / 2]}")
        }
    }

    /** The camera showing the top of the page [index] at the scale of the layout. */
    private fun at(rects: List<Rect>, index: Int): Matrix2D = Matrix2D().postTranslate(0f, -(rects[index].top - 8f))

    /** Draws frames with [camera] until every tile on screen is rasterized; how long it took. */
    private fun showUntilFilled(manager: TileManager, document: Document, rects: List<Rect>, camera: Matrix2D): Duration {
        val canvas = Canvas(ImageBitmap(64, 64))
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < TIMEOUT) {
            manager.drawTiles(canvas, camera, viewport, document, rects)
            if (visibleTiles(document, rects, camera).all { manager.tileCache.get(it)?.state == TileState.READY }) return start.elapsedNow()
            Thread.sleep(FRAME_MS)
        }
        error("The screen is not filled after $TIMEOUT")
    }

    private fun visibleTiles(document: Document, rects: List<Rect>, camera: Matrix2D): List<TileKey> {
        val screenToWorld = Matrix2D()
        check(camera.invert(screenToWorld))
        val world = screenToWorld.mapRect(viewport)
        val lod = TilePyramid.calculateLod(camera.a)
        return rects.indices.filter { rects[it].overlaps(world) }.flatMap { index ->
            val page = rects[index]
            val keys = mutableListOf<TileKey>()
            TilePyramid.getPageIntersectingTileRange(
                max(0f, world.left - page.left), max(0f, world.top - page.top),
                min(page.width, world.right - page.left), min(page.height, world.bottom - page.top),
                page.width, page.height, lod
            ).forEach { col, row -> keys += TileKey(document.pages[index].renderKey, lod, col, row) }
            keys
        }
    }

    private fun report(measure: String, value: String) = println("BENCHMARK $measure: $value")

    private companion object {
        const val FRAME_MS = 16L
        val TIMEOUT: Duration = 60.seconds
    }
}
