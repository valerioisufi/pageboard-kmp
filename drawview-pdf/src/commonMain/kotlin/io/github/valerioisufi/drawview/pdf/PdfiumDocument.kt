package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.model.PdfTextLayout

/**
 * Native PDFium entry points (native/bridge/pdfium_bridge.h): JNI on the desktop JVM and on
 * Android, cinterop on iOS.
 */
internal expect object PdfiumBindings {
    /** Loads the native libraries: `false` when PDFium cannot be loaded. */
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

    /**
     * Characters of a page (Unicode code points) and their boxes (four values each, see
     * `pdfium_get_page_text_layout`), or `null` when the page cannot be read.
     */
    fun pageTextLayout(document: Long, pageIndex: Int): Pair<IntArray, FloatArray>?

    fun closeDocument(document: Long)

    // Writing (see pdfium_bridge.h: the coordinates are in the PDF user space of the page, the
    // matrices are six values a, b, c, d, e, f)

    /** Creates a new empty document: returns its handle, or 0 on failure. */
    fun newDocument(): Long

    /** Adds a page at the end of [document]: returns its handle, or 0 on failure. */
    fun newPage(document: Long, widthPt: Double, heightPt: Double): Long

    fun pageAddPath(page: Long, verbs: ByteArray, verbCount: Int, points: FloatArray, fillArgb: Int, strokeArgb: Int, strokeWidth: Float): Boolean

    fun pageAddJpeg(document: Long, page: Long, data: ByteArray, matrix: DoubleArray): Boolean

    /** [pixels] are ARGB, top row first. */
    fun pageAddBitmap(document: Long, page: Long, pixels: IntArray, width: Int, height: Int, matrix: DoubleArray): Boolean

    fun pageAddPdfPage(document: Long, page: Long, source: Long, pageIndex: Int, matrix: DoubleArray): Boolean

    fun pageAddMarkup(page: Long, kind: Int, quads: FloatArray, argb: Int): Boolean

    /** Loads a TrueType font into [document]: returns its handle, or 0 on failure. */
    fun loadFont(document: Long, data: ByteArray): Long

    fun closeFont(font: Long)

    fun pageAddText(document: Long, page: Long, font: Long, text: String, fontSize: Float, argb: Int, invisible: Boolean, matrix: DoubleArray): Boolean

    /** Writes the content of [page] and closes it. */
    fun endPage(page: Long): Boolean

    fun saveDocument(document: Long, path: String): Boolean
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

    override fun getPageTextLayout(pageIndex: Int): PdfTextLayout? {
        if (handle == 0L) return null
        val size = PdfiumBindings.pageSize(handle, pageIndex) ?: return null
        val (chars, boxes) = PdfiumBindings.pageTextLayout(handle, pageIndex) ?: return null
        return PdfTextLayout(size.widthPt.toFloat(), size.heightPt.toFloat(), chars, boxes)
    }

    override fun close() {
        if (handle != 0L) {
            PdfiumBindings.closeDocument(handle)
            handle = 0L
        }
    }
}
