package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_close_document
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_get_page_count
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_get_page_size
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_get_page_text
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_get_page_text_layout
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_open_document
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_render_page_bitmap
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * PDFium C API (native/bridge/pdfium_bridge.h), linked statically; PDFium itself is the
 * PDFium.xcframework embedded by the app.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object PdfiumBindings {
    actual fun load(): Boolean = true

    actual fun openDocument(path: String, password: String?): Long = pdfium_open_document(path, password)

    actual fun pageCount(document: Long): Int = pdfium_get_page_count(document)

    actual fun pageSize(document: Long, pageIndex: Int): PdfSize? = memScoped {
        val width = alloc<DoubleVar>()
        val height = alloc<DoubleVar>()
        if (pdfium_get_page_size(document, pageIndex, width.ptr, height.ptr) != 0) {
            PdfSize(width.value, height.value)
        } else {
            null
        }
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
        val bytes = ByteArray(widthPx * heightPx * 4)
        val ok = bytes.usePinned { pinned ->
            pdfium_render_page_bitmap(
                document, pageIndex,
                startX, startY, sizeX, sizeY,
                widthPx, heightPx, rotate,
                pinned.addressOf(0).reinterpret()
            )
        }
        if (ok == 0) return null

        val info = ImageInfo(ColorInfo(ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB), widthPx, heightPx)
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        bitmap.installPixels(info, bytes, widthPx * 4)
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }

    actual fun pageText(document: Long, pageIndex: Int): String? {
        val length = pdfium_get_page_text(document, pageIndex, null, 0)
        if (length < 0) return null
        if (length == 0) return ""
        val units = UShortArray(length + 1)
        val written = units.usePinned { pinned -> pdfium_get_page_text(document, pageIndex, pinned.addressOf(0), length + 1) }
        if (written != length) return null
        return CharArray(length) { units[it].toInt().toChar() }.concatToString()
    }

    actual fun pageTextLayout(document: Long, pageIndex: Int): Pair<IntArray, FloatArray>? {
        val count = pdfium_get_page_text_layout(document, pageIndex, null, null, 0)
        if (count < 0) return null
        val chars = IntArray(count)
        val boxes = FloatArray(count * 4)
        if (count > 0) {
            val written = chars.usePinned { pinnedChars ->
                boxes.usePinned { pinnedBoxes ->
                    pdfium_get_page_text_layout(document, pageIndex, pinnedChars.addressOf(0), pinnedBoxes.addressOf(0), count)
                }
            }
            if (written != count) return null
        }
        return chars to boxes
    }

    actual fun closeDocument(document: Long) = pdfium_close_document(document)
}
