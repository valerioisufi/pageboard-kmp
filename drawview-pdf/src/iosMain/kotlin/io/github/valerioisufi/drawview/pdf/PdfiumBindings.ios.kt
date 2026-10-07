package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_close_document
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_close_font
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_delete_page
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_end_page
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_get_page_count
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_get_page_size
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_get_page_text
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_get_page_text_layout
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_import_pages
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_load_font
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_new_document
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_new_page
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_open_document
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_page_add_bitmap
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_page_add_jpeg
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_page_add_markup
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_page_add_path
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_page_add_pdf_page
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_page_add_text
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_place_page
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_render_page_bitmap
import io.github.valerioisufi.drawview.pdf.cinterop.pdfium_save_document
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

    actual fun newDocument(): Long = pdfium_new_document()

    actual fun newPage(document: Long, index: Int, widthPt: Double, heightPt: Double): Long =
        pdfium_new_page(document, index, widthPt, heightPt)

    actual fun importPages(document: Long, source: Long, pageIndices: IntArray): Boolean {
        if (pageIndices.isEmpty()) return false
        return pageIndices.usePinned { pinned -> pdfium_import_pages(document, source, pinned.addressOf(0), pageIndices.size) != 0 }
    }

    actual fun placePage(document: Long, fromIndex: Int, toIndex: Int, matrix: DoubleArray): Long {
        if (matrix.size < 6) return 0L
        return matrix.usePinned { pinned -> pdfium_place_page(document, fromIndex, toIndex, pinned.addressOf(0)) }
    }

    actual fun deletePage(document: Long, index: Int): Boolean = pdfium_delete_page(document, index) != 0

    actual fun pageAddPath(page: Long, verbs: ByteArray, verbCount: Int, points: FloatArray, fillArgb: Int, strokeArgb: Int, strokeWidth: Float): Boolean {
        if (verbCount <= 0 || verbs.size < verbCount || points.isEmpty()) return false
        return verbs.usePinned { pinnedVerbs ->
            points.usePinned { pinnedPoints ->
                pdfium_page_add_path(
                    page, pinnedVerbs.addressOf(0).reinterpret(), verbCount, pinnedPoints.addressOf(0),
                    fillArgb.toUInt(), strokeArgb.toUInt(), strokeWidth
                ) != 0
            }
        }
    }

    actual fun pageAddJpeg(document: Long, page: Long, data: ByteArray, matrix: DoubleArray): Boolean {
        if (data.isEmpty() || matrix.size < 6) return false
        return data.usePinned { pinnedData ->
            matrix.usePinned { pinnedMatrix ->
                pdfium_page_add_jpeg(document, page, pinnedData.addressOf(0).reinterpret(), data.size, pinnedMatrix.addressOf(0)) != 0
            }
        }
    }

    actual fun pageAddBitmap(document: Long, page: Long, pixels: IntArray, width: Int, height: Int, matrix: DoubleArray): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height || matrix.size < 6) return false
        return pixels.usePinned { pinnedPixels ->
            matrix.usePinned { pinnedMatrix ->
                pdfium_page_add_bitmap(document, page, pinnedPixels.addressOf(0).reinterpret(), width, height, pinnedMatrix.addressOf(0)) != 0
            }
        }
    }

    actual fun pageAddPdfPage(document: Long, page: Long, source: Long, pageIndex: Int, matrix: DoubleArray): Boolean {
        if (matrix.size < 6) return false
        return matrix.usePinned { pinned -> pdfium_page_add_pdf_page(document, page, source, pageIndex, pinned.addressOf(0)) != 0 }
    }

    actual fun pageAddMarkup(page: Long, kind: Int, quads: FloatArray, argb: Int, appearance: String?): Boolean {
        if (quads.size < 8) return false
        return quads.usePinned { pinned -> pdfium_page_add_markup(page, kind, pinned.addressOf(0), quads.size / 8, argb.toUInt(), appearance) != 0 }
    }

    actual fun loadFont(document: Long, data: ByteArray): Long {
        if (data.isEmpty()) return 0L
        return data.usePinned { pinned -> pdfium_load_font(document, pinned.addressOf(0).reinterpret(), data.size) }
    }

    actual fun closeFont(font: Long) = pdfium_close_font(font)

    actual fun pageAddText(document: Long, page: Long, font: Long, text: String, fontSize: Float, argb: Int, invisible: Boolean, matrix: DoubleArray): Boolean {
        if (text.isEmpty() || matrix.size < 6) return false
        val units = UShortArray(text.length) { text[it].code.toUShort() }
        return units.usePinned { pinnedText ->
            matrix.usePinned { pinnedMatrix ->
                pdfium_page_add_text(
                    document, page, font, pinnedText.addressOf(0), text.length, fontSize, argb.toUInt(), if (invisible) 1 else 0,
                    pinnedMatrix.addressOf(0)
                ) != 0
            }
        }
    }

    actual fun endPage(page: Long): Boolean = pdfium_end_page(page) != 0

    actual fun saveDocument(document: Long, path: String, info: String?): Boolean = pdfium_save_document(document, path, info) != 0
}
