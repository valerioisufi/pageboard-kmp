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
class Viewport internal constructor(
    private val document: DrawDocument,
    density: Float,
    private val tileManager: TileManager,
    private val requestRedraw: () -> Unit
) {
    /** The pages laid out in world space. */
    val layout = CalcPage(density = density)

    /** The camera: its pan, its zoom and their animations. */
    val camera = CameraPhysicsEngine(
        density = density,
        getContentRect = { layout.contentRect }
    )

    /** Pixels per density-independent pixel of the screen showing the canvas. */
    var density: Float = density
        set(value) {
            if (field == value) return
            field = value
            layout.density = value
            camera.density = value
            layout.needToBeUpdated = true
            updatePageLayout()
        }

    /** Width of the canvas, in pixels. */
    var width by mutableFloatStateOf(0f)
        private set

    /** Height of the canvas, in pixels. */
    var height by mutableFloatStateOf(0f)
        private set

    private val cachedRenderMatrix = Matrix2D()
    private val cachedInverseMatrix = Matrix2D()

    /**
     * Updates the size of the canvas, keeping the content under its center stable.
     */
    fun setSize(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val sizeChanged = this.width != width || this.height != height
        if (!sizeChanged && !layout.needToBeUpdated) return

        // Remember which point of which page is at the center of the viewport
        var anchor: Triple<Int, Float, Float>? = null
        val previousScale = camera.getCurrentScale()
        val hadLayout = this.width > 0f && layout.pagesRectOnWindow.isNotEmpty()
        if (hadLayout) {
            val center = screenToWorld(Offset(this.width / 2f, this.height / 2f))
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
            anchor = Triple(
                target,
                ((center.x - r.left) / r.width).coerceIn(0f, 1f),
                ((center.y - r.top) / r.height).coerceIn(0f, 1f)
            )
        }

        this.width = width
        this.height = height
        camera.stopAllAnimations()
        camera.setViewport(width, height)
        layout.needToBeUpdated = true
        updatePageLayout()

        if (anchor != null && anchor.first in layout.pagesRectOnWindow.indices) {
            val r = layout.pagesRectOnWindow[anchor.first]
            camera.centerOnWorldPoint(
                worldX = r.left + r.width * anchor.second,
                worldY = r.top + r.height * anchor.third,
                scale = previousScale,
                screenWidth = width,
                screenHeight = height
            )
        } else {
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

    /** Lays the pages out again (their sizes, their order or the canvas changed). */
    fun updatePageLayout() {
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
    fun markLayoutNeedsUpdate() {
        layout.needToBeUpdated = true
        updatePageLayout()
        tileManager.retainDocumentPages(document)
        camera.restoreToBounds(animated = false)
        requestRedraw()
    }

    /**
     * Retrieves the current camera matrix (transforms World space -> Screen space).
     */
    fun getRenderMatrix(outMatrix: Matrix2D = cachedRenderMatrix): Matrix2D {
        return camera.getRenderMatrix(outMatrix)
    }

    /**
     * Retrieves the inverse camera matrix (transforms Screen space -> World space).
     */
    fun getInverseMatrix(outMatrix: Matrix2D = cachedInverseMatrix): Matrix2D {
        val render = camera.getRenderMatrix(Matrix2D())
        render.invert(outMatrix)
        return outMatrix
    }

    fun screenToWorld(screenPoint: Offset): Offset {
        val inverse = getInverseMatrix(Matrix2D())
        return inverse.mapOffset(screenPoint)
    }

    fun worldToScreen(worldPoint: Offset): Offset {
        val render = camera.getRenderMatrix(Matrix2D())
        return render.mapOffset(worldPoint)
    }

    /** Matrix mapping the millimeters of page [pageIndex] to world space. */
    fun pageMmToWorld(pageIndex: Int): Matrix2D? {
        val page = document.pages.getOrNull(pageIndex) ?: return null
        val worldRect = layout.pagesRectOnWindow.getOrNull(pageIndex) ?: return null
        return Matrix2D().setRectToRect(page.rect(), worldRect.toModel())
    }

    /** Matrix mapping the millimeters of page [pageIndex] to screen pixels. */
    fun pageMmToScreen(pageIndex: Int): Matrix2D? {
        val mmToWorld = pageMmToWorld(pageIndex) ?: return null
        return mmToWorld.postConcat(camera.getRenderMatrix(Matrix2D()))
    }

    /** Converts a screen point to the millimeters of page [pageIndex]. */
    fun screenToPageMm(pageIndex: Int, screenPoint: Offset): Offset? {
        val mmToScreen = pageMmToScreen(pageIndex) ?: return null
        val inverse = Matrix2D()
        if (!mmToScreen.invert(inverse)) return null
        return inverse.mapOffset(screenPoint)
    }

    /** Current screen pixels per millimeter of page [pageIndex]. */
    fun pixelsPerMm(pageIndex: Int): Float {
        val page = document.pages.getOrNull(pageIndex) ?: return 1f
        val worldRect = layout.pagesRectOnWindow.getOrNull(pageIndex) ?: return 1f
        return worldRect.width / page.widthMm.coerceAtLeast(0.01f) * camera.getCurrentScale()
    }

    /** Pages intersecting the viewport, with their screen rectangles. */
    fun visiblePages(): List<CalcPage.PageRectWithIndex> {
        if (width <= 0f || height <= 0f) return emptyList()
        return layout.getPagesRectOnWindowTransformation(
            Rect(0f, 0f, width, height),
            camera.getRenderMatrix(Matrix2D())
        )
    }

    /** Index of the page under [screenPoint], or `null`. */
    fun findPageIndexAtScreen(screenPoint: Offset): Int? =
        visiblePages().firstOrNull { it.rect.contains(screenPoint) }?.index

    /** Index of the visible page nearest to [screenPoint]. */
    fun nearestVisiblePageIndex(screenPoint: Offset): Int? =
        visiblePages().minByOrNull { hypot(it.rect.center.x - screenPoint.x, it.rect.center.y - screenPoint.y) }?.index

    fun firstVisiblePageIndex(): Int? = visiblePages().firstOrNull()?.index

    /** Index of the page taking the largest part of the viewport (the page being looked at), or `null`. */
    fun mostVisiblePageIndex(): Int? = visiblePages().maxByOrNull { page ->
        minOf(page.rect.bottom, height) - maxOf(page.rect.top, 0f)
    }?.index

    /**
     * Index of the page containing [worldPoint] (or the nearest one when [nearest] is `true`).
     */
    fun findPageIndexAtWorld(worldPoint: Offset, nearest: Boolean = false): Int {
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

    fun zoomBy(factor: Float, focus: Offset = Offset(width / 2f, height / 2f)) {
        camera.onDrag(0f, 0f, factor, focus.x, focus.y)
        camera.restoreToBounds()
        requestRedraw()
    }

    fun zoomIn() = zoomBy(1.25f)

    fun zoomOut() = zoomBy(0.8f)

    /**
     * Shows the top of the page [pageIndex] at the zoom level [scale] (the current one by default),
     * centered when the page is narrower than the viewport.
     */
    fun showPage(pageIndex: Int, scale: Float = camera.getCurrentScale()) {
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
    fun showNextPage() {
        val current = mostVisiblePageIndex() ?: return
        showPage((current + 1).coerceAtMost(document.pages.lastIndex))
    }

    /** Shows the top of the page before the one being looked at. */
    fun showPreviousPage() {
        val current = mostVisiblePageIndex() ?: return
        showPage((current - 1).coerceAtLeast(0))
    }

    /** The pages as wide as the viewport (the zoom of the opening), from the top of the one being looked at. */
    fun fitWidth() {
        showPage(mostVisiblePageIndex() ?: 0, scale = 1f)
    }

    /**
     * Pans the camera by ([dx], [dy]) screen pixels (e.g. to keep the edited text above the
     * software keyboard).
     */
    fun panBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        camera.stopAllAnimations()
        camera.onDrag(dx, dy, 1f, width / 2f, height / 2f)
        requestRedraw()
    }

    fun centerDocument() {
        if (width <= 0f || height <= 0f) return
        val content = layout.contentRect
        if (content.isEmpty) return
        camera.centerOnWorldPoint(
            worldX = content.center.x,
            worldY = content.top + (height / 2f),
            scale = 1f,
            screenWidth = width,
            screenHeight = height
        )
        camera.restoreToBounds(animated = false)
        requestRedraw()
    }
}
