package io.github.valerioisufi.drawview.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileNotFoundException

/**
 * PDF document rendered by the Android framework [PdfRenderer] (as in the original app).
 * PdfRenderer allows a single open page at a time: every access is synchronized.
 */
internal class AndroidPdfDocument(file: File) : PdfDocument {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val sizes = mutableMapOf<Int, PdfSize>()
    private var closed = false

    override val pageCount: Int
        get() = synchronized(this) { if (closed) 0 else renderer.pageCount }

    override fun getPageSize(pageIndex: Int): PdfSize = synchronized(this) {
        if (closed || pageIndex !in 0 until renderer.pageCount) return PdfSize(0.0, 0.0)
        sizes.getOrPut(pageIndex) {
            renderer.openPage(pageIndex).use { page -> PdfSize(page.width.toDouble(), page.height.toDouble()) }
        }
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
    ): ImageBitmap? = synchronized(this) {
        if (closed || widthPx <= 0 || heightPx <= 0 || pageIndex !in 0 until renderer.pageCount) return null
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        renderer.openPage(pageIndex).use { page ->
            val matrix = Matrix().apply {
                postScale(sizeX.toFloat() / page.width, sizeY.toFloat() / page.height)
                postTranslate(startX.toFloat(), startY.toFloat())
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        bitmap.asImageBitmap()
    }

    override fun close() = synchronized(this) {
        if (closed) return
        closed = true
        renderer.close()
        descriptor.close()
    }
}

actual object PdfPlatform {
    actual fun openDocument(filePath: String, password: String?): PdfDocument {
        val file = File(filePath)
        if (!file.exists()) throw FileNotFoundException("PDF file not found: $filePath")
        return AndroidPdfDocument(file)
    }

    actual fun isAvailable(): Boolean = true
}
