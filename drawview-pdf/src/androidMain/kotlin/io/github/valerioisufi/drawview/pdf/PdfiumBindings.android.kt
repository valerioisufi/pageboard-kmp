package io.github.valerioisufi.drawview.pdf

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

    actual fun closeDocument(document: Long) = PdfiumNative.nCloseDocument(document)
}
