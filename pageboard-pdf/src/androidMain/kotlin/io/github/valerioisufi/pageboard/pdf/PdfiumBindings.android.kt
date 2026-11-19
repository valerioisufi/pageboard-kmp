package io.github.valerioisufi.pageboard.pdf

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.IntBuffer

internal actual object PdfiumBindings {
    actual fun load(): Boolean = PdfiumNative.isAvailable

    actual fun openDocument(path: String, password: String?): Long = PdfiumNative.nOpenDocument(path, password)

    actual fun pageCount(document: Long): Int = PdfiumNative.nGetPageCount(document)

    actual fun pageSize(document: Long, pageIndex: Int): PdfSize? {
        val buf = DoubleArray(2)
        return if (PdfiumNative.nGetPageSize(document, pageIndex, buf)) PdfSize(buf[0], buf[1]) else null
    }

    actual fun renderPageSlice(
        document: Long,
        pageIndex: Int,
        startX: Int,
        startY: Int,
        sizeX: Int,
        sizeY: Int,
        widthPx: Int,
        heightPx: Int,
        rotate: Int
    ): ImageBitmap? {
        val pixels = IntArray(widthPx * heightPx)
        val ok = PdfiumNative.nRenderPageBitmap(
            document, pageIndex,
            startX, startY, sizeX, sizeY,
            widthPx, heightPx, rotate, pixels
        )
        if (!ok) return null
        // PDFium writes R, G, B, A bytes (FPDF_REVERSE_BYTE_ORDER): the memory layout of ARGB_8888
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(IntBuffer.wrap(pixels))
        return bitmap.asImageBitmap()
    }

    actual fun pageText(document: Long, pageIndex: Int): String? = PdfiumNative.nGetPageText(document, pageIndex)

    actual fun pageTextLayout(document: Long, pageIndex: Int): Pair<IntArray, FloatArray>? {
        val count = PdfiumNative.nGetPageTextLayout(document, pageIndex, null, null)
        if (count < 0) return null
        val chars = IntArray(count)
        val boxes = FloatArray(count * 4)
        if (count > 0 && PdfiumNative.nGetPageTextLayout(document, pageIndex, chars, boxes) != count) return null
        return chars to boxes
    }

    actual fun closeDocument(document: Long) = PdfiumNative.nCloseDocument(document)

    actual fun newDocument(): Long = PdfiumNative.nNewDocument()

    actual fun newPage(document: Long, index: Int, widthPt: Double, heightPt: Double): Long =
        PdfiumNative.nNewPage(document, index, widthPt, heightPt)

    actual fun importPages(document: Long, source: Long, pageIndices: IntArray): Boolean =
        PdfiumNative.nImportPages(document, source, pageIndices)

    actual fun placePage(document: Long, fromIndex: Int, toIndex: Int, matrix: DoubleArray): Long =
        PdfiumNative.nPlacePage(document, fromIndex, toIndex, matrix)

    actual fun deletePage(document: Long, index: Int): Boolean = PdfiumNative.nDeletePage(document, index)

    actual fun pageAddPath(page: Long, verbs: ByteArray, verbCount: Int, points: FloatArray, fillArgb: Int, strokeArgb: Int, strokeWidth: Float): Boolean =
        PdfiumNative.nPageAddPath(page, verbs, verbCount, points, fillArgb, strokeArgb, strokeWidth)

    actual fun pageAddJpeg(document: Long, page: Long, data: ByteArray, matrix: DoubleArray): Boolean =
        PdfiumNative.nPageAddJpeg(document, page, data, matrix)

    actual fun pageAddBitmap(document: Long, page: Long, pixels: IntArray, width: Int, height: Int, matrix: DoubleArray): Boolean =
        PdfiumNative.nPageAddBitmap(document, page, pixels, width, height, matrix)

    actual fun pageAddPdfPage(document: Long, page: Long, source: Long, pageIndex: Int, matrix: DoubleArray): Boolean =
        PdfiumNative.nPageAddPdfPage(document, page, source, pageIndex, matrix)

    actual fun pageAddMarkup(page: Long, kind: Int, quads: FloatArray, argb: Int, appearance: String?): Boolean =
        PdfiumNative.nPageAddMarkup(page, kind, quads, argb, appearance)

    actual fun loadFont(document: Long, data: ByteArray): Long = PdfiumNative.nLoadFont(document, data)

    actual fun closeFont(font: Long) = PdfiumNative.nCloseFont(font)

    actual fun pageAddText(document: Long, page: Long, font: Long, text: String, fontSize: Float, argb: Int, invisible: Boolean, matrix: DoubleArray): Boolean =
        PdfiumNative.nPageAddText(document, page, font, text, fontSize, argb, invisible, matrix)

    actual fun endPage(page: Long): Boolean = PdfiumNative.nEndPage(page)

    actual fun saveDocument(document: Long, path: String, info: String?): Boolean = PdfiumNative.nSaveDocument(document, path, info)
}
