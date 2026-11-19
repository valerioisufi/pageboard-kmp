package io.github.valerioisufi.pageboard.canvas.render.tile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import io.github.valerioisufi.pageboard.canvas.StrokeMeshPainter
import io.github.valerioisufi.pageboard.canvas.TextMarkupPainter
import io.github.valerioisufi.pageboard.canvas.geometry.mapRect
import io.github.valerioisufi.pageboard.canvas.geometry.setRectToRect
import io.github.valerioisufi.pageboard.canvas.geometry.toCompose
import io.github.valerioisufi.pageboard.canvas.geometry.toComposeMatrix
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.TextMarkupElement
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.platform.SyncLock
import io.github.valerioisufi.pageboard.platform.syncLock
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
public class TileManager internal constructor(
    public val tileRenderer: TileRenderer = TileRenderer(),
    internal val tileCache: TileCache = TileCache(),
    onInvalidate: () -> Unit = {}
) {
    /** Callback invoked (possibly from a background thread) when the canvas must be redrawn. */
    internal var onInvalidate: () -> Unit = onInvalidate

    /** General scope for tile rendering jobs. */
    public val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Dedicated 2-thread worker for background tile rasterization. */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal val tileWorkerDispatcher = Dispatchers.Default.limitedParallelism(TILE_WORKERS)

    private val lock = SyncLock()
    private val tileQueue = TileQueue(maxWorkers = TILE_WORKERS)
    private val pageGenerations = mutableMapOf<Long, Int>()
    /** World Space size of each page used by its cached tiles (it changes with the window width). */
    private val pageWorldSizes = mutableMapOf<Long, Size>()
    private var bakeSequence = 0L

    /** The cells on screen, in screen pixels, that the last frame had no tile for. */
    private val blankCells = mutableListOf<Rect>()
    private val recentBakes = mutableMapOf<Long, MutableList<BakedElement>>()

    /** An element committed to a page and baked into its tiles: a stroke or a mark of the text of a PDF. */
    private class BakedElement(val sequence: Long, val stroke: StrokeElement? = null, val markup: TextMarkupElement? = null)

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
     * @param pageRects The World Space bounds of each page (indexed like [Document.pages]).
     * @param skipPageKey Page not drawn (e.g. the page being dragged while reordering).
     */
    internal fun drawTiles(
        canvas: Canvas,
        cameraMatrix: Matrix2D,
        viewportBounds: Rect,
        document: Document,
        pageRects: List<Rect>,
        skipPageKey: Long? = null
    ) {
        blankCells.clear()
        if (pageRects.isEmpty()) return
        tileCache.beginFrame()

        // 1. Calculate World Space viewport
        val screenToWorld = Matrix2D()
        if (!cameraMatrix.invert(screenToWorld)) return
        val worldViewport = screenToWorld.mapRect(viewportBounds)

        // 2. Determine current scale and LOD
        tileQueue.beginFrame()
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

            val prefetchLocalLeft = (worldViewport.left - pageWorldRect.left).coerceIn(0f, pageWidth)
            val prefetchLocalTop = (worldViewport.top - pageWorldRect.top).coerceIn(0f, pageHeight)
            val prefetchLocalRight = (worldViewport.right - pageWorldRect.left).coerceIn(0f, pageWidth)
            val prefetchLocalBottom = (worldViewport.bottom - pageWorldRect.top).coerceIn(0f, pageHeight)

            // The overview of the page, the base of its cells at the next zoom out
            TilePyramid.getPageIntersectingTileRange(
                localLeft = prefetchLocalLeft,
                localTop = prefetchLocalTop,
                localRight = prefetchLocalRight,
                localBottom = prefetchLocalBottom,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                lod = TilePyramid.OVERVIEW_LOD
            ).forEach { col, row ->
                val key = TileKey(page.renderKey, TilePyramid.OVERVIEW_LOD, col, row)
                // Stale, it is refreshed when it is drawn, not at every change of the page written
                if (tileCache.get(key) == null) {
                    val priority = if (isPageVisible) TilePriority.PageOverview else TilePriority.Margin
                    enqueueTile(key, priority, document, page, pageWorldRect)
                }
            }

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
                    val cellPageRect = TilePyramid.getTilePageRect(key)
                    val cell = pageFraction(cellPageRect, pageWorldRect)
                    // The center of the screen first
                    val dx = pageWorldRect.left + cellPageRect.center.x - worldViewport.center.x
                    val dy = pageWorldRect.top + cellPageRect.center.y - worldViewport.center.y
                    val order = dx * dx + dy * dy
                    val tile = tileCache.get(key)

                    if (tile != null && (tile.state == TileState.READY || tile.state == TileState.STALE)) {
                        drawTileBitmap(canvas, tile.bitmap, key, pageWorldRect, cameraMatrix)
                        onScreen.drawn += DrawnTile(tile, pageWorldRect.size, cell)
                        if (tile.state == TileState.STALE) {
                            enqueueTile(key, TilePriority.OnScreen, document, page, pageWorldRect, order)
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
                            if (ancestor.state == TileState.STALE) {
                                enqueueTile(ancestor.key, TilePriority.Refresh, document, page, pageWorldRect)
                            }
                        } else {
                            blankCells += tileScreenBounds
                        }
                        // What was on screen before the window was resized, under the sharper tiles
                        drawPreviousLayouts(canvas, onScreen, cell, pageWorldRect, cameraMatrix)
                        for (descendant in tileCache.findReadyDescendants(key)) {
                            drawTileBitmap(canvas, descendant.bitmap, descendant.key, pageWorldRect, cameraMatrix)
                            val coverage = pageFraction(TilePyramid.getTilePageRect(descendant.key), pageWorldRect)
                            onScreen.drawn += DrawnTile(descendant, pageWorldRect.size, coverage)
                        }
                        canvas.restore()

                        enqueueTile(key, TilePriority.OnScreen, document, page, pageWorldRect, order)
                    }
                }
            }

            // Prefetch range
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
                    enqueueTile(key, TilePriority.Margin, document, page, pageWorldRect)
                }
            }
            onScreen.endFrame()
        }
        tileQueue.endFrame()
        startTileWorkers()
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
    internal fun drawPageTiles(canvas: Canvas, page: Page, pageWorldRect: Rect, destScreenRect: Rect, cameraScale: Float) {
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
    internal fun updateLayout(pages: List<Page>, pageRects: List<Rect>) {
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
                tileQueue.removePage(pageKey)
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

    private fun bumpGeneration(pageKey: Long) {
        syncLock(lock) {
            pageGenerations[pageKey] = (pageGenerations[pageKey] ?: 0) + 1
        }
    }

    /**
     * Whether tiles are being rasterized: the pages on screen may still be drawn without them (a
     * screenshot test waits for them).
     */
    public val isRasterizing: Boolean get() = tileQueue.isBusy

    /**
     * Asks the workers for a tile, in the frame being drawn.
     *
     * @param order Which tile comes first within [priority], the lowest first.
     */
    private fun enqueueTile(
        key: TileKey,
        priority: TilePriority,
        document: Document,
        page: Page,
        pageWorldRect: Rect,
        order: Float = 0f
    ) {
        tileQueue.ask(key, priority, order) { rasterizeTile(key, document, page, pageWorldRect) }
    }

    /** Starts the workers the tiles asked for need, on the dedicated worker dispatcher. */
    private fun startTileWorkers() {
        while (tileQueue.addWorker()) {
            scope.launch(tileWorkerDispatcher) {
                while (true) {
                    val request = tileQueue.next() ?: break
                    try {
                        request.work()
                    } catch (failure: Throwable) {
                        tileQueue.retireWorker()
                        throw failure
                    } finally {
                        tileQueue.finished(request.key)
                    }
                    onInvalidate()
                }
            }
        }
    }

    /**
     * Rasterizes the tile [key] and publishes it in the cache.
     */
    private fun rasterizeTile(key: TileKey, document: Document, page: Page, pageWorldRect: Rect) {
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
                    if (stroke != null && !tileRenderer.isHidden(stroke.id)) painter.draw(tileCanvas, stroke.mesh, mmToTile)
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
     * @param pageRects The World Space bounds of each page (indexed like [Document.pages]).
     * @param commit Mutation adding the strokes to their pages, executed atomically with the bake
     * so that background workers never miss or double-draw them.
     */
    internal fun bakeStrokesDirectlySync(
        strokesByPage: Map<Int, List<StrokeElement>>,
        document: Document,
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
    internal fun bakeMarkupsDirectlySync(page: Page, pageWorldRect: Rect, markups: List<TextMarkupElement>, commit: () -> Unit) {
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

    private fun drawMarkup(canvas: Canvas, markup: TextMarkupElement, widthMm: Float, heightMm: Float, mmToTile: Matrix2D) {
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
    internal fun invalidatePageRegion(page: Page, dirtyMmRect: Rect, pageWorldRect: Rect?) {
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
     * Whether the tiles drawn in the last frame over [mmRect] (millimeters of [page]) show its
     * content as it is now: none of them stale (e.g. elements put back in the tiles, drawn over
     * them until then, see `SelectionManager`).
     */
    internal fun isRegionCurrent(page: Page, mmRect: io.github.valerioisufi.pageboard.model.geometry.Rect): Boolean {
        val onScreen = pageTiles[page.renderKey] ?: return true
        if (page.widthMm <= 0f || page.heightMm <= 0f) return true
        val region = Rect(mmRect.left / page.widthMm, mmRect.top / page.heightMm, mmRect.right / page.widthMm, mmRect.bottom / page.heightMm)
        return onScreen.drawn.none { it.coverage.overlaps(region) && it.tile.state != TileState.READY }
    }

    /**
     * Whether the last frame drew tiles of their own over all of [screenRect]: a cell waiting for
     * its first tile, with no coarser one to stand in, may show nothing of what the page holds.
     */
    internal fun isScreenRegionDrawn(screenRect: Rect): Boolean = blankCells.none { it.overlaps(screenRect) }

    /**
     * Invalidates every tile of the page identified by [pageKey].
     */
    internal fun invalidatePage(pageKey: Long) {
        bumpGeneration(pageKey)
        tileCache.invalidatePage(pageKey)
        onInvalidate()
    }

    /**
     * Evicts the tiles of the page identified by [pageKey] (e.g. after its deletion).
     */
    internal fun removeForPage(pageKey: Long) {
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
    internal fun retainDocumentPages(document: Document) {
        val live = document.pages.map { it.renderKey }.toSet()
        tileCache.retainPages(live)
        syncLock(lock) {
            recentBakes.keys.retainAll(live)
            pageWorldSizes.keys.retainAll(live)
        }
        pageTiles.keys.retainAll(live)
    }

    internal fun invalidateAll() {
        tileQueue.clear()
        syncLock(lock) {
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
    public fun renderThumbnail(document: Document, page: Page, widthPx: Int): ImageBitmap =
        tileRenderer.renderThumbnail(document, page, widthPx)

    internal fun cleanup() {
        scope.cancel()
        tileQueue.clear()
        syncLock(lock) {
            recentBakes.clear()
        }
        pageTiles.clear()
        tileCache.clear()
        tileRenderer.pdfAdapter?.close()
    }

    private companion object {
        /** How many tiles are rasterized at the same time. */
        const val TILE_WORKERS = 2
    }
}
