package io.github.valerioisufi.drawview.canvas.camera

import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.canvas.geometry.mapRect
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.px
import kotlin.math.round

/**
 * Handles mathematical layout, positioning, and coordinate calculations for document pages.
 *
 * Translates domain page dimensions into screen-space coordinates, factoring in margins,
 * scale factor, and camera transformations (pan/zoom) to identify visible pages with O(log N) lookup.
 *
 * @param density Screen pixel density (defaults to 1.0f).
 */
public class CalcPage(
    public var density: Float = 1f
) {
    /**
     * Flag indicating whether page layout bounds need recalculation.
     */
    internal var needToBeUpdated: Boolean = true

    /**
     * Mathematical boundaries for each page relative to the base, unscaled viewport.
     */
    internal val pagesRectOnWindow = mutableListOf<Rect>()

    /**
     * Bounding box encompassing all laid-out pages including their margins: the bounds of the
     * camera.
     */
    internal var contentRect: Rect = Rect.Zero

    /**
     * Spacing configuration for document layout.
     */
    internal data class PagePositionOnWindowOption(
        var horizontalPadding: Float = 8f,
        var topPadding: Float = 8f,
        var betweenPadding: Float = 8f,
        var bottomPadding: Float = 16f,
        /** The widest page at the zoom of the opening: a wide window keeps the desk beside it. */
        var maxPageWidth: Float = 880f
    )

    /**
     * Computes bounding rectangles for a sequence of pages stacked vertically in continuous scroll.
     * Content is scaled uniformly to fit the window's width based on the widest page (at most
     * [PagePositionOnWindowOption.maxPageWidth] wide, centered).
     *
     * Every page starts on a whole pixel: with the camera on whole pixels too, the tiles of a page
     * shown at their own scale fall on the pixels of the screen instead of being filtered half a
     * pixel off, which blurs them.
     */
    internal fun calcPagesRectOnWindow(
        pages: List<DrawPage>,
        windowRect: Rect,
        options: PagePositionOnWindowOption = PagePositionOnWindowOption()
    ) {
        pagesRectOnWindow.clear()
        if (pages.isEmpty() || windowRect.isEmpty) {
            contentRect = Rect.Zero
            needToBeUpdated = false
            return
        }

        val horizontalPadding = options.horizontalPadding * density
        val topPadding = options.topPadding * density
        val betweenPadding = options.betweenPadding * density
        val bottomPadding = options.bottomPadding * density

        val maxPageWidthMm = pages.maxOf { it.dimension.width.mm }.coerceAtLeast(1f)
        val availableWidth = (windowRect.width - horizontalPadding * 2).coerceIn(10f, maxOf(10f, options.maxPageWidth * density))
        val scaleFactor = availableWidth / maxPageWidthMm

        for (page in pages) {
            val pageWidth = page.dimension.width.mm * scaleFactor
            val pageHeight = page.dimension.calcHeightFromWidthPx(pageWidth.px)

            val top = round(if (pagesRectOnWindow.isEmpty()) topPadding else pagesRectOnWindow.last().bottom + betweenPadding)
            val left = round((windowRect.width / 2f) - (pageWidth / 2f))
            val right = left + pageWidth
            val bottom = top + pageHeight

            pagesRectOnWindow.add(Rect(left, top, right, bottom))
        }

        // The pages with their margin, not the window: a wide window keeps the desk beside them out of it
        contentRect = Rect(
            left = pagesRectOnWindow.minOf { it.left } - horizontalPadding,
            top = 0f,
            right = pagesRectOnWindow.maxOf { it.right } + horizontalPadding,
            bottom = pagesRectOnWindow.last().bottom + bottomPadding
        )
        needToBeUpdated = false
    }

    /**
     * Coupling of a dynamically transformed page rectangle with its index.
     */
    public data class PageRectWithIndex(
        val rect: Rect,
        val index: Int
    )

    /**
     * Identifies all pages currently visible within [windowRect] after applying [matrix].
     * Uses binary search to quickly isolate visible pages in large documents.
     */
    internal fun getPagesRectOnWindowTransformation(
        windowRect: Rect,
        matrix: Matrix2D
    ): List<PageRectWithIndex> {
        val visiblePages = mutableListOf<PageRectWithIndex>()
        if (pagesRectOnWindow.isEmpty() || windowRect.isEmpty) return visiblePages

        var startIndex = 0
        var endIndex = pagesRectOnWindow.size - 1
        var midIndex = 0

        while (startIndex <= endIndex) {
            midIndex = (startIndex + endIndex) / 2
            val transformed = matrix.mapRect(pagesRectOnWindow[midIndex])

            if (transformed.bottom < windowRect.top) {
                startIndex = midIndex + 1
            } else if (transformed.top > windowRect.bottom) {
                endIndex = midIndex - 1
            } else {
                visiblePages.add(PageRectWithIndex(transformed, midIndex))
                break
            }
        }

        if (visiblePages.isEmpty()) return visiblePages

        // Scan upwards
        var topIndex = midIndex - 1
        while (topIndex >= 0) {
            val transformed = matrix.mapRect(pagesRectOnWindow[topIndex])
            if (transformed.bottom > windowRect.top) {
                visiblePages.add(PageRectWithIndex(transformed, topIndex))
                topIndex--
            } else {
                break
            }
        }

        // Scan downwards
        var bottomIndex = midIndex + 1
        while (bottomIndex < pagesRectOnWindow.size) {
            val transformed = matrix.mapRect(pagesRectOnWindow[bottomIndex])
            if (transformed.top < windowRect.bottom) {
                visiblePages.add(PageRectWithIndex(transformed, bottomIndex))
                bottomIndex++
            } else {
                break
            }
        }

        visiblePages.sortBy { it.index }
        return visiblePages
    }
}
