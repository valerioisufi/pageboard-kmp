package io.github.valerioisufi.drawview.ui.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.render.tile.TextLine
import io.github.valerioisufi.drawview.core.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.core.util.SyncLock
import io.github.valerioisufi.drawview.core.util.syncLock
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Typographic points to millimeters. */
const val PT_TO_MM = 0.3527f

/**
 * Draws the text blocks of the pages (the `TextLayerRenderer` of the original app):
 * - plain and Markdown texts, laid out with the Compose text engine at the pixel size of the
 *   destination (word wrapping on the width of the block, as in the editor) with the inline
 *   `$...$` formulas rendered by MicroTeX;
 * - LaTeX blocks, rendered by MicroTeX and scaled on the width of the block.
 *
 * Thread-safe: the tiles are rasterized on background threads.
 *
 * @param fontFamilyResolver resolver of the composition (`LocalFontFamilyResolver`).
 */
class ComposeTextTileAdapter(
    fontFamilyResolver: FontFamily.Resolver,
    private val fontFamily: FontFamily = FontFamily.Default
) : TextTileAdapter {

    private val lock = SyncLock()

    // Density 1: 1.sp == 1px, every size below is expressed in pixels of the destination
    private val measurer = TextMeasurer(fontFamilyResolver, Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0)

    private val layouts = LruCache<LayoutKey, PreparedText>(MAX_LAYOUTS)
    private val formulaCache = LruCache<FormulaKey, LatexBitmap?>(MAX_FORMULAS)
    private val ascentCache = mutableMapOf<Int, Float>()

    private val imagePaint = Paint().apply {
        isAntiAlias = true
        filterQuality = FilterQuality.Medium
    }

    private data class LayoutKey(
        val textId: String,
        val version: Int,
        val content: String,
        val fontPxKey: Int,
        val widthPx: Int,
        val color: Int,
        val alignment: Int,
        val markdown: Boolean
    )

    private class PreparedText(
        val layout: TextLayoutResult,
        val formulas: List<MarkdownTextUtil.InlineFormula<ImageBitmap>>,
        val fontPx: Float
    )

    private data class FormulaKey(val latex: String, val color: Int, val sizeBucket: Int)

    private class LatexBitmap(val bitmap: ImageBitmap, val baselineRatio: Float)

    override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D) {
        val scale = mmToCanvas.scaleFactor
        if (scale <= 0f || text.width <= 0f) return
        syncLock(lock) {
            if (text.isLatex && LatexEngine.isNativeReady()) {
                if (drawLatexBlock(canvas, text, mmToCanvas, scale)) return
            }
            drawRichText(canvas, text, mmToCanvas, scale)
        }
    }

    override fun lines(text: DrawText): List<TextLine> {
        if (text.width <= 0f || text.text.isBlank()) return emptyList()
        if (text.isLatex && LatexEngine.isNativeReady()) return emptyList()
        // Laid out as drawText does, at a resolution where the rounding of the width is negligible
        val scale = LINES_PX_PER_MM
        val fontPx = text.fontSize * PT_TO_MM * scale
        val layout = syncLock(lock) { prepare(text, fontPx, (text.width * scale).toInt().coerceAtLeast(1)).layout }
        val shown = layout.layoutInput.text.text
        return (0 until layout.lineCount).mapNotNull { line ->
            val content = shown.substring(layout.getLineStart(line), layout.getLineEnd(line, visibleEnd = true))
                .replace(MarkdownTextUtil.OBJECT_REPLACEMENT, "")
                .replace(MarkdownTextUtil.WORD_JOINER, "")
            if (content.isBlank()) return@mapNotNull null
            val left = layout.getLineLeft(line)
            TextLine(content, left / scale, layout.getLineBaseline(line) / scale, (layout.getLineRight(line) - left) / scale, fontPx / scale)
        }
    }

    /**
     * Drops the cached layouts and formulas (e.g. when the LaTeX engine becomes available).
     */
    fun clearCaches() = syncLock(lock) {
        layouts.clear()
        formulaCache.clear()
    }

    // ==========================================================
    // Plain / Markdown text
    // ==========================================================

    private fun drawRichText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, scale: Float) {
        val fontPx = text.fontSize * PT_TO_MM * scale
        if (fontPx < 0.5f) return
        // Exact width used for the word wrapping, as in the editor
        val widthPx = (text.width * scale).toInt().coerceAtLeast(1)
        val key = LayoutKey(
            textId = text.id,
            version = text.version,
            content = text.text,
            fontPxKey = (fontPx * 100f).roundToInt(),
            widthPx = widthPx,
            color = text.color,
            alignment = text.alignment,
            markdown = text.isMarkdownEnabled
        )
        val prepared = layouts.getOrPut(key) { prepare(text, fontPx, widthPx) }

        val ratio = fontPx / prepared.fontPx
        val toCanvas = Matrix2D()
            .postScale(1f / scale * ratio, 1f / scale * ratio)
            .postRotate(text.rotation, text.width / 2f, text.height / 2f)
            .postTranslate(text.x, text.y)
            .postConcat(mmToCanvas)

        canvas.save()
        canvas.concat(toCanvas.toComposeMatrix())
        TextPainter.paint(canvas, prepared.layout)
        val rects = prepared.layout.placeholderRects
        for (formula in prepared.formulas) {
            val rect = rects.getOrNull(formula.placeholderIndex) ?: continue
            // The placeholder bottom is the baseline of the line
            val top = rect.bottom - formula.heightPx * formula.baselineRatio
            canvas.drawImage(formula.image, Offset(rect.left, top), imagePaint)
        }
        canvas.restore()
    }

    private fun prepare(text: DrawText, fontPx: Float, widthPx: Int): PreparedText {
        val color = Color(text.color)
        val baseStyle = TextStyle(
            color = color,
            fontSize = fontPx.sp,
            fontFamily = fontFamily,
            letterSpacing = 0.sp,
            textAlign = when (text.alignment) {
                1 -> TextAlign.Center
                2 -> TextAlign.End
                else -> TextAlign.Start
            }
        )
        val canvasText = MarkdownTextUtil.toCanvasText(
            text = text.text,
            isMarkdownEnabled = text.isMarkdownEnabled,
            baseColor = color,
            fontSizePx = fontPx,
            fontAscentPx = fontAscent(baseStyle, fontPx)
        ) { latex, sizePx ->
            renderInlineFormula(latex, sizePx, text.color)?.let {
                MarkdownTextUtil.RenderedFormula(it.bitmap, it.bitmap.width.toFloat(), it.bitmap.height.toFloat(), it.baselineRatio)
            }
        }
        val placeholders = canvasText.placeholders.map { spec ->
            AnnotatedString.Range(
                Placeholder(
                    width = spec.widthPx.sp,
                    height = spec.heightPx.sp,
                    placeholderVerticalAlign = if (spec.aboveBaseline) PlaceholderVerticalAlign.AboveBaseline else PlaceholderVerticalAlign.TextTop
                ),
                spec.start,
                spec.end
            )
        }
        val layout = measurer.measure(
            text = canvasText.text,
            style = baseStyle,
            softWrap = true,
            placeholders = placeholders,
            constraints = Constraints(maxWidth = widthPx),
            skipCache = true
        )
        return PreparedText(layout, canvasText.formulas, fontPx)
    }

    /** Ascent of the base font, used to reserve the descent of the inline formulas. */
    private fun fontAscent(style: TextStyle, fontPx: Float): Float {
        val bucket = fontPx.roundToInt().coerceAtLeast(1)
        return ascentCache.getOrPut(bucket) {
            measurer.measure(text = AnnotatedString("Hg"), style = style.copy(fontSize = bucket.toFloat().sp), skipCache = true)
                .firstBaseline * fontPx / bucket
        }
    }

    private fun renderInlineFormula(latex: String, sizePx: Float, color: Int): LatexBitmap? {
        if (!LatexEngine.isNativeReady()) return null
        val clean = LatexEngine.sanitizeLatexString(latex)
        if (clean.isBlank()) return null
        val bucket = sizePx.roundToInt().coerceAtLeast(1)
        return formulaCache.getOrPut(FormulaKey(clean, color, bucket)) {
            LatexEngine.renderFormula(clean, bucket.toFloat(), color, paddingPx = 0f)?.let {
                LatexBitmap(it.bitmap, it.baselineRatio)
            }
        }
    }

    // ==========================================================
    // LaTeX blocks
    // ==========================================================

    /**
     * Draws a LaTeX block scaled on the width of the block (the height follows the aspect ratio
     * of the formula). The bitmap resolution follows the zoom level.
     *
     * @return `false` when the formula cannot be rendered.
     */
    private fun drawLatexBlock(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, scale: Float): Boolean {
        val clean = LatexEngine.sanitizeLatexString(text.text)
        if (clean.isBlank()) return false

        // Reference size of the original app (fontSize * 3 px), multiplied by powers of two
        // until the bitmap is at least as wide as its destination
        val referencePx = text.fontSize * 3f
        val reference = latexBitmap(clean, text.color, referencePx, 1f)
        val targetWidthPx = text.width * scale
        val factor = if (reference.bitmap.width > 0) targetWidthPx / reference.bitmap.width else 1f
        val multiplier = if (factor <= 1f) 1f else 2f.pow(ceil(ln(factor) / ln(2f))).coerceAtMost(MAX_LATEX_MULTIPLIER)
        val maxMultiplier = MAX_LATEX_BITMAP_SIDE / max(reference.bitmap.width, reference.bitmap.height).coerceAtLeast(1).toFloat()
        val effective = min(multiplier, max(1f, maxMultiplier))
        val bitmap = if (effective > 1f) latexBitmap(clean, text.color, referencePx, effective) else reference

        val bmp = bitmap.bitmap
        if (bmp.width <= 0 || bmp.height <= 0) return false
        val bmpScale = text.width / bmp.width
        val heightMm = bmp.height * bmpScale
        // The block height follows the aspect ratio of the formula (as in the original app)
        if (abs(text.height - heightMm) > 0.01f) text.height = heightMm

        val toCanvas = Matrix2D()
            .postScale(bmpScale, bmpScale)
            .postRotate(text.rotation, text.width / 2f, heightMm / 2f)
            .postTranslate(text.x, text.y)
            .postConcat(mmToCanvas)
        canvas.save()
        canvas.concat(toCanvas.toComposeMatrix())
        canvas.drawImage(bmp, Offset.Zero, imagePaint)
        canvas.restore()
        return true
    }

    /**
     * Renders a LaTeX block at [multiplier] times the reference size, with the padding of the
     * original app (12 px at the reference size) scaled accordingly: every resolution has the
     * same aspect ratio. Invalid formulas are rendered as an error badge.
     */
    private fun latexBitmap(clean: String, color: Int, referencePx: Float, multiplier: Float): LatexBitmap {
        val sizePx = referencePx * multiplier
        val bucket = sizePx.roundToInt().coerceAtLeast(1)
        return formulaCache.getOrPut(FormulaKey("block:$clean", color, bucket)) {
            val result = LatexEngine.renderFormula(clean, sizePx, color, paddingPx = 12f * multiplier)
            if (result != null) {
                LatexBitmap(result.bitmap, result.baselineRatio)
            } else {
                LatexBitmap(LatexEngine.generateErrorBitmap(clean, sizePx), 0.75f)
            }
        } ?: LatexBitmap(LatexEngine.generateErrorBitmap(clean, sizePx), 0.75f)
    }

    companion object {
        private const val MAX_LAYOUTS = 256

        /** Resolution of the layouts of [lines]. */
        private const val LINES_PX_PER_MM = 20f
        private const val MAX_FORMULAS = 128
        private const val MAX_LATEX_MULTIPLIER = 16f
        private const val MAX_LATEX_BITMAP_SIDE = 4096f
    }
}

/**
 * Minimal LRU cache (not thread-safe: guarded by the caller).
 */
private class LruCache<K, V>(private val maxSize: Int) {
    private val map = LinkedHashMap<K, V>()

    fun getOrPut(key: K, create: () -> V): V {
        if (map.containsKey(key)) {
            @Suppress("UNCHECKED_CAST")
            val value = map.remove(key) as V
            map[key] = value
            return value
        }
        val value = create()
        map[key] = value
        while (map.size > maxSize) {
            val eldest = map.keys.first()
            map.remove(eldest)
        }
        return value
    }

    fun clear() = map.clear()
}
