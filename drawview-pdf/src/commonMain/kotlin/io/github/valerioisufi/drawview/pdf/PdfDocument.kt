package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Handle to an open PDF document for page inspection and rasterization.
 */
interface PdfDocument : AutoCloseable {
    /**
     * Total number of pages in the document.
     */
    val pageCount: Int

    /**
     * Returns the physical dimensions of the specified page in points (pt) and millimeters (mm).
     */
    fun getPageSize(pageIndex: Int): PdfSize

    /**
     * Renders an entire page into a raster ImageBitmap at the specified pixel resolution.
     */
    fun renderPage(
        pageIndex: Int,
        widthPx: Int,
        heightPx: Int,
        rotate: Int = 0
    ): ImageBitmap? = renderPageSlice(pageIndex, 0, 0, widthPx, heightPx, widthPx, heightPx, rotate)

    /**
     * Renders a region of a page into a [widthPx] x [heightPx] bitmap: the whole page is scaled
     * to [sizeX] x [sizeY] pixels and placed at ([startX], [startY]) in the bitmap.
     */
    fun renderPageSlice(
        pageIndex: Int,
        startX: Int,
        startY: Int,
        sizeX: Int,
        sizeY: Int,
        widthPx: Int,
        heightPx: Int,
        rotate: Int = 0
    ): ImageBitmap?

    /**
     * Text of the specified page (the text layer of the PDF: empty for a scanned page), or `null`
     * when the page cannot be read.
     */
    fun getPageText(pageIndex: Int): String?

    /**
     * Closes the document and releases native resources.
     */
    override fun close()
}
