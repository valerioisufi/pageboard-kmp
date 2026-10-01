package io.github.valerioisufi.drawview.core.camera

import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.geometry.px
import io.github.valerioisufi.drawview.core.model.DrawPage

/**
 * Handles mathematical layout, positioning, and coordinate calculations for document pages.
 *
 * Translates domain page dimensions into screen-space coordinates, factoring in margins,
 * scale factor, and camera transformations (pan/zoom) to identify visible pages with O(log N) lookup.
 *
 * @param density Screen pixel density (defaults to 1.0f).
 */
class CalcPage(
    var density: Float = 1f
) {
    /**
     * Flag indicating whether page layout bounds need recalculation.
     */
    var needToBeUpdated: Boolean = true

    /**
     * Mathematical boundaries for each page relative to the base, unscaled viewport.
     */
    val pagesRectOnWindow = mutableListOf<Rect>()

    /**
     * Bounding box encompassing all laid-out pages including their margins.
     */
    var contentRect: Rect = Rect.Zero

    /**
     * Spacing configuration for document layout.
     */
    data class PagePositionOnWindowOption(
        var horizontalPadding: Float = 8f,
        var topPadding: Float = 8f,
        var betweenPadding: Float = 8f,
        var bottomPadding: Float = 16f
    )

    /**
     * Computes bounding rectangles for a sequence of pages stacked vertically in continuous scroll.
     * Content is scaled uniformly to fit the window's width based on the widest page.
     */
    fun calcPagesRectOnWindow(
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
        val availableWidth = (windowRect.width - horizontalPadding * 2).coerceAtLeast(10f)
        val scaleFactor = availableWidth / maxPageWidthMm

        var leftMostPosition = horizontalPadding
        var rightMostPosition = windowRect.width - horizontalPadding

        for (page in pages) {
            val pageWidth = page.dimension.width.mm * scaleFactor
            val pageHeight = page.dimension.calcHeightFromWidthPx(pageWidth.px)

            val top = if (pagesRectOnWindow.isEmpty()) topPadding else pagesRectOnWindow.last().bottom + betweenPadding
            val left = (windowRect.width / 2f) - (pageWidth / 2f)
            val right = left + pageWidth
            val bottom = top + pageHeight

            val rect = Rect(left, top, right, bottom)
            if (rect.left < leftMostPosition) leftMostPosition = rect.left
            if (rect.right > rightMostPosition) rightMostPosition = rect.right

            pagesRectOnWindow.add(rect)
        }

        val bottomMost = if (pagesRectOnWindow.isNotEmpty()) pagesRectOnWindow.last().bottom + bottomPadding else windowRect.height

        contentRect = Rect(
            left = leftMostPosition - horizontalPadding,
            top = 0f,
            right = rightMostPosition + horizontalPadding,
            bottom = bottomMost
        )
        needToBeUpdated = false
    }

    /**
     * Coupling of a dynamically transformed page rectangle with its index.
     */
    data class PageRectWithIndex(
        val rect: Rect,
        val index: Int
    )

    /**
     * Identifies all pages currently visible within [windowRect] after applying [matrix].
     * Uses binary search to quickly isolate visible pages in large documents.
     */
    fun getPagesRectOnWindowTransformation(
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
