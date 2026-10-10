package io.github.valerioisufi.drawview.core.render.tile

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.geometry.toCompose
import io.github.valerioisufi.drawview.core.geometry.toModel
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapter for rendering PDF pages into tiles and inspecting PDF files.
 * Allows :drawview-core to decouple completely from PDFium or any specific PDF implementation.
 *
 * Implementations must be thread-safe: tiles are rasterized on background workers.
 */
interface PdfTileAdapter {
    /**
     * Renders page [pdfPageIndex] of the PDF stored at [resourcePath] into [canvas], fitted in
     * [pageRect] (see [fitPdfPage]).
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
     * Returns the characters of page [pdfPageIndex] of the PDF stored at [resourcePath] with
     * their boxes (no character for a scanned page), or `null` when it cannot be read.
     */
    fun getTextLayout(resourcePath: String, pdfPageIndex: Int): PdfTextLayout? = null

    /**
     * Releases the cached documents.
     */
    fun close() {}
}

/**
 * Rectangle of [pageRect] where a PDF page of [pdfWidth] x [pdfHeight] is drawn (see the
 * [fitPdfPage] of the model).
 */
fun fitPdfPage(pdfWidth: Double, pdfHeight: Double, pageRect: Rect): Rect =
    io.github.valerioisufi.drawview.model.geometry.fitPdfPage(pdfWidth, pdfHeight, pageRect.toModel()).toCompose()

/**
 * Adapter for rasterizing text blocks (Markdown with formulas) into tiles.
 *
 * Implementations must be thread-safe: tiles are rasterized on background workers.
 */
interface TextTileAdapter {
    /**
     * Draws [text] on [canvas], mapping page millimeters to canvas pixels through [mmToCanvas].
     */
    fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D)

    /**
     * [text] as [drawText] lays it out (its words, their style and place, its formulas), e.g. to
     * write it in a PDF; `null` when the adapter cannot tell it.
     */
    fun layout(text: DrawText): TextBlockLayout? = null

    /**
     * The size of [text] as [drawText] lays it out on its width; `null` when the adapter cannot
     * tell it.
     */
    fun measure(text: DrawText): TextBlockSize? = null

    /** A line of a text of [fontSize] points; `null` when the adapter cannot tell it. */
    fun lineMetrics(fontSize: Float): TextLineMetrics? = null

    /** Drops what the adapter keeps of the texts it laid out (e.g. its formulas became renderable). */
    fun clearCaches() {}
}

/**
 * The size of a text block in millimeters: [height] of its lines laid out on its width, [width]
 * of its longest line.
 */
class TextBlockSize(val width: Float, val height: Float)

/** A line of text in millimeters: its [height] and its [baseline] from its top. */
class TextLineMetrics(val height: Float, val baseline: Float)

/**
 * A text block as a [TextTileAdapter] lays it out, in millimeters of the block (from its top-left
 * corner, before its rotation, which turns it around its middle, [height] being the height it is
 * drawn): its [words] and its [formulas].
 */
class TextBlockLayout(val words: List<TextWord>, val formulas: List<TextFormula>, val height: Float)

/**
 * A word of a text block with the spaces after it: its [text] goes from [left] for [width] on the
 * [baseline] of its line (from [lineTop] to [lineBottom]), in [face] [fontSize] high, of [color]
 * (ARGB), struck through or not.
 */
class TextWord(
    val text: String,
    val face: TextFace,
    val fontSize: Float,
    val color: Int,
    val left: Float,
    val baseline: Float,
    val width: Float,
    val lineTop: Float,
    val lineBottom: Float,
    val strikethrough: Boolean = false
)

/**
 * A formula of a text block: [latex] drawn in [color] (ARGB) in the box ([left], [top], [width],
 * [height]).
 */
class TextFormula(val latex: String, val color: Int, val left: Float, val top: Float, val width: Float, val height: Float)

/** A face of the fonts of the notes: the text and its code (monospace), regular or bold, upright or italic. */
enum class TextFace {
    REGULAR,
    ITALIC,
    BOLD,
    BOLD_ITALIC,
    MONO,
    MONO_BOLD
}

/**
 * Renders the LaTeX formulas of the texts (implemented by `drawview-latex`): the port through
 * which the editor, the thumbnails and the export draw formulas without depending on an engine.
 *
 * Implementations must be thread-safe: tiles are rasterized on background workers.
 */
interface FormulaRenderer {
    /**
     * Whether formulas can be rendered now: the engine may have to be prepared first (see
     * [prepare]). The editors draw their texts again when it becomes `true`.
     */
    val isReady: StateFlow<Boolean>

    /**
     * Prepares the engine (e.g. unpacks its fonts), the text of the formulas (`\text{}`) being
     * written in [textFonts] (TrueType files by face: [TextFace.REGULAR], [TextFace.BOLD],
     * [TextFace.ITALIC], [TextFace.BOLD_ITALIC]). Heavy: call it off the main thread. Safe to call
     * several times.
     */
    suspend fun prepare(textFonts: Map<TextFace, ByteArray> = emptyMap())

    /**
     * [latex] as written in a text (with its delimiters, possibly unfinished while it is typed)
     * made ready for the engine; empty when nothing is left to draw.
     */
    fun sanitize(latex: String): String

    /**
     * The formula [latex] (see [sanitize]) drawn [fontSizePx] high in [colorArgb], or `null`
     * when it is invalid or the engine is not ready.
     */
    fun render(latex: String, fontSizePx: Float, colorArgb: Int): FormulaImage?
}

/** A formula drawn by a [FormulaRenderer]: its [bitmap] and the place of its baseline (0 top, 1 bottom). */
class FormulaImage(val bitmap: ImageBitmap, val baselineRatio: Float)
