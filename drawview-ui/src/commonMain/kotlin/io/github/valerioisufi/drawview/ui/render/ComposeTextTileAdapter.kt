package io.github.valerioisufi.drawview.ui.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.canvas.geometry.toComposeMatrix
import io.github.valerioisufi.drawview.canvas.render.tile.FormulaRenderer
import io.github.valerioisufi.drawview.canvas.render.tile.TextBlockLayout
import io.github.valerioisufi.drawview.canvas.render.tile.TextBlockSize
import io.github.valerioisufi.drawview.canvas.render.tile.TextFace
import io.github.valerioisufi.drawview.canvas.render.tile.TextFormula
import io.github.valerioisufi.drawview.canvas.render.tile.TextLineMetrics
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.canvas.render.tile.TextWord
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.text.TextSizes.PT_TO_MM
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import io.github.valerioisufi.drawview.ui.theme.NotesFonts
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the text blocks of the pages (the `TextLayerRenderer` of the original app): Markdown laid
 * out with the Compose text engine at the pixel size of the destination (word wrapping on the
 * width of the block, as in the editor), its formulas rendered by MicroTeX in the lines or, alone
 * on their lines, centered on a line of their own.
 *
 * Thread-safe: the tiles are rasterized on background threads.
 *
 * @param fontFamilyResolver resolver of the composition (`LocalFontFamilyResolver`).
 * @param fonts the fonts of the texts ([rememberNotesFonts] in the editor, the same on every
 *   platform).
 * @param formulas renders the formulas (`null`: they are drawn as their source).
 */
public class ComposeTextTileAdapter(
    fontFamilyResolver: FontFamily.Resolver,
    private val fonts: NotesFonts = NotesFonts.System,
    formulas: FormulaRenderer? = null
) : TextTileAdapter {

    private val lock = SyncLock()

    // Density 1: 1.sp == 1px, every size below is expressed in pixels of the destination
    private val measurer = TextMeasurer(fontFamilyResolver, Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0)

    private val layouts = LruCache<LayoutKey, PreparedText>(MAX_LAYOUTS)
    private val formulaImages = FormulaImages(formulas)
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
        val alignment: TextAlignment
    )

    private class PreparedText(
        val layout: TextLayoutResult,
        val formulas: List<MarkdownTextUtil.InlineFormula<ImageBitmap>>,
        val fontPx: Float
    )

    override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D) {
        val scale = mmToCanvas.scaleFactor
        if (scale <= 0f || text.width <= 0f) return
        syncLock(lock) { drawRichText(canvas, text, mmToCanvas, scale) }
    }

    override fun measure(text: DrawText): TextBlockSize? {
        if (text.width <= 0f) return null
        return syncLock(lock) {
            val scale = LAYOUT_PX_PER_MM
            val prepared = prepare(text, text.fontSize * PT_TO_MM * scale, (text.width * scale).toInt().coerceAtLeast(1))
            val layout = prepared.layout
            // A formula alone on its line takes the line: its own width counts
            val blockLines = HashMap<Int, Float>()
            for (formula in prepared.formulas) {
                if (!formula.block) continue
                val rect = layout.placeholderRects.getOrNull(formula.placeholderIndex) ?: continue
                blockLines[layout.getLineForVerticalPosition(rect.center.y)] = formula.widthPx
            }
            var longest = 0f
            for (line in 0 until layout.lineCount) {
                longest = max(longest, blockLines[line] ?: (layout.getLineRight(line) - layout.getLineLeft(line)))
            }
            TextBlockSize(longest / scale, layout.size.height / scale)
        }
    }

    override fun lineMetrics(fontSize: Float): TextLineMetrics? {
        if (fontSize <= 0f) return null
        return syncLock(lock) {
            val fontPx = fontSize * PT_TO_MM * LAYOUT_PX_PER_MM
            val line = measurer.measure(AnnotatedString(" "), baseStyle(Color.Black, fontPx, TextAlign.Start), skipCache = true)
            TextLineMetrics(line.size.height / LAYOUT_PX_PER_MM, line.firstBaseline / LAYOUT_PX_PER_MM)
        }
    }

    /** The words and the formulas of a text, laid out as [drawRichText] does. */
    override fun layout(text: DrawText): TextBlockLayout? {
        if (text.width <= 0f || text.text.isBlank()) return null
        return syncLock(lock) {
            // At a resolution where the rounding of the width is negligible
            val scale = LAYOUT_PX_PER_MM
            val fontPx = text.fontSize * PT_TO_MM * scale
            val prepared = prepare(text, fontPx, (text.width * scale).toInt().coerceAtLeast(1))
            val layout = prepared.layout
            val shown = layout.layoutInput.text
            val chars = shown.text
            val words = mutableListOf<TextWord>()
            for (line in 0 until layout.lineCount) {
                val end = layout.getLineEnd(line)
                var start = layout.getLineStart(line)
                while (start < end) {
                    if (chars[start].isPlaceholder() || chars[start] == '\n') {
                        start++
                        continue
                    }
                    // The word and the spaces after it, in one style
                    val style = styleAt(shown, start, text.color, fontPx)
                    var stop = start
                    var spaces = false
                    while (stop < end && !chars[stop].isPlaceholder() && chars[stop] != '\n' && styleAt(shown, stop, text.color, fontPx) == style) {
                        val space = chars[stop].isWhitespace()
                        if (spaces && !space) break
                        spaces = spaces || space
                        stop++
                    }
                    val left = layout.getBoundingBox(start).left
                    val right = layout.getBoundingBox(stop - 1).right
                    words += TextWord(
                        text = chars.substring(start, stop),
                        face = style.face,
                        fontSize = style.sizePx / scale,
                        color = style.color,
                        left = left / scale,
                        baseline = layout.getLineBaseline(line) / scale,
                        width = (right - left) / scale,
                        lineTop = layout.getLineTop(line) / scale,
                        lineBottom = layout.getLineBottom(line) / scale,
                        strikethrough = style.strikethrough
                    )
                    start = stop
                }
            }
            val rects = layout.placeholderRects
            val formulas = prepared.formulas.mapNotNull { formula ->
                val box = formulaBox(formula, rects.getOrNull(formula.placeholderIndex) ?: return@mapNotNull null)
                TextFormula(formula.latex, text.color, box.left / scale, box.top / scale, box.width / scale, box.height / scale)
            }
            TextBlockLayout(words, formulas, text.height)
        }
    }

    /**
     * Where [formula] is drawn in its placeholder [rect]: its baseline on the bottom of the
     * placeholder of its body, or centered in the line of a block formula.
     */
    private fun formulaBox(formula: MarkdownTextUtil.InlineFormula<*>, rect: Rect?): Rect {
        if (rect == null) return Rect.Zero
        val left = if (formula.block) rect.left + (rect.width - formula.widthPx) / 2f else rect.left
        val top = if (formula.block) rect.top + (rect.height - formula.heightPx) / 2f else rect.bottom - formula.heightPx * formula.baselineRatio
        return Rect(left, top, left + formula.widthPx, top + formula.heightPx)
    }

    private data class WordStyle(val face: TextFace, val sizePx: Float, val color: Int, val strikethrough: Boolean)

    /** The style of the character [index] of [shown] (its spans over the base style of the block). */
    private fun styleAt(shown: AnnotatedString, index: Int, baseColor: Int, baseSizePx: Float): WordStyle {
        var bold = false
        var italic = false
        var mono = false
        var sizePx = baseSizePx
        var color = baseColor
        var strikethrough = false
        for (span in shown.spanStyles) {
            if (index < span.start || index >= span.end) continue
            val style = span.item
            style.fontWeight?.let { bold = it.weight >= FontWeight.SemiBold.weight }
            style.fontStyle?.let { italic = it == FontStyle.Italic }
            style.fontFamily?.let { mono = it == fonts.monospace || it == FontFamily.Monospace }
            if (style.fontSize.isSp) sizePx = style.fontSize.value
            if (style.color.isSpecified) color = style.color.toArgb()
            style.textDecoration?.let { strikethrough = it.contains(TextDecoration.LineThrough) }
        }
        val face = when {
            mono -> if (bold) TextFace.MONO_BOLD else TextFace.MONO
            bold -> if (italic) TextFace.BOLD_ITALIC else TextFace.BOLD
            else -> if (italic) TextFace.ITALIC else TextFace.REGULAR
        }
        return WordStyle(face, sizePx, color, strikethrough)
    }

    private fun Char.isPlaceholder() =
        this == MarkdownTextUtil.OBJECT_REPLACEMENT[0] || this == MarkdownTextUtil.WORD_JOINER[0]

    /**
     * Drops the cached layouts and formulas (e.g. when the LaTeX engine becomes available).
     */
    override fun clearCaches(): Unit = syncLock(lock) {
        layouts.clear()
        formulaImages.clear()
    }

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
            alignment = text.alignment
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
            val box = formulaBox(formula, rects.getOrNull(formula.placeholderIndex) ?: continue)
            val image = formula.image
            canvas.drawImageRect(
                image,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
                dstSize = IntSize(box.width.roundToInt().coerceAtLeast(1), box.height.roundToInt().coerceAtLeast(1)),
                paint = imagePaint
            )
        }
        canvas.restore()
    }

    private fun baseStyle(color: Color, fontPx: Float, align: TextAlign) = TextStyle(
        color = color,
        fontSize = fontPx.sp,
        fontFamily = fonts.text,
        letterSpacing = 0.sp,
        textAlign = align
    )

    private fun prepare(text: DrawText, fontPx: Float, widthPx: Int): PreparedText {
        val color = Color(text.color)
        val baseStyle = baseStyle(color, fontPx, text.alignment.toTextAlign())
        val canvasText = MarkdownTextUtil.toCanvasText(
            text = text.text,
            baseColor = color,
            fontSizePx = fontPx,
            fontAscentPx = fontAscent(baseStyle, fontPx),
            lineWidthPx = widthPx.toFloat(),
            monospace = fonts.monospace
        ) { latex, sizePx ->
            formulaImages.render(latex, sizePx, text.color)?.let {
                MarkdownTextUtil.RenderedFormula(it.bitmap, it.bitmap.width.toFloat(), it.bitmap.height.toFloat(), it.baselineRatio)
            }
        }
        val placeholders = canvasText.placeholders.map { spec ->
            AnnotatedString.Range(Placeholder(width = spec.widthPx.sp, height = spec.heightPx.sp, placeholderVerticalAlign = spec.verticalAlign), spec.start, spec.end)
        }
        val layout = measurer.measure(
            text = canvasText.text,
            style = baseStyle,
            softWrap = true,
            placeholders = placeholders,
            // The whole width of the block: the lines are aligned in it (with a maximum width only,
            // the layout would take the width of its longest line)
            constraints = Constraints.fixedWidth(widthPx),
            skipCache = true
        )
        return PreparedText(layout, canvasText.formulas, fontPx)
    }

    /** Ascent of the base font, used to reserve the descent of the formulas. */
    private fun fontAscent(style: TextStyle, fontPx: Float): Float {
        val bucket = fontPx.roundToInt().coerceAtLeast(1)
        return ascentCache.getOrPut(bucket) {
            measurer.measure(text = AnnotatedString("Hg"), style = style.copy(fontSize = bucket.toFloat().sp), skipCache = true)
                .firstBaseline * fontPx / bucket
        }
    }

    public companion object {
        private const val MAX_LAYOUTS = 256

        /** Resolution of the layouts of [layout] and [measure]. */
        private const val LAYOUT_PX_PER_MM = 20f
    }
}

/** The alignment of Compose for the lines of a text. */
internal fun TextAlignment.toTextAlign(): TextAlign = when (this) {
    TextAlignment.START -> TextAlign.Start
    TextAlignment.CENTER -> TextAlign.Center
    TextAlignment.END -> TextAlign.End
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
