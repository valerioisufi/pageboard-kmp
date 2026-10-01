package io.github.valerioisufi.drawview.core.render.tile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import io.github.valerioisufi.drawview.core.canvas.StrokeMeshPainter
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.readSnapshot
import io.github.valerioisufi.drawview.core.util.SyncLock
import io.github.valerioisufi.drawview.core.util.syncLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Manages the multi-scale, background-rasterized tile grid for low-latency rendering.
 *
 * Handles tile caching, visibility culling, hierarchical mipmap fallback rendering,
 * asynchronous background rasterization, and synchronous main-thread stroke baking.
 *
 * Every page has a content *generation*, incremented by each invalidation: a tile rasterized
 * from a snapshot older than the current generation is published as [TileState.STALE] (so it
 * is rendered again), and the strokes committed while a tile was being rasterized are baked into
 * the result before it is published, so that freshly drawn strokes never disappear.
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
    private val recentBakes = mutableMapOf<Long, MutableList<Pair<Long, DrawStroke>>>()

    private val filterPaint = Paint().apply {
        filterQuality = FilterQuality.Medium
        isAntiAlias = true
    }
    private val bakePainter = StrokeMeshPainter()
    private val scratchTileToScreen = Matrix2D()
    private val scratchMmToTile = Matrix2D()

    /**
     * Draws visible and pre-fetched tiles on [canvas].
     *
     * @param canvas The destination canvas.
     * @param cameraMatrix The current camera transformation matrix.
     * @param viewportBounds Visible screen bounds in pixels.
     * @param document The document data model.
     * @param pageRects The World Space bounds of each page (indexed like [DrawDocument.pages]).
     * @param skipPageUid Page not drawn (e.g. the page being dragged while reordering).
     */
    fun drawTiles(
        canvas: Canvas,
        cameraMatrix: Matrix2D,
        viewportBounds: Rect,
        document: DrawDocument,
        pageRects: List<Rect>,
        skipPageUid: Long? = null
    ) {
        if (pageRects.isEmpty()) return

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
            if (page.uid == skipPageUid) continue
            val pageWorldRect = pageRects[i]
            ensurePageGeometry(page.uid, pageWorldRect)

            // Culling: check if page intersects prefetch viewport
            val nearPage = pageWorldRect.left < prefetchRight &&
                    prefetchLeft < pageWorldRect.right &&
                    pageWorldRect.top < prefetchBottom &&
                    prefetchTop < pageWorldRect.bottom
            if (!nearPage) continue

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
                    val key = TileKey(page.uid, targetLod, col, row)
                    val tile = tileCache.get(key)

                    if (tile != null && (tile.state == TileState.READY || tile.state == TileState.STALE)) {
                        drawTileBitmap(canvas, tile.bitmap, key, pageWorldRect, cameraMatrix)
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

                        val ancestor = tileCache.findNearestAncestor(key)
                        if (ancestor != null) {
                            canvas.save()
                            canvas.clipRect(tileScreenBounds)
                            drawTileBitmap(canvas, ancestor.bitmap, ancestor.key, pageWorldRect, cameraMatrix)
                            canvas.restore()
                        } else {
                            val descendants = tileCache.findReadyDescendants(key)
                            if (descendants.isNotEmpty()) {
                                canvas.save()
                                canvas.clipRect(tileScreenBounds)
                                for (descendant in descendants) {
                                    drawTileBitmap(canvas, descendant.bitmap, descendant.key, pageWorldRect, cameraMatrix)
                                }
                                canvas.restore()
                            }
                        }

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
                val key = TileKey(page.uid, targetLod, col, row)
                val cached = tileCache.get(key)
                if (cached == null || cached.state == TileState.STALE) {
                    enqueueTile(key, document, page, pageWorldRect)
                }
            }
        }
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
            val key = TileKey(page.uid, lod, col, row)
            val tile = tileCache.get(key) ?: tileCache.findNearestAncestor(key) ?: return@forEach
            drawTileBitmap(canvas, tile.bitmap, tile.key, pageWorldRect, worldToDest)
        }
        canvas.restore()
    }

    private fun drawTileBitmap(
        canvas: Canvas,
        bitmap: ImageBitmap,
        key: TileKey,
        pageWorldRect: Rect,
        cameraMatrix: Matrix2D
    ) {
        val tileToScreen = TilePyramid.getTileToScreenMatrix(key, pageWorldRect, cameraMatrix, scratchTileToScreen)
        canvas.save()
        canvas.concat(tileToScreen.toComposeMatrix())
        canvas.drawImage(bitmap, Offset.Zero, filterPaint)
        canvas.restore()
    }

    /**
     * Drops the tiles of the page when its World Space size changed (e.g. the window was resized
     * after the first frames): they were rasterized at another scale.
     */
    private fun ensurePageGeometry(pageUid: Long, pageWorldRect: Rect) {
        val size = pageWorldRect.size
        val changed = syncLock(lock) {
            val previous = pageWorldSizes.put(pageUid, size)
            if (previous != null && previous != size) {
                pageGenerations[pageUid] = (pageGenerations[pageUid] ?: 0) + 1
                recentBakes.remove(pageUid)
                true
            } else {
                false
            }
        }
        if (changed) tileCache.removeForPage(pageUid)
    }

    private fun generationOf(pageUid: Long): Int = syncLock(lock) { pageGenerations[pageUid] ?: 0 }

    private fun bumpGeneration(pageUid: Long) {
        syncLock(lock) {
            pageGenerations[pageUid] = (pageGenerations[pageUid] ?: 0) + 1
        }
    }

    /**
     * Enqueues a tile for background rasterization on the dedicated worker dispatcher.
     */
    fun enqueueTile(
        key: TileKey,
        document: DrawDocument,
        page: DrawPage,
        pageWorldRect: Rect
    ) {
        syncLock(lock) {
            if (activeTileJobs.containsKey(key)) return
            // Placeholder entry: the real job is registered right after launch
            activeTileJobs[key] = Job()
        }

        val job = scope.launch(tileWorkerDispatcher) {
            try {
                // Read generation, bake sequence and content atomically with respect to
                // bakeStrokesDirectlySync (which mutates the page under the same lock).
                var generation = 0
                var sequence = 0L
                val content = syncLock(lock) {
                    generation = pageGenerations[key.pageUid] ?: 0
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
                    val candidates = recentBakes[key.pageUid]?.filter { it.first > sequence }.orEmpty()
                    // A stroke added to the page between the sequence read and the snapshot is
                    // already part of the rendered content.
                    val rendered = if (candidates.isEmpty()) emptySet() else content.strokes.toHashSet()
                    val pending = candidates.filter { it.second !in rendered }
                    if (pending.isNotEmpty()) {
                        val mmToTile = TilePyramid.getMmToTileMatrix(key, content.rect(), pageWorldRect)
                        val tileCanvas = Canvas(bitmap)
                        val painter = StrokeMeshPainter()
                        for ((_, stroke) in pending) {
                            if (!stroke.isDragging) painter.draw(tileCanvas, stroke.mesh, mmToTile)
                        }
                    }
                    val upToDate = (pageGenerations[key.pageUid] ?: 0) == generation
                    // A tile rasterized for an outdated page geometry is discarded
                    val sameGeometry = pageWorldSizes[key.pageUid]?.let { it == pageWorldRect.size } ?: true
                    if (sameGeometry) {
                        tileCache.put(Tile(key, bitmap, if (upToDate) TileState.READY else TileState.STALE))
                    }
                }
            } finally {
                syncLock(lock) {
                    activeTileJobs.remove(key)
                }
            }
            onInvalidate()
        }

        syncLock(lock) {
            if (activeTileJobs.containsKey(key)) activeTileJobs[key] = job
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
                val pageRectMm = page.rect()

                val bakes = recentBakes.getOrPut(page.uid) { mutableListOf() }
                for (stroke in pageStrokes) {
                    bakeSequence++
                    bakes.add(bakeSequence to stroke)
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
                            val key = TileKey(page.uid, lod, col, row)
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
     * Invalidates the tiles of [page] intersecting [dirtyMmRect] (page millimeters): they keep
     * being drawn (no flash) while a fresh version is rasterized in background.
     */
    fun invalidatePageRegion(page: DrawPage, dirtyMmRect: Rect, pageWorldRect: Rect?) {
        bumpGeneration(page.uid)
        val scaleX = if (pageWorldRect != null && page.widthMm > 0f) pageWorldRect.width / page.widthMm else null
        val scaleY = if (pageWorldRect != null && page.heightMm > 0f) pageWorldRect.height / page.heightMm else null
        if (scaleX == null || scaleY == null) {
            tileCache.invalidatePage(page.uid)
        } else {
            val margin = 8f
            val localBox = Rect(
                dirtyMmRect.left * scaleX - margin,
                dirtyMmRect.top * scaleY - margin,
                dirtyMmRect.right * scaleX + margin,
                dirtyMmRect.bottom * scaleY + margin
            )
            tileCache.invalidatePage(page.uid) { key ->
                TilePyramid.getTilePageRect(key).overlaps(localBox)
            }
        }
        onInvalidate()
    }

    /**
     * Invalidates every tile of the page identified by [pageUid].
     */
    fun invalidatePage(pageUid: Long) {
        bumpGeneration(pageUid)
        tileCache.invalidatePage(pageUid)
        onInvalidate()
    }

    /**
     * Evicts the tiles of the page identified by [pageUid] (e.g. after its deletion).
     */
    fun removeForPage(pageUid: Long) {
        bumpGeneration(pageUid)
        tileCache.removeForPage(pageUid)
        syncLock(lock) {
            recentBakes.remove(pageUid)
            pageWorldSizes.remove(pageUid)
        }
        onInvalidate()
    }

    /**
     * Drops the tiles of pages that are no longer part of [document].
     */
    fun retainDocumentPages(document: DrawDocument) {
        val live = readSnapshot { document.pages.map { it.uid }.toSet() }
        tileCache.retainPages(live)
        syncLock(lock) {
            recentBakes.keys.retainAll(live)
            pageWorldSizes.keys.retainAll(live)
        }
    }

    fun invalidateAll() {
        syncLock(lock) {
            activeTileJobs.values.forEach { it.cancel() }
            activeTileJobs.clear()
            for (uid in pageGenerations.keys.toList()) {
                pageGenerations[uid] = (pageGenerations[uid] ?: 0) + 1
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
        tileCache.clear()
        tileRenderer.pdfAdapter?.close()
    }
}
