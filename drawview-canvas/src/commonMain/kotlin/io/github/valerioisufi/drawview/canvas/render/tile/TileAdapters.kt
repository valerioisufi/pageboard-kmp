package io.github.valerioisufi.drawview.canvas.render.tile

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.canvas.geometry.toCompose
import io.github.valerioisufi.drawview.canvas.geometry.toModel
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapter for rendering PDF pages into tiles and inspecting PDF files.
 * Allows :drawview-canvas to decouple completely from PDFium or any specific PDF implementation.
 *
 * Implementations must be thread-safe: tiles are rasterized on background workers.
 */
public interface PdfTileAdapter {
    /**
     * Renders page [pdfPageIndex] of the PDF stored at [resourcePath] into [canvas], fitted in
     * [pageRect] (see [fitPdfPage]).
     *
     * @param pageRect destination bounds of the whole page on [canvas] (may exceed the canvas).
     * @param clipRect visible region of [canvas] that must be rendered.
     */
    public fun renderPdfPage(
        canvas: Canvas,
        resourcePath: String,
        pdfPageIndex: Int,
        pageRect: Rect,
        clipRect: Rect
    )

    /**
     * Returns the size in millimeters of every page of the PDF stored at [resourcePath].
     */
    public fun getPageSizesMm(resourcePath: String): List<Pair<Float, Float>>

    /**
     * Returns the text of page [pdfPageIndex] of the PDF stored at [resourcePath] (empty for a
     * scanned page), or `null` when it cannot be read.
     */
    public fun getPageText(resourcePath: String, pdfPageIndex: Int): String? = null

    /**
     * Returns the characters of page [pdfPageIndex] of the PDF stored at [resourcePath] with
     * their boxes (no character for a scanned page), or `null` when it cannot be read.
     */
    public fun getTextLayout(resourcePath: String, pdfPageIndex: Int): PdfTextLayout? = null

    /**
     * Releases the cached documents.
     */
    public fun close() {}
}

/**
 * Rectangle of [pageRect] where a PDF page of [pdfWidth] x [pdfHeight] is drawn (see the
 * [fitPdfPage] of the model).
 */
public fun fitPdfPage(pdfWidth: Double, pdfHeight: Double, pageRect: Rect): Rect =
    io.github.valerioisufi.drawview.model.geometry.fitPdfPage(pdfWidth, pdfHeight, pageRect.toModel()).toCompose()

/**
 * Adapter for rasterizing text blocks (Markdown with formulas) into tiles.
 *
 * Implementations must be thread-safe: tiles are rasterized on background workers.
 */
public interface TextTileAdapter {
    /**
     * Draws [text] on [canvas], mapping page millimeters to canvas pixels through [mmToCanvas], on
     * the paper of [paperColor] (ARGB, the background of its page: the backgrounds of its code are
     * opaque on it; [NO_PAPER] when there is none, and they are translucent).
     */
    public fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, paperColor: Int)

    /**
     * [text] as [drawText] lays it out on the paper of [paperColor] (its words, their style and
     * place, its formulas, the backgrounds of its code), e.g. to write it in a PDF; `null` when
     * the adapter cannot tell it.
     */
    public fun layout(text: DrawText, paperColor: Int): TextBlockLayout? = null

    /**
     * The size of [text] as [drawText] lays it out on its width; `null` when the adapter cannot
     * tell it.
     */
    public fun measure(text: DrawText): TextBlockSize? = null

    /** A line of a text of [fontSize] points; `null` when the adapter cannot tell it. */
    public fun lineMetrics(fontSize: Float): TextLineMetrics? = null

    /** Drops what the adapter keeps of the texts it laid out (e.g. its formulas became renderable). */
    public fun clearCaches() {}

    public companion object {
        /** No paper under a text (a color of no alpha): the backgrounds of its code are translucent. */
        public const val NO_PAPER: Int = 0
    }
}

/**
 * The size of a text block in millimeters: [height] of its lines laid out on its width, [width]
 * of its longest line.
 */
public class TextBlockSize(public val width: Float, public val height: Float)

/** A line of text in millimeters: its [height] and its [baseline] from its top. */
public class TextLineMetrics(public val height: Float, public val baseline: Float)

/**
 * A text block as a [TextTileAdapter] lays it out, in millimeters of the block (from its top-left
 * corner, before its rotation, which turns it around its middle, [height] being the height it is
 * drawn): its [words], its [formulas] and the [boxes] drawn under them (the backgrounds of its
 * code).
 */
public class TextBlockLayout(
    public val words: List<TextWord>,
    public val formulas: List<TextFormula>,
    public val height: Float,
    public val boxes: List<TextBox> = emptyList()
)

/**
 * A background of a text block: the rectangle from [left], [top] of [width] x [height], its corners
 * rounded by [radius], filled in [color] (ARGB, translucent as it is drawn).
 */
public class TextBox(
    public val left: Float,
    public val top: Float,
    public val width: Float,
    public val height: Float,
    public val radius: Float,
    public val color: Int
)

/**
 * A word of a text block with the spaces after it: its [text] goes from [left] for [width] on the
 * [baseline] of its line (from [lineTop] to [lineBottom]), in [face] [fontSize] high, of [color]
 * (ARGB), struck through or not.
 */
public class TextWord(
    public val text: String,
    public val face: TextFace,
    public val fontSize: Float,
    public val color: Int,
    public val left: Float,
    public val baseline: Float,
    public val width: Float,
    public val lineTop: Float,
    public val lineBottom: Float,
    public val strikethrough: Boolean = false
)

/**
 * A formula of a text block: [latex] drawn in [color] (ARGB) in the box ([left], [top], [width],
 * [height]).
 */
public class TextFormula(public val latex: String, public val color: Int, public val left: Float, public val top: Float, public val width: Float, public val height: Float)

/** A face of the fonts of the notes: the text and its code (monospace), regular or bold, upright or italic. */
public enum class TextFace {
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
public interface FormulaRenderer {
    /**
     * Whether formulas can be rendered now: the engine may have to be prepared first (see
     * [prepare]). The editors draw their texts again when it becomes `true`.
     */
    public val isReady: StateFlow<Boolean>

    /**
     * Prepares the engine (e.g. unpacks its fonts), the text of the formulas (`\text{}`) being
     * written in [textFonts] (TrueType files by face: [TextFace.REGULAR], [TextFace.BOLD],
     * [TextFace.ITALIC], [TextFace.BOLD_ITALIC]). Heavy: call it off the main thread. Safe to call
     * several times.
     */
    public suspend fun prepare(textFonts: Map<TextFace, ByteArray> = emptyMap())

    /**
     * [latex] as written in a text (with its delimiters, possibly unfinished while it is typed)
     * made ready for the engine; empty when nothing is left to draw.
     */
    public fun sanitize(latex: String): String

    /**
     * The formula [latex] (see [sanitize]) drawn [fontSizePx] high in [colorArgb], or `null`
     * when it is invalid or the engine is not ready.
     */
    public fun render(latex: String, fontSizePx: Float, colorArgb: Int): FormulaImage?
}

/** A formula drawn by a [FormulaRenderer]: its [bitmap] and the place of its baseline (0 top, 1 bottom). */
public class FormulaImage(public val bitmap: ImageBitmap, public val baselineRatio: Float)
