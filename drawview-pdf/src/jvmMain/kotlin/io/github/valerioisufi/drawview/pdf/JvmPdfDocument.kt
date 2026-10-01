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

/**
 * PDF document rendered by PDFium (desktop JVM).
 */
internal class JvmPdfDocument(
    private var docHandle: Long
) : PdfDocument {

    override val pageCount: Int
        get() = if (docHandle != 0L) PdfiumNative.nGetPageCount(docHandle) else 0

    override fun getPageSize(pageIndex: Int): PdfSize {
        if (docHandle == 0L) return PdfSize(0.0, 0.0)
        val buf = DoubleArray(2)
        val ok = PdfiumNative.nGetPageSize(docHandle, pageIndex, buf)
        return if (ok) PdfSize(buf[0], buf[1]) else PdfSize(0.0, 0.0)
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
        if (docHandle == 0L || widthPx <= 0 || heightPx <= 0) return null

        // PDFium writes R, G, B, A bytes (FPDF_REVERSE_BYTE_ORDER)
        val pixels = IntArray(widthPx * heightPx)
        val ok = PdfiumNative.nRenderPageBitmap(
            docHandle, pageIndex,
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

    override fun close() {
        if (docHandle != 0L) {
            PdfiumNative.nCloseDocument(docHandle)
            docHandle = 0L
        }
    }

    @Suppress("removal")
    protected fun finalize() {
        close()
    }
}
