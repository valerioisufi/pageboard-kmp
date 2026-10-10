package io.github.valerioisufi.drawview.core.render.tile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import io.github.valerioisufi.drawview.core.canvas.StrokeMeshPainter
import io.github.valerioisufi.drawview.core.canvas.TextMarkupPainter
import io.github.valerioisufi.drawview.core.geometry.mapRect
import io.github.valerioisufi.drawview.core.geometry.setRectToRect
import io.github.valerioisufi.drawview.core.geometry.toCompose
import io.github.valerioisufi.drawview.core.geometry.toComposeMatrix
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup
import io.github.valerioisufi.drawview.core.model.readSnapshot
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manages the multi-scale, background-rasterized tile grid for low-latency rendering.
 *
 * Handles tile caching, visibility culling, hierarchical mipmap fallback rendering,
 * asynchronous background rasterization, and synchronous main-thread stroke baking.
 *
 * Every page has a content *generation*, incremented by each invalidation: a tile rasterized
 * from a snapshot older than the current generation is published as [TileState.STALE] (so it
 * is rendered again), and the strokes (and marks of the text of the PDFs) committed while a tile
 * was being rasterized are baked into the result before it is published, so that freshly drawn
 * strokes never disappear.
 *
 * When a page is laid out at another scale (the window was resized), its tiles are dropped, but the
 * ones on screen keep being drawn, stretched, until the tiles of the new layout are ready.
 *
 * @param onInvalidate Callback triggered when a new tile is rasterized to request a canvas redraw.
 */
class TileManager(
    val tileRenderer: TileRenderer = TileRenderer(),
    val tileCache: TileCache = TileCache(),
    onInvalidate: () -> Unit = {}
) {
    /** Callback invoked (possibly from a background thread) when the canvas must be redrawn. */
    var onInvalidate: () -> Unit = onInvalidate

    /** General scope for tile rendering jobs. */
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Dedicated 2-thread worker for background tile rasterization. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val tileWorkerDispatcher = Dispatchers.Default.limitedParallelism(2)

    private val lock = SyncLock()
    private val activeTileJobs = mutableMapOf<TileKey, Job>()
    private val pageGenerations = mutableMapOf<Long, Int>()
    /** World Space size of each page used by its cached tiles (it changes with the window width). */
    private val pageWorldSizes = mutableMapOf<Long, Size>()
    private var bakeSequence = 0L
    private val recentBakes = mutableMapOf<Long, MutableList<BakedElement>>()

    /** An element committed to a page and baked into its tiles: a stroke or a mark of the text of a PDF. */
    private class BakedElement(val sequence: Long, val stroke: DrawStroke? = null, val markup: DrawTextMarkup? = null)

    private val filterPaint = Paint().apply {
        filterQuality = FilterQuality.Medium
        // Antialiased edges of adjacent tiles would let the background show through the seam
        isAntiAlias = false
    }
    private val bakePainter = StrokeMeshPainter()
    private val markupPaint = Paint()
    private val scratchTileToScreen = Matrix2D()
    private val scratchPageToScreen = Matrix2D()
    private val scratchMmToTile = Matrix2D()

    /** Tiles drawn on screen in the last frame, by page (UI thread only). */
    private val pageTiles = mutableMapOf<Long, PageTiles>()

    /**
     * A tile drawn on screen.
     *
     * @property pageSize World Space size of the page the tile was rasterized for.
     * @property coverage Part of the page the tile covered on screen, in fractions of the page size.
     */
    private class DrawnTile(val tile: Tile, val pageSize: Size, val coverage: Rect) {
        /** Whether the tile, of a previous layout, was drawn in the last frame. */
        var drawn = false
    }

    /** The tiles of a page on screen. */
    private class PageTiles {
        /** Tiles of the previous layouts of the page, oldest first. */
        var previousLayouts: List<DrawnTile> = emptyList()

        /** Tiles of the current layout drawn in the last frame. */
        val drawn = mutableListOf<DrawnTile>()

        /** The page was laid out at another scale: what is on screen becomes a previous layout. */
        fun keepAsPreviousLayout() {
            previousLayouts = previousLayouts.filter { it.drawn } + drawn
            // Kept by another change of layout before the next frame
            previousLayouts.forEach { it.drawn = true }
            drawn.clear()
        }

        fun beginFrame() {
            previousLayouts.forEach { it.drawn = false }
            drawn.clear()
        }

        fun endFrame() {
            // The tiles of the current layout cover the screen
            if (previousLayouts.none { it.drawn }) previousLayouts = emptyList()
        }
    }

    /**
     * Draws visible and pre-fetched tiles on [canvas].
     *
     * @param canvas The destination canvas.
     * @param cameraMatrix The current camera transformation matrix.
     * @param viewportBounds Visible screen bounds in pixels.
     * @param document The document data model.
     * @param pageRects The World Space bounds of each page (indexed like [DrawDocument.pages]).
     * @param skipPageKey Page not drawn (e.g. the page being dragged while reordering).
     */
    fun drawTiles(
        canvas: Canvas,
        cameraMatrix: Matrix2D,
        viewportBounds: Rect,
        document: DrawDocument,
        pageRects: List<Rect>,
        skipPageKey: Long? = null
    ) {
        if (pageRects.isEmpty()) return
        tileCache.beginFrame()

        // 1. Calculate World Space viewport
        val screenToWorld = Matrix2D()
        if (!cameraMatrix.invert(screenToWorld)) return
        val worldViewport = screenToWorld.mapRect(viewportBounds)

        // 2. Determine current scale and LOD
        val currentScale = cameraMatrix.a
        val targetLod = TilePyramid.calculateLod(currentScale)
        val tileSize = TilePyramid.getTileWorldSize(targetLod)

        val pages = document.pages
        val pageCount = min(pages.size, pageRects.size)

        // Overscan viewport for prefetching
        val prefetchLeft = worldViewport.left - tileSize
        val prefetchTop = worldViewport.top - tileSize
        val prefetchRight = worldViewport.right + tileSize
        val prefetchBottom = worldViewport.bottom + tileSize

        for (i in 0 until pageCount) {
            val page = pages[i]
            if (page.renderKey == skipPageKey) {
                pageTiles.remove(page.renderKey)
                continue
            }
            val pageWorldRect = pageRects[i]
            ensurePageGeometry(page.renderKey, pageWorldRect)

            // Culling: check if page intersects prefetch viewport
            val nearPage = pageWorldRect.left < prefetchRight &&
                    prefetchLeft < pageWorldRect.right &&
                    pageWorldRect.top < prefetchBottom &&
                    prefetchTop < pageWorldRect.bottom
            if (!nearPage) {
                pageTiles.remove(page.renderKey)
                continue
            }
            val onScreen = pageTiles.getOrPut(page.renderKey) { PageTiles() }
            onScreen.beginFrame()

            val isPageVisible = pageWorldRect.left < worldViewport.right &&
                    worldViewport.left < pageWorldRect.right &&
                    pageWorldRect.top < worldViewport.bottom &&
                    worldViewport.top < pageWorldRect.bottom

            val pageWidth = pageWorldRect.width
            val pageHeight = pageWorldRect.height

            if (isPageVisible) {
                val localLeft = max(0f, worldViewport.left - pageWorldRect.left)
                val localTop = max(0f, worldViewport.top - pageWorldRect.top)
                val localRight = min(pageWidth, worldViewport.right - pageWorldRect.left)
                val localBottom = min(pageHeight, worldViewport.bottom - pageWorldRect.top)

                val visibleRange = TilePyramid.getPageIntersectingTileRange(
                    localLeft = localLeft,
                    localTop = localTop,
                    localRight = localRight,
                    localBottom = localBottom,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    lod = targetLod,
                    overscanMargin = 0
                )

                visibleRange.forEach { col, row ->
                    val key = TileKey(page.renderKey, targetLod, col, row)
                    val cell = pageFraction(TilePyramid.getTilePageRect(key), pageWorldRect)
                    val tile = tileCache.get(key)

                    if (tile != null && (tile.state == TileState.READY || tile.state == TileState.STALE)) {
                        drawTileBitmap(canvas, tile.bitmap, key, pageWorldRect, cameraMatrix)
                        onScreen.drawn += DrawnTile(tile, pageWorldRect.size, cell)
                        if (tile.state == TileState.STALE) {
                            enqueueTile(key, document, page, pageWorldRect)
                        }
                    } else {
                        // Hierarchical Mipmap Fallback
                        val tileLocalLeft = col * tileSize
                        val tileLocalTop = row * tileSize
                        val tileWorldLeft = pageWorldRect.left + tileLocalLeft
                        val tileWorldTop = pageWorldRect.top + tileLocalTop
                        val tileWorldRight = min(pageWorldRect.right, tileWorldLeft + tileSize)
                        val tileWorldBottom = min(pageWorldRect.bottom, tileWorldTop + tileSize)
                        val tileScreenBounds = cameraMatrix.mapRect(
                            Rect(tileWorldLeft, tileWorldTop, tileWorldRight, tileWorldBottom)
                        )

                        canvas.save()
                        canvas.clipRect(tileScreenBounds)
                        val ancestor = tileCache.findNearestAncestor(key)
                        if (ancestor != null) {
                            drawTileBitmap(canvas, ancestor.bitmap, ancestor.key, pageWorldRect, cameraMatrix)
                            onScreen.drawn += DrawnTile(ancestor, pageWorldRect.size, cell)
                        } else {
                            // What was on screen before the window was resized, under the sharper tiles
                            drawPreviousLayouts(canvas, onScreen, cell, pageWorldRect, cameraMatrix)
                            for (descendant in tileCache.findReadyDescendants(key)) {
                                drawTileBitmap(canvas, descendant.bitmap, descendant.key, pageWorldRect, cameraMatrix)
                                val coverage = pageFraction(TilePyramid.getTilePageRect(descendant.key), pageWorldRect)
                                onScreen.drawn += DrawnTile(descendant, pageWorldRect.size, coverage)
                            }
                        }
                        canvas.restore()

                        enqueueTile(key, document, page, pageWorldRect)
                    }
                }
            }

            // Prefetch range
            val prefetchLocalLeft = (worldViewport.left - pageWorldRect.left).coerceIn(0f, pageWidth)
            val prefetchLocalTop = (worldViewport.top - pageWorldRect.top).coerceIn(0f, pageHeight)
            val prefetchLocalRight = (worldViewport.right - pageWorldRect.left).coerceIn(0f, pageWidth)
            val prefetchLocalBottom = (worldViewport.bottom - pageWorldRect.top).coerceIn(0f, pageHeight)

            val prefetchRange = TilePyramid.getPageIntersectingTileRange(
                localLeft = prefetchLocalLeft,
                localTop = prefetchLocalTop,
                localRight = prefetchLocalRight,
                localBottom = prefetchLocalBottom,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                lod = targetLod,
                overscanMargin = 1
            )

            prefetchRange.forEach { col, row ->
                val key = TileKey(page.renderKey, targetLod, col, row)
                val cached = tileCache.get(key)
                if (cached == null || cached.state == TileState.STALE) {
                    enqueueTile(key, document, page, pageWorldRect)
                }
            }
            onScreen.endFrame()
        }
    }

    /**
     * Draws the tiles of the previous layouts of the page (before the window was resized) covering
     * [cell] (fractions of the page size), stretched to the current page size.
     */
    private fun drawPreviousLayouts(
        canvas: Canvas,
        onScreen: PageTiles,
        cell: Rect,
        pageWorldRect: Rect,
        cameraMatrix: Matrix2D
    ) {
        for (previous in onScreen.previousLayouts) {
            val left = max(cell.left, previous.coverage.left)
            val top = max(cell.top, previous.coverage.top)
            val right = min(cell.right, previous.coverage.right)
            val bottom = min(cell.bottom, previous.coverage.bottom)
            if (left >= right || top >= bottom) continue
            val clip = cameraMatrix.mapRect(
                Rect(
                    pageWorldRect.left + left * pageWorldRect.width,
                    pageWorldRect.top + top * pageWorldRect.height,
                    pageWorldRect.left + right * pageWorldRect.width,
                    pageWorldRect.top + bottom * pageWorldRect.height
                )
            )
            canvas.save()
            canvas.clipRect(clip)
            drawTileBitmap(canvas, previous.tile.bitmap, previous.tile.key, pageWorldRect, cameraMatrix, previous.pageSize)
            canvas.restore()
            previous.drawn = true
        }
    }

    /** [pageLocalRect] (page-local World Space) in fractions of the size of the page, within the page. */
    private fun pageFraction(pageLocalRect: Rect, pageWorldRect: Rect): Rect {
        val width = pageWorldRect.width
        val height = pageWorldRect.height
        if (width <= 0f || height <= 0f) return Rect.Zero
        return Rect(
            (pageLocalRect.left / width).coerceIn(0f, 1f),
            (pageLocalRect.top / height).coerceIn(0f, 1f),
            (pageLocalRect.right / width).coerceIn(0f, 1f),
            (pageLocalRect.bottom / height).coerceIn(0f, 1f)
        )
    }

    /**
     * Draws all the tiles of a single page at an arbitrary destination rectangle (used to render
     * the page being dragged while reordering pages).
     */
    fun drawPageTiles(canvas: Canvas, page: DrawPage, pageWorldRect: Rect, destScreenRect: Rect, cameraScale: Float) {
        val lod = TilePyramid.calculateLod(cameraScale)
        val range = TilePyramid.getPageIntersectingTileRange(
            0f, 0f, pageWorldRect.width, pageWorldRect.height,
            pageWorldRect.width, pageWorldRect.height, lod
        )
        val worldToDest = Matrix2D().setRectToRect(pageWorldRect, destScreenRect)
        canvas.save()
        canvas.clipRect(destScreenRect)
        range.forEach { col, row ->
            val key = TileKey(page.renderKey, lod, col, row)
            val tile = tileCache.get(key) ?: tileCache.findNearestAncestor(key) ?: return@forEach
            drawTileBitmap(canvas, tile.bitmap, tile.key, pageWorldRect, worldToDest)
        }
        canvas.restore()
    }

    /**
     * Draws the tile [key] rasterized for a page of size [renderedPageSize], stretched when the page
     * is now laid out at another size.
     */
    private fun drawTileBitmap(
        canvas: Canvas,
        bitmap: ImageBitmap,
        key: TileKey,
        pageWorldRect: Rect,
        cameraMatrix: Matrix2D,
        renderedPageSize: Size = pageWorldRect.size
    ) {
        val tileToScreen = if (renderedPageSize == pageWorldRect.size) {
            TilePyramid.getTileToScreenMatrix(key, pageWorldRect, cameraMatrix, scratchTileToScreen)
        } else {
            scratchPageToScreen.reset()
                .postScale(pageWorldRect.width / renderedPageSize.width, pageWorldRect.height / renderedPageSize.height)
                .postTranslate(pageWorldRect.left, pageWorldRect.top)
                .postConcat(cameraMatrix)
            TilePyramid.getTileToScreenMatrix(key, Rect.Zero, scratchPageToScreen, scratchTileToScreen)
        }
        canvas.save()
        canvas.concat(tileToScreen.toComposeMatrix())
        canvas.drawImage(bitmap, Offset.Zero, filterPaint)
        canvas.restore()
    }

    /**
     * Updates the World Space size of the pages after a change of layout (window resized, widest
     * page changed): the tiles of the pages laid out at another scale are dropped, the ones on
     * screen are drawn stretched until the new ones are ready.
     */
    fun updateLayout(pages: List<DrawPage>, pageRects: List<Rect>) {
        for (i in 0 until min(pages.size, pageRects.size)) {
            ensurePageGeometry(pages[i].renderKey, pageRects[i])
        }
    }

    /**
     * Drops the tiles of the page when its World Space size changed (e.g. the window was resized
     * after the first frames): they were rasterized at another scale.
     */
    private fun ensurePageGeometry(pageKey: Long, pageWorldRect: Rect) {
        val size = pageWorldRect.size
        val changed = syncLock(lock) {
            val previous = pageWorldSizes.put(pageKey, size)
            if (previous != null && previous != size) {
                pageGenerations[pageKey] = (pageGenerations[pageKey] ?: 0) + 1
                recentBakes.remove(pageKey)
                // The queued tiles would be rasterized for the old layout, delaying the new ones
                val jobs = activeTileJobs.entries.iterator()
                while (jobs.hasNext()) {
                    val (key, job) = jobs.next()
                    if (key.pageKey == pageKey) {
                        job.cancel()
                        jobs.remove()
                    }
                }
                true
            } else {
                false
            }
        }
        if (changed) {
            tileCache.removeForPage(pageKey)
            pageTiles[pageKey]?.keepAsPreviousLayout()
        }
    }

    private fun generationOf(pageKey: Long): Int = syncLock(lock) { pageGenerations[pageKey] ?: 0 }

    private fun bumpGeneration(pageKey: Long) {
        syncLock(lock) {
            pageGenerations[pageKey] = (pageGenerations[pageKey] ?: 0) + 1
        }
    }

    /**
     * Whether tiles are being rasterized: the pages on screen may still be drawn without them (a
     * screenshot test waits for them).
     */
    val isRasterizing: Boolean get() = syncLock(lock) { activeTileJobs.isNotEmpty() }

    /**
     * Enqueues a tile for background rasterization on the dedicated worker dispatcher.
     */
    fun enqueueTile(
        key: TileKey,
        document: DrawDocument,
        page: DrawPage,
        pageWorldRect: Rect
    ) {
        val job = syncLock(lock) {
            if (activeTileJobs.containsKey(key)) return
            // Started once registered: a job ending at once still finds itself registered
            scope.launch(tileWorkerDispatcher, start = CoroutineStart.LAZY) {
                try {
                    rasterizeTile(key, document, page, pageWorldRect)
                } finally {
                    // A cancelled job still running must not unregister the one replacing it
                    val self = coroutineContext[Job]
                    syncLock(lock) {
                        if (activeTileJobs[key] === self) activeTileJobs.remove(key)
                    }
                }
                onInvalidate()
            }.also { activeTileJobs[key] = it }
        }
        job.start()
    }

    /**
     * Rasterizes the tile [key] and publishes it in the cache.
     */
    private fun rasterizeTile(key: TileKey, document: DrawDocument, page: DrawPage, pageWorldRect: Rect) {
        // Read generation, bake sequence and content atomically with respect to
        // bakeStrokesDirectlySync (which mutates the page under the same lock).
        var generation = 0
        var sequence = 0L
        val content = syncLock(lock) {
            generation = pageGenerations[key.pageKey] ?: 0
            sequence = bakeSequence
            page.snapshotContent()
        }
        val bitmap = tileCache.obtainBitmap()

        tileRenderer.renderTile(
            tileKey = key,
            targetBitmap = bitmap,
            document = document,
            content = content,
            pageWorldRect = pageWorldRect
        )

        // Publish on the same lock used by the synchronous bake: no stroke committed while
        // the tile was rasterized can be lost.
        syncLock(lock) {
            val candidates = recentBakes[key.pageKey]?.filter { it.sequence > sequence }.orEmpty()
            // An element added to the page between the sequence read and the snapshot is
            // already part of the rendered content.
            val renderedStrokes = if (candidates.isEmpty()) emptySet() else content.strokes.toHashSet()
            val renderedMarkups = if (candidates.isEmpty()) emptySet() else content.markups.toHashSet()
            val pending = candidates.filter { it.stroke !in renderedStrokes && it.markup !in renderedMarkups }
            if (pending.isNotEmpty()) {
                val mmToTile = TilePyramid.getMmToTileMatrix(key, content.rect().toCompose(), pageWorldRect)
                val tileCanvas = Canvas(bitmap)
                val painter = StrokeMeshPainter()
                for (element in pending) {
                    val stroke = element.stroke
                    if (stroke != null && !stroke.isHidden) painter.draw(tileCanvas, stroke.mesh, mmToTile)
                    element.markup?.let { drawMarkup(tileCanvas, it, content.widthMm, content.heightMm, mmToTile) }
                }
            }
            val upToDate = (pageGenerations[key.pageKey] ?: 0) == generation
            // A tile rasterized for an outdated page geometry is discarded
            val sameGeometry = pageWorldSizes[key.pageKey]?.let { it == pageWorldRect.size } ?: true
            if (sameGeometry) {
                tileCache.put(Tile(key, bitmap, if (upToDate) TileState.READY else TileState.STALE))
            }
        }
    }

    /**
     * Bakes newly completed ink strokes immediately into the cached tiles of every LOD on the UI
     * thread (< 0.2 ms), eliminating any latency gap or flash before background rasterization.
     *
     * @param strokesByPage Strokes keyed by page index.
     * @param pageRects The World Space bounds of each page (indexed like [DrawDocument.pages]).
     * @param commit Mutation adding the strokes to their pages, executed atomically with the bake
     * so that background workers never miss or double-draw them.
     */
    fun bakeStrokesDirectlySync(
        strokesByPage: Map<Int, List<DrawStroke>>,
        document: DrawDocument,
        pageRects: List<Rect>,
        cameraScale: Float,
        commit: () -> Unit = {}
    ) {
        if (strokesByPage.isEmpty()) {
            commit()
            return
        }

        syncLock(lock) {
            commit()
            for ((pageIndex, pageStrokes) in strokesByPage) {
                val page = document.pages.getOrNull(pageIndex) ?: continue
                val pageWorldRect = pageRects.getOrNull(pageIndex) ?: continue
                val pageRectMm = page.rect().toCompose()

                val bakes = recentBakes.getOrPut(page.renderKey) { mutableListOf() }
                for (stroke in pageStrokes) {
                    bakeSequence++
                    bakes.add(BakedElement(bakeSequence, stroke = stroke))
                }
                // Keep only the strokes that in-flight jobs may still be missing
                if (bakes.size > 256) bakes.subList(0, bakes.size - 256).clear()

                val scaleX = if (page.widthMm > 0f) pageWorldRect.width / page.widthMm else 1f
                val scaleY = if (page.heightMm > 0f) pageWorldRect.height / page.heightMm else 1f

                for (lod in TilePyramid.MIN_LOD..TilePyramid.MAX_LOD) {
                    for (stroke in pageStrokes) {
                        val bounds = stroke.bounds
                        val tileRange = TilePyramid.getPageIntersectingTileRange(
                            localLeft = bounds.left * scaleX,
                            localTop = bounds.top * scaleY,
                            localRight = bounds.right * scaleX,
                            localBottom = bounds.bottom * scaleY,
                            pageWidth = pageWorldRect.width,
                            pageHeight = pageWorldRect.height,
                            lod = lod,
                            overscanMargin = 0
                        )
                        tileRange.forEach { col, row ->
                            val key = TileKey(page.renderKey, lod, col, row)
                            val tile = tileCache.get(key) ?: return@forEach
                            if (tile.state != TileState.READY && tile.state != TileState.STALE) return@forEach
                            TilePyramid.getMmToTileMatrix(key, pageRectMm, pageWorldRect, scratchMmToTile)
                            val tileCanvas = Canvas(tile.bitmap)
                            tileCanvas.save()
                            tileCanvas.clipRect(scratchMmToTile.mapRect(pageRectMm))
                            bakePainter.draw(tileCanvas, stroke.mesh, scratchMmToTile)
                            tileCanvas.restore()
                        }
                    }
                }
            }
        }
        onInvalidate()
    }

    /**
     * Bakes the new marks [markups] of the text of the PDF of [page] into its cached tiles of
     * every LOD on the UI thread, like [bakeStrokesDirectlySync], then invalidates them: the baked
     * marks are drawn over the ink, the fresh tiles draw them below it.
     *
     * @param pageWorldRect World Space bounds of [page].
     * @param commit Mutation adding the marks to the page, executed atomically with the bake.
     */
    fun bakeMarkupsDirectlySync(page: DrawPage, pageWorldRect: Rect, markups: List<DrawTextMarkup>, commit: () -> Unit) {
        syncLock(lock) {
            commit()
            val bakes = recentBakes.getOrPut(page.renderKey) { mutableListOf() }
            for (markup in markups) {
                bakeSequence++
                bakes.add(BakedElement(bakeSequence, markup = markup))
            }
            if (bakes.size > 256) bakes.subList(0, bakes.size - 256).clear()

            val pageRectMm = page.rect().toCompose()
            val scaleX = if (page.widthMm > 0f) pageWorldRect.width / page.widthMm else 1f
            val scaleY = if (page.heightMm > 0f) pageWorldRect.height / page.heightMm else 1f
            for (lod in TilePyramid.MIN_LOD..TilePyramid.MAX_LOD) {
                for (markup in markups) {
                    val bounds = markup.boundsMm(page)
                    TilePyramid.getPageIntersectingTileRange(
                        localLeft = bounds.left * scaleX,
                        localTop = bounds.top * scaleY,
                        localRight = bounds.right * scaleX,
                        localBottom = bounds.bottom * scaleY,
                        pageWidth = pageWorldRect.width,
                        pageHeight = pageWorldRect.height,
                        lod = lod,
                        overscanMargin = 0
                    ).forEach { col, row ->
                        val key = TileKey(page.renderKey, lod, col, row)
                        val tile = tileCache.get(key) ?: return@forEach
                        if (tile.state != TileState.READY && tile.state != TileState.STALE) return@forEach
                        TilePyramid.getMmToTileMatrix(key, pageRectMm, pageWorldRect, scratchMmToTile)
                        val tileCanvas = Canvas(tile.bitmap)
                        tileCanvas.save()
                        tileCanvas.clipRect(scratchMmToTile.mapRect(pageRectMm))
                        drawMarkup(tileCanvas, markup, page.widthMm, page.heightMm, scratchMmToTile)
                        tileCanvas.restore()
                    }
                }
            }
        }
        val dirty = markups.map { it.boundsMm(page).toCompose() }.reduceOrNull { a, b ->
            Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
        }
        if (dirty != null) invalidatePageRegion(page, dirty, pageWorldRect)
        onInvalidate()
    }

    private fun drawMarkup(canvas: Canvas, markup: DrawTextMarkup, widthMm: Float, heightMm: Float, mmToTile: Matrix2D) {
        TextMarkupPainter.draw(
            canvas = canvas,
            rects = markup.rects,
            kind = markup.kind,
            color = Color(markup.color),
            pointsToCanvas = markup.pointsToPageMm(widthMm, heightMm).postConcat(mmToTile),
            paint = markupPaint
        )
    }

    /**
     * Invalidates the tiles of [page] intersecting [dirtyMmRect] (page millimeters): they keep
     * being drawn (no flash) while a fresh version is rasterized in background.
     */
    fun invalidatePageRegion(page: DrawPage, dirtyMmRect: Rect, pageWorldRect: Rect?) {
        bumpGeneration(page.renderKey)
        val scaleX = if (pageWorldRect != null && page.widthMm > 0f) pageWorldRect.width / page.widthMm else null
        val scaleY = if (pageWorldRect != null && page.heightMm > 0f) pageWorldRect.height / page.heightMm else null
        if (scaleX == null || scaleY == null) {
            tileCache.invalidatePage(page.renderKey)
        } else {
            val margin = 8f
            val localBox = Rect(
                dirtyMmRect.left * scaleX - margin,
                dirtyMmRect.top * scaleY - margin,
                dirtyMmRect.right * scaleX + margin,
                dirtyMmRect.bottom * scaleY + margin
            )
            tileCache.invalidatePage(page.renderKey) { key ->
                TilePyramid.getTilePageRect(key).overlaps(localBox)
            }
        }
        onInvalidate()
    }

    /**
     * Invalidates every tile of the page identified by [pageKey].
     */
    fun invalidatePage(pageKey: Long) {
        bumpGeneration(pageKey)
        tileCache.invalidatePage(pageKey)
        onInvalidate()
    }

    /**
     * Evicts the tiles of the page identified by [pageKey] (e.g. after its deletion).
     */
    fun removeForPage(pageKey: Long) {
        bumpGeneration(pageKey)
        tileCache.removeForPage(pageKey)
        syncLock(lock) {
            recentBakes.remove(pageKey)
            pageWorldSizes.remove(pageKey)
        }
        pageTiles.remove(pageKey)
        onInvalidate()
    }

    /**
     * Drops the tiles of pages that are no longer part of [document].
     */
    fun retainDocumentPages(document: DrawDocument) {
        val live = readSnapshot { document.pages.map { it.renderKey }.toSet() }
        tileCache.retainPages(live)
        syncLock(lock) {
            recentBakes.keys.retainAll(live)
            pageWorldSizes.keys.retainAll(live)
        }
        pageTiles.keys.retainAll(live)
    }

    fun invalidateAll() {
        syncLock(lock) {
            activeTileJobs.values.forEach { it.cancel() }
            activeTileJobs.clear()
            for (renderKey in pageGenerations.keys.toList()) {
                pageGenerations[renderKey] = (pageGenerations[renderKey] ?: 0) + 1
            }
            recentBakes.clear()
        }
        tileCache.clear()
        onInvalidate()
    }

    /**
     * Rasterizes the whole page into a thumbnail bitmap [widthPx] pixels wide.
     */
    fun renderThumbnail(document: DrawDocument, page: DrawPage, widthPx: Int): ImageBitmap =
        tileRenderer.renderThumbnail(document, page, widthPx)

    fun cleanup() {
        scope.cancel()
        syncLock(lock) {
            activeTileJobs.values.forEach { it.cancel() }
            activeTileJobs.clear()
            recentBakes.clear()
        }
        pageTiles.clear()
        tileCache.clear()
        tileRenderer.pdfAdapter?.close()
    }
}
