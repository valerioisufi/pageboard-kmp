package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.graphics.ImageBitmap

/**
 * PDFium is not compiled for the browser: importing PDFs is disabled.
 */
internal actual object PdfiumBindings {
    actual fun load(): Boolean = false

    actual fun openDocument(path: String, password: String?): Long = 0L

    actual fun pageCount(document: Long): Int = 0

    actual fun pageSize(document: Long, pageIndex: Int): PdfSize? = null

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
    ): ImageBitmap? = null

    actual fun closeDocument(document: Long) {}
}
