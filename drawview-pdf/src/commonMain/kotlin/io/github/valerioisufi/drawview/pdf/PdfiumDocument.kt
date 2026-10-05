package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Native PDFium entry points (native/bridge/pdfium_bridge.h): JNI on the desktop JVM and on
 * Android, cinterop on iOS. Not available in the browser.
 */
internal expect object PdfiumBindings {
    /** Loads the native libraries: `false` when PDFium is not available on this platform. */
    fun load(): Boolean

    /** Opens a document from a file path: returns its handle, or 0 on failure. */
    fun openDocument(path: String, password: String?): Long

    fun pageCount(document: Long): Int

    /** Size of a page in points, or `null` when the page cannot be read. */
    fun pageSize(document: Long, pageIndex: Int): PdfSize?

    /** See [PdfDocument.renderPageSlice]. */
    fun renderPageSlice(
        document: Long,
        pageIndex: Int,
        startX: Int,
        startY: Int,
        sizeX: Int,
        sizeY: Int,
        widthPx: Int,
        heightPx: Int,
        rotate: Int
    ): ImageBitmap?

    /** Text of a page, or `null` when the page cannot be read. */
    fun pageText(document: Long, pageIndex: Int): String?

    fun closeDocument(document: Long)
}

/**
 * PDF document rendered by PDFium.
 */
internal class PdfiumDocument(private var handle: Long) : PdfDocument {

    override val pageCount: Int
        get() = if (handle != 0L) PdfiumBindings.pageCount(handle) else 0

    override fun getPageSize(pageIndex: Int): PdfSize {
        if (handle == 0L) return PdfSize(0.0, 0.0)
        return PdfiumBindings.pageSize(handle, pageIndex) ?: PdfSize(0.0, 0.0)
    }

    override fun renderPageSlice(
        pageIndex: Int,
        startX: Int,
        startY: Int,
        sizeX: Int,
        sizeY: Int,
        widthPx: Int,
        heightPx: Int,
        rotate: Int
    ): ImageBitmap? {
        if (handle == 0L || widthPx <= 0 || heightPx <= 0) return null
        return PdfiumBindings.renderPageSlice(handle, pageIndex, startX, startY, sizeX, sizeY, widthPx, heightPx, rotate)
    }

    override fun getPageText(pageIndex: Int): String? =
        if (handle != 0L) PdfiumBindings.pageText(handle, pageIndex) else null

    override fun close() {
        if (handle != 0L) {
            PdfiumBindings.closeDocument(handle)
            handle = 0L
        }
    }
}
