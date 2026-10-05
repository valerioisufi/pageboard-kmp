package io.github.valerioisufi.drawview.core.render.tile

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.DrawText

/**
 * Adapter for rendering PDF pages into tiles and inspecting PDF files.
 * Allows :drawview-core to decouple completely from PDFium or any specific PDF implementation.
 *
 * Implementations must be thread-safe: tiles are rasterized on background workers.
 */
interface PdfTileAdapter {
    /**
     * Renders page [pdfPageIndex] of the PDF stored at [resourcePath] into [canvas].
     *
     * @param pageRect destination bounds of the whole page on [canvas] (may exceed the canvas).
     * @param clipRect visible region of [canvas] that must be rendered.
     */
    fun renderPdfPage(
        canvas: Canvas,
        resourcePath: String,
        pdfPageIndex: Int,
        pageRect: Rect,
        clipRect: Rect
    )

    /**
     * Returns the size in millimeters of every page of the PDF stored at [resourcePath].
     */
    fun getPageSizesMm(resourcePath: String): List<Pair<Float, Float>>

    /**
     * Returns the text of page [pdfPageIndex] of the PDF stored at [resourcePath] (empty for a
     * scanned page), or `null` when it cannot be read.
     */
    fun getPageText(resourcePath: String, pdfPageIndex: Int): String? = null

    /**
     * Releases the cached documents.
     */
    fun close() {}
}

/**
 * Adapter for rasterizing text blocks (plain text, Markdown and LaTeX) into tiles.
 *
 * Implementations must be thread-safe: tiles are rasterized on background workers.
 */
interface TextTileAdapter {
    /**
     * Draws [text] on [canvas], mapping page millimeters to canvas pixels through [mmToCanvas].
     */
    fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D)
}

/**
 * Adapter for rasterizing LaTeX math formulas into ImageBitmaps.
 * Allows :drawview-core to decouple completely from MicroTeX or any specific LaTeX engine.
 */
interface LatexTileAdapter {
    /**
     * Renders a LaTeX formula to an ImageBitmap with transparent background.
     * Returns null if syntax error occurs or engine is uninitialized.
     */
    fun renderLatex(
        latex: String,
        fontSizePx: Float,
        color: Color
    ): ImageBitmap?
}
