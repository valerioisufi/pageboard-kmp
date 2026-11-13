package io.github.valerioisufi.drawview.canvas.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.canvas.geometry.mapOffset
import io.github.valerioisufi.drawview.canvas.geometry.toModel
import io.github.valerioisufi.drawview.canvas.render.tile.TileManager
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.math.hypot

/**
 * What the editor shows of the document: the size of the canvas ([width], [height]), the pages
 * laid out one under the other ([layout], in world space) and the camera over them ([camera]);
 * the conversions between the screen, the world and the millimeters of the pages; the zoom and
 * the moves from page to page.
 *
 * Coordinate spaces:
 * - **page millimeters**: content of a page (strokes, texts, images), origin at the top-left
 *   corner of the page;
 * - **world**: pages laid out vertically by [layout] to fit the width of the canvas;
 * - **screen**: world transformed by the camera ([getRenderMatrix]).
 */
public class Viewport internal constructor(
    private val document: DrawDocument,
    density: Float,
    private val tileManager: TileManager,
    private val requestRedraw: () -> Unit
) {
    /** The pages laid out in world space. */
    public val layout: CalcPage = CalcPage(density = density)

    /** The camera: its pan, its zoom and their animations. */
    public val camera: CameraPhysicsEngine = CameraPhysicsEngine(
        density = density,
        getContentRect = { layout.contentRect }
    )

    /** Pixels per density-independent pixel of the screen showing the canvas. */
    public var density: Float = density
        set(value) {
            if (field == value) return
            val targetAnchor = initialAnchor ?: currentAnchor()
            val previousScale = camera.getCurrentScale()
            field = value
            layout.density = value
            camera.density = value
            layout.needToBeUpdated = true
            updatePageLayout()
            if (centerOn(targetAnchor, previousScale)) camera.restoreToBounds(animated = false)
        }

    /** Width of the canvas, in pixels. */
    public var width: Float by mutableFloatStateOf(0f)
        private set

    /** Height of the canvas, in pixels. */
    public var height: Float by mutableFloatStateOf(0f)
        private set

    private val cachedRenderMatrix = Matrix2D()
    private val cachedInverseMatrix = Matrix2D()
    private var initialAnchor: Anchor? = null

    /**
     * A point of a page: the index of the page, the point as fractions (`0..1`) of its width and
     * height, and the zoom to show it at (`null`: the zoom of whoever centers on it).
     */
    private class Anchor(val pageIndex: Int, val x: Float, val y: Float, val scale: Float? = null)

    /**
     * Remembers the page and normalized coordinates (`0..1`) to center on when the canvas is laid
     * out for the first time, at [scale] (`null`: the pages as wide as the canvas).
     */
    internal fun setInitialAnchor(pageIndex: Int, normX: Float, normY: Float, scale: Float? = null) {
        initialAnchor = Anchor(pageIndex, normX.coerceIn(0f, 1f), normY.coerceIn(0f, 1f), scale)
    }

    /**
     * Where the document is looked at: the page under the center of the canvas, the point of it
     * there and the zoom, or `null` before the canvas is laid out. Before the first layout, the
     * place it will show.
     */
    public fun currentPlace(): DocumentPlace? {
        val anchor = initialAnchor ?: currentAnchor() ?: return null
        val page = document.pages.getOrNull(anchor.pageIndex) ?: return null
        return DocumentPlace(
            pageId = page.id,
            pageNumber = anchor.pageIndex + 1,
            x = anchor.x,
            y = anchor.y,
            scale = anchor.scale ?: if (initialAnchor != null) 1f else camera.getCurrentScale()
        )
    }

    /**
     * Shows [place]: its point at the center of the canvas, at its zoom (within the bounds of the
     * camera). The page is found by its id, else by its number (the last page when there are
     * fewer). Before the first layout, remembers it for the first layout. Returns `false` when
     * the document has no page.
     */
    public fun showPlace(place: DocumentPlace): Boolean {
        val pages = document.pages
        if (pages.isEmpty()) return false
        val index = pages.indexOfFirst { it.id == place.pageId }.takeIf { it >= 0 }
            ?: (place.pageNumber - 1).coerceIn(0, pages.lastIndex)
        val anchor = Anchor(index, place.x.coerceIn(0f, 1f), place.y.coerceIn(0f, 1f), place.scale)
        if (width > 0f && height > 0f && centerOn(anchor, camera.getCurrentScale())) {
            camera.restoreToBounds(animated = false)
            requestRedraw()
        } else {
            initialAnchor = anchor
        }
        return true
    }

    /** The page under the center of the canvas, and the point of it there (normalized), or `null`. */
    private fun currentAnchor(): Anchor? {
        if (width <= 0f || layout.pagesRectOnWindow.isEmpty()) return null
        val center = screenToWorld(Offset(width / 2f, height / 2f))
        val rects = layout.pagesRectOnWindow
        var target = rects.lastIndex
        for (i in rects.indices) {
            val zoneBottom = if (i < rects.lastIndex) rects[i + 1].top else layout.contentRect.bottom
            if (center.y <= zoneBottom) {
                target = i
                break
            }
        }
        val r = rects[target]
        return Anchor(
            target,
            ((center.x - r.left) / r.width).coerceIn(0f, 1f),
            ((center.y - r.top) / r.height).coerceIn(0f, 1f)
        )
    }

    /**
     * Updates the size of the canvas, keeping the content under its center stable.
     */
    public fun setSize(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val sizeChanged = this.width != width || this.height != height
        if (!sizeChanged && !layout.needToBeUpdated && initialAnchor == null) return

        // Remember which point of which page is at the center of the viewport
        val anchor = currentAnchor()
        val previousScale = camera.getCurrentScale()

        this.width = width
        this.height = height
        camera.stopAllAnimations()
        camera.setViewport(width, height)
        layout.needToBeUpdated = true
        updatePageLayout()

        val targetAnchor = initialAnchor?.also { initialAnchor = null } ?: anchor
        if (!centerOn(targetAnchor, scale = if (anchor != null) previousScale else 1f)) {
            // First layout: show the top of the document at 1:1 (pages fit the viewport width)
            camera.centerOnWorldPoint(
                worldX = width / 2f,
                worldY = height / 2f,
                scale = 1f,
                screenWidth = width,
                screenHeight = height
            )
        }
        camera.restoreToBounds(animated = false)
        requestRedraw()
    }

    /**
     * Centers the camera on the point of a page [anchor] names, at its zoom (else at [scale]):
     * `false` when that page is not laid out.
     */
    private fun centerOn(anchor: Anchor?, scale: Float): Boolean {
        if (anchor == null || anchor.pageIndex !in layout.pagesRectOnWindow.indices || width <= 0f || height <= 0f) return false
        val r = layout.pagesRectOnWindow[anchor.pageIndex]
        camera.centerOnWorldPoint(
            worldX = r.left + r.width * anchor.x,
            worldY = r.top + r.height * anchor.y,
            scale = anchor.scale ?: scale,
            screenWidth = width,
            screenHeight = height
        )
        return true
    }

    /** Lays the pages out again (their sizes, their order or the canvas changed). */
    internal fun updatePageLayout() {
        if (width > 0f && height > 0f) {
            layout.calcPagesRectOnWindow(
                pages = document.pages,
                windowRect = Rect(0f, 0f, width, height)
            )
            // The pages laid out at another scale (window resized, widest page changed) are rasterized again
            tileManager.updateLayout(document.pages, layout.pagesRectOnWindow)
        }
    }

    /** The pages of the document changed (added, removed, moved, resized): lays them out again. */
    internal fun markLayoutNeedsUpdate() {
        val targetAnchor = initialAnchor ?: currentAnchor()
        val previousScale = camera.getCurrentScale()
        layout.needToBeUpdated = true
        updatePageLayout()
        tileManager.retainDocumentPages(document)
        centerOn(targetAnchor, previousScale)
        camera.restoreToBounds(animated = false)
        requestRedraw()
    }

    /**
     * Retrieves the current camera matrix (transforms World space -> Screen space).
     */
    internal fun getRenderMatrix(outMatrix: Matrix2D = cachedRenderMatrix): Matrix2D {
        return camera.getRenderMatrix(outMatrix)
    }

    /**
     * Retrieves the inverse camera matrix (transforms Screen space -> World space).
     */
    internal fun getInverseMatrix(outMatrix: Matrix2D = cachedInverseMatrix): Matrix2D {
        val render = camera.getRenderMatrix(Matrix2D())
        render.invert(outMatrix)
        return outMatrix
    }

    internal fun screenToWorld(screenPoint: Offset): Offset {
        val inverse = getInverseMatrix(Matrix2D())
        return inverse.mapOffset(screenPoint)
    }

    /** Matrix mapping the millimeters of page [pageIndex] to world space. */
    internal fun pageMmToWorld(pageIndex: Int): Matrix2D? {
        val page = document.pages.getOrNull(pageIndex) ?: return null
        val worldRect = layout.pagesRectOnWindow.getOrNull(pageIndex) ?: return null
        return Matrix2D().setRectToRect(page.rect(), worldRect.toModel())
    }

    /** Matrix mapping the millimeters of page [pageIndex] to screen pixels. */
    public fun pageMmToScreen(pageIndex: Int): Matrix2D? {
        val mmToWorld = pageMmToWorld(pageIndex) ?: return null
        return mmToWorld.postConcat(camera.getRenderMatrix(Matrix2D()))
    }

    /** Converts a screen point to the millimeters of page [pageIndex]. */
    internal fun screenToPageMm(pageIndex: Int, screenPoint: Offset): Offset? {
        val mmToScreen = pageMmToScreen(pageIndex) ?: return null
        val inverse = Matrix2D()
        if (!mmToScreen.invert(inverse)) return null
        return inverse.mapOffset(screenPoint)
    }

    /** Current screen pixels per millimeter of page [pageIndex]. */
    public fun pixelsPerMm(pageIndex: Int): Float {
        val page = document.pages.getOrNull(pageIndex) ?: return 1f
        val worldRect = layout.pagesRectOnWindow.getOrNull(pageIndex) ?: return 1f
        return worldRect.width / page.widthMm.coerceAtLeast(0.01f) * camera.getCurrentScale()
    }

    /** Pages intersecting the viewport, with their screen rectangles. */
    public fun visiblePages(): List<CalcPage.PageRectWithIndex> {
        if (width <= 0f || height <= 0f) return emptyList()
        return layout.getPagesRectOnWindowTransformation(
            Rect(0f, 0f, width, height),
            camera.getRenderMatrix(Matrix2D())
        )
    }

    /** Index of the page under [screenPoint], or `null`. */
    internal fun findPageIndexAtScreen(screenPoint: Offset): Int? =
        visiblePages().firstOrNull { it.rect.contains(screenPoint) }?.index

    /** Index of the visible page nearest to [screenPoint]. */
    internal fun nearestVisiblePageIndex(screenPoint: Offset): Int? =
        visiblePages().minByOrNull { hypot(it.rect.center.x - screenPoint.x, it.rect.center.y - screenPoint.y) }?.index

    public fun firstVisiblePageIndex(): Int? = visiblePages().firstOrNull()?.index

    /** Index of the page taking the largest part of the viewport (the page being looked at), or `null`. */
    public fun mostVisiblePageIndex(): Int? = visiblePages().maxByOrNull { page ->
        minOf(page.rect.bottom, height) - maxOf(page.rect.top, 0f)
    }?.index

    /**
     * Index of the page containing [worldPoint] (or the nearest one when [nearest] is `true`).
     */
    internal fun findPageIndexAtWorld(worldPoint: Offset, nearest: Boolean = false): Int {
        val rects = layout.pagesRectOnWindow
        for (i in rects.indices) {
            if (rects[i].contains(worldPoint)) return i
        }
        if (!nearest || rects.isEmpty()) return -1
        return rects.indices.minBy { i ->
            val r = rects[i]
            hypot(r.center.x - worldPoint.x, r.center.y - worldPoint.y)
        }
    }

    internal fun zoomBy(factor: Float, focus: Offset = Offset(width / 2f, height / 2f)) {
        camera.onDrag(0f, 0f, factor, focus.x, focus.y)
        camera.restoreToBounds()
        requestRedraw()
    }

    public fun zoomIn(): Unit = zoomBy(1.25f)

    public fun zoomOut(): Unit = zoomBy(0.8f)

    /**
     * Shows the top of the page [pageIndex] at the zoom level [scale] (the current one by default),
     * centered when the page is narrower than the viewport.
     */
    internal fun showPage(pageIndex: Int, scale: Float = camera.getCurrentScale()) {
        if (width <= 0f || height <= 0f) return
        val page = layout.pagesRectOnWindow.getOrNull(pageIndex) ?: return
        camera.centerOnWorldPoint(
            worldX = page.center.x,
            worldY = page.top + (height / 2f - camera.topPaddingPx) / scale,
            scale = scale,
            screenWidth = width,
            screenHeight = height
        )
        camera.restoreToBounds(animated = false)
        requestRedraw()
    }

    /** Shows the top of the page after the one being looked at. */
    public fun showNextPage() {
        val current = mostVisiblePageIndex() ?: return
        showPage((current + 1).coerceAtMost(document.pages.lastIndex))
    }

    /** Shows the top of the page before the one being looked at. */
    public fun showPreviousPage() {
        val current = mostVisiblePageIndex() ?: return
        showPage((current - 1).coerceAtLeast(0))
    }

    /** The pages as wide as the viewport (the zoom of the opening), from the top of the one being looked at. */
    public fun fitWidth() {
        showPage(mostVisiblePageIndex() ?: 0, scale = 1f)
    }

    /**
     * Pans the camera by ([dx], [dy]) screen pixels (e.g. to keep the edited text above the
     * software keyboard).
     */
    public fun panBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        camera.stopAllAnimations()
        camera.onDrag(dx, dy, 1f, width / 2f, height / 2f)
        requestRedraw()
    }
}
