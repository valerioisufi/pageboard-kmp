package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.model.PdfTextLayout

/**
 * Handle to an open PDF document for page inspection and rasterization.
 */
public interface PdfDocument : AutoCloseable {
    /**
     * Total number of pages in the document.
     */
    public val pageCount: Int

    /**
     * Returns the physical dimensions of the specified page in points (pt) and millimeters (mm).
     */
    public fun getPageSize(pageIndex: Int): PdfSize

    /**
     * Renders an entire page into a raster ImageBitmap at the specified pixel resolution.
     */
    public fun renderPage(
        pageIndex: Int,
        widthPx: Int,
        heightPx: Int,
        rotate: Int = 0
    ): ImageBitmap? = renderPageSlice(pageIndex, 0, 0, widthPx, heightPx, widthPx, heightPx, rotate)

    /**
     * Renders a region of a page into a [widthPx] x [heightPx] bitmap: the whole page is scaled
     * to [sizeX] x [sizeY] pixels and placed at ([startX], [startY]) in the bitmap.
     */
    public fun renderPageSlice(
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
    public fun getPageText(pageIndex: Int): String?

    /**
     * Characters of the text of the specified page with their boxes (no character for a scanned
     * page), or `null` when the page cannot be read.
     */
    public fun getPageTextLayout(pageIndex: Int): PdfTextLayout?

    /**
     * Closes the document and releases native resources.
     */
    override fun close()
}
