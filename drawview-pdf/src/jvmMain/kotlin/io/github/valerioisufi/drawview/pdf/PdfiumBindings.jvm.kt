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
}
