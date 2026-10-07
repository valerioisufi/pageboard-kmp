package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal actual object PdfiumBindings {
    actual fun load(): Boolean = PdfiumNativeLoader.isAvailable

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
        // PDFium writes R, G, B, A bytes (FPDF_REVERSE_BYTE_ORDER)
        val pixels = IntArray(widthPx * heightPx)
        val ok = PdfiumNative.nRenderPageBitmap(
            document, pageIndex,
            startX, startY, sizeX, sizeY,
            widthPx, heightPx, rotate, pixels
        )
        if (!ok) return null

        val bytes = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.nativeOrder())
        bytes.asIntBuffer().put(pixels)

        val info = ImageInfo(
            ColorInfo(ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB),
            widthPx,
            heightPx
        )
        val skiaBmp = Bitmap()
        skiaBmp.allocPixels(info)
        skiaBmp.installPixels(info, bytes.array(), widthPx * 4)
        skiaBmp.setImmutable()
        return skiaBmp.asComposeImageBitmap()
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

    actual fun pageAddMarkup(page: Long, kind: Int, quads: FloatArray, argb: Int): Boolean =
        PdfiumNative.nPageAddMarkup(page, kind, quads, argb)

    actual fun loadFont(document: Long, data: ByteArray): Long = PdfiumNative.nLoadFont(document, data)

    actual fun closeFont(font: Long) = PdfiumNative.nCloseFont(font)

    actual fun pageAddText(document: Long, page: Long, font: Long, text: String, fontSize: Float, argb: Int, invisible: Boolean, matrix: DoubleArray): Boolean =
        PdfiumNative.nPageAddText(document, page, font, text, fontSize, argb, invisible, matrix)

    actual fun endPage(page: Long): Boolean = PdfiumNative.nEndPage(page)

    actual fun saveDocument(document: Long, path: String): Boolean = PdfiumNative.nSaveDocument(document, path)
}
