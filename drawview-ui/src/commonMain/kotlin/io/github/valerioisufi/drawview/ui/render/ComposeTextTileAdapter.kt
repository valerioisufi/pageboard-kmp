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
import io.github.valerioisufi.drawview.canvas.render.tile.TextBox
import io.github.valerioisufi.drawview.canvas.render.tile.TextFace
import io.github.valerioisufi.drawview.canvas.render.tile.TextFormula
import io.github.valerioisufi.drawview.canvas.render.tile.TextLineMetrics
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.canvas.render.tile.TextWord
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.text.TextSizes.PT_TO_MM
import io.github.valerioisufi.drawview.model.text.code.BuiltInCodeHighlighter
import io.github.valerioisufi.drawview.model.text.code.CodeHighlighter
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import io.github.valerioisufi.drawview.ui.theme.NotesFonts
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the text blocks of the pages (the `TextLayerRenderer` of the original app): Markdown laid
 * out with the Compose text engine once, at the resolution it is measured at, and drawn scaled at
 * every zoom (word wrapping on the width of the block; its lines, and so its height, the same at
 * every zoom), its formulas rendered by MicroTeX at the size they are drawn in the lines or, alone
 * on their lines, centered on a line of their own, its blocks of code on lines of their own (see
 * [CodeBlockLayout]) and its code in the line on a background.
 *
 * Thread-safe: the tiles are rasterized on background threads.
 *
 * @param fontFamilyResolver resolver of the composition (`LocalFontFamilyResolver`).
 * @param fonts the fonts of the texts ([rememberNotesFonts] in the editor, the same on every
 *   platform).
 * @param formulas renders the formulas (`null`: they are drawn as their source).
 * @param code tells the tokens of the blocks of code, for their colors (`null`: in one color).
 */
public class ComposeTextTileAdapter(
    fontFamilyResolver: FontFamily.Resolver,
    private val fonts: NotesFonts = NotesFonts.System,
    formulas: FormulaRenderer? = null,
    private val code: CodeHighlighter? = BuiltInCodeHighlighter
) : TextTileAdapter {

    private val lock = SyncLock()

    // Density 1: 1.sp == 1px, every size below is expressed in pixels of the destination
    private val measurer = TextMeasurer(fontFamilyResolver, Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 0)

    private val layouts = LruCache<LayoutKey, PreparedText>(MAX_LAYOUTS)
    private val sizes = LruCache<SizeKey, TextBlockSize>(MAX_LAYOUTS)
    private val formulaImages = FormulaImages(formulas)
    private val ascentCache = mutableMapOf<Int, Float>()

    private val imagePaint = Paint().apply {
        isAntiAlias = true
        filterQuality = FilterQuality.Medium
    }
    private val chipPaint = Paint().apply { isAntiAlias = true }

    private data class LayoutKey(
        val textId: String,
        val version: Int,
        val content: String,
        val fontPxKey: Int,
        val widthPx: Int,
        val color: Int,
        val alignment: TextAlignment,
        val paper: Int
    )

    /** What the size of a text depends on (see [measure]). */
    private data class SizeKey(val content: String, val fontSize: Float, val width: Float, val alignment: TextAlignment)

    /**
     * A text laid out: its [layout], its [formulas] and [codeBlocks] in its placeholders, the
     * backgrounds of its code in the line ([chips], in [chipColor]), the size each image of its
     * formulas was rendered at ([formulaSizes], to render them again at the size they are drawn).
     */
    private class PreparedText(
        val layout: TextLayoutResult,
        val formulas: List<MarkdownTextUtil.InlineFormula<ImageBitmap>>,
        val codeBlocks: List<MarkdownTextUtil.CanvasCodeBlock>,
        val chips: List<CodeChip>,
        val chipColor: Color,
        val formulaSizes: Map<ImageBitmap, Float>
    )


    override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, paperColor: Int) {
        val scale = mmToCanvas.scaleFactor
        if (scale <= 0f || text.width <= 0f) return
        syncLock(lock) { drawRichText(canvas, text, mmToCanvas, scale, paperColor) }
    }

    /** The size of [text] as [drawText] lays it out, kept for the next texts of the same content and size. */
    override fun measure(text: DrawText): TextBlockSize? {
        if (text.width <= 0f) return null
        val key = SizeKey(text.text, text.fontSize, text.width, text.alignment)
        return syncLock(lock) { sizes.getOrPut(key) { measureLayout(text) } }
    }

    private fun measureLayout(text: DrawText): TextBlockSize {
        val scale = LAYOUT_PX_PER_MM
        val prepared = prepare(text, text.fontSize * PT_TO_MM * scale, (text.width * scale).toInt().coerceAtLeast(1), TextTileAdapter.NO_PAPER)
        val layout = prepared.layout
        // A formula alone on its line and a block of code take the line: their own width counts
        val blockLines = HashMap<Int, Float>()
        for (formula in prepared.formulas) {
            if (!formula.block) continue
            val rect = layout.placeholderRects.getOrNull(formula.placeholderIndex) ?: continue
            blockLines[layout.getLineForVerticalPosition(rect.center.y)] = formula.widthPx
        }
        for (block in prepared.codeBlocks) {
            val rect = layout.placeholderRects.getOrNull(block.placeholderIndex) ?: continue
            blockLines[layout.getLineForVerticalPosition(rect.center.y)] = block.leftPx + block.layout.naturalWidthPx
        }
        var longest = 0f
        for (line in 0 until layout.lineCount) {
            longest = max(longest, blockLines[line] ?: (layout.getLineRight(line) - layout.getLineLeft(line)))
        }
        return TextBlockSize(longest / scale, layout.size.height / scale)
    }

    override fun lineMetrics(fontSize: Float): TextLineMetrics? {
        if (fontSize <= 0f) return null
        return syncLock(lock) {
            val fontPx = fontSize * PT_TO_MM * LAYOUT_PX_PER_MM
            val line = measurer.measure(AnnotatedString(" "), baseStyle(Color.Black, fontPx, TextAlign.Start), skipCache = true)
            TextLineMetrics(line.size.height / LAYOUT_PX_PER_MM, line.firstBaseline / LAYOUT_PX_PER_MM)
        }
    }

    /** The words, the formulas and the backgrounds of a text, laid out as [drawRichText] does. */
    override fun layout(text: DrawText, paperColor: Int): TextBlockLayout? {
        if (text.width <= 0f || text.text.isBlank()) return null
        return syncLock(lock) {
            // At a resolution where the rounding of the width is negligible
            val scale = LAYOUT_PX_PER_MM
            val fontPx = text.fontSize * PT_TO_MM * scale
            val prepared = prepare(text, fontPx, (text.width * scale).toInt().coerceAtLeast(1), paperColor)
            val layout = prepared.layout
            val words = mutableListOf<TextWord>()
            val boxes = mutableListOf<TextBox>()
            for (chip in prepared.chips) boxes += chip.rect.toBox(chip.radius, prepared.chipColor, scale)
            addWords(layout, CharStyles(layout.layoutInput.text, text.color, fontPx, mono = false), scale, 0f, 0f, words)
            val rects = layout.placeholderRects
            val formulas = prepared.formulas.mapNotNull { formula ->
                val box = formulaBox(formula, rects.getOrNull(formula.placeholderIndex) ?: return@mapNotNull null)
                TextFormula(formula.latex, text.color, box.left / scale, box.top / scale, box.width / scale, box.height / scale)
            }
            for (codeBlock in prepared.codeBlocks) {
                val rect = rects.getOrNull(codeBlock.placeholderIndex)?.translate(codeBlock.leftPx, 0f) ?: continue
                val block = codeBlock.layout
                boxes += Rect(rect.left, rect.top, rect.left + block.widthPx, rect.top + block.heightPx).toBox(block.radiusPx, block.theme.background, scale)
                val code = CharStyles(block.code.layoutInput.text, text.color, block.fontPx, mono = true)
                addWords(block.code, code, scale, rect.left + block.codeLeftPx, rect.top + block.codeTopPx, words)
                for (number in block.numbers) {
                    val styles = CharStyles(number.layout.layoutInput.text, block.theme.label.toArgb(), block.fontPx, mono = true)
                    addWords(number.layout, styles, scale, rect.left + number.left, rect.top + number.top, words)
                }
                val label = block.label ?: continue
                val labelStyles = CharStyles(label.layoutInput.text, block.theme.label.toArgb(), label.layoutInput.style.fontSize.value, mono = true)
                addWords(label, labelStyles, scale, rect.left + block.labelLeftPx, rect.top + block.labelTopPx, words)
            }
            TextBlockLayout(words, formulas, layout.size.height / scale, boxes)
        }
    }

    /**
     * Adds to [words] the words of [layout] (its placeholders left out) in the styles of their
     * characters, [left], [top] its origin, [scale] pixels per millimeter.
     */
    private fun addWords(layout: TextLayoutResult, styles: CharStyles, scale: Float, left: Float, top: Float, words: MutableList<TextWord>) {
        val chars = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount) {
            val end = layout.getLineEnd(line)
            var start = layout.getLineStart(line)
            while (start < end) {
                if (chars[start].isPlaceholder() || chars[start] == '\n') {
                    start++
                    continue
                }
                // The word and the spaces after it, in one style
                val style = styles.at(start)
                var stop = start
                var spaces = false
                while (stop < end && !chars[stop].isPlaceholder() && chars[stop] != '\n' && styles.at(stop) == style) {
                    val space = chars[stop].isWhitespace()
                    if (spaces && !space) break
                    spaces = spaces || space
                    stop++
                }
                val wordLeft = layout.getBoundingBox(start).left
                val wordRight = layout.getBoundingBox(stop - 1).right
                words += TextWord(
                    text = chars.substring(start, stop),
                    face = style.face,
                    fontSize = style.sizePx / scale,
                    color = style.color,
                    left = (left + wordLeft) / scale,
                    baseline = (top + layout.getLineBaseline(line)) / scale,
                    width = (wordRight - wordLeft) / scale,
                    lineTop = (top + layout.getLineTop(line)) / scale,
                    lineBottom = (top + layout.getLineBottom(line)) / scale,
                    strikethrough = style.strikethrough
                )
                start = stop
            }
        }
    }

    /** [this] rectangle in pixels as a background of [color] in millimeters, its corners of [radius] pixels. */
    private fun Rect.toBox(radius: Float, color: Color, scale: Float) =
        TextBox(left / scale, top / scale, width / scale, height / scale, radius / scale, color.toArgb())

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

    /**
     * The style of every character of [shown]: its spans over the base style of the block ([baseColor],
     * [baseSizePx], in the monospace when [mono]), a later span over an earlier one. The spans are
     * applied once each: the spans of a text nest only a few deep, so the work is linear.
     */
    private inner class CharStyles(shown: AnnotatedString, baseColor: Int, baseSizePx: Float, mono: Boolean) {
        private val bold = BooleanArray(shown.length)
        private val italic = BooleanArray(shown.length)
        private val monospace = BooleanArray(shown.length) { mono }
        private val strikethrough = BooleanArray(shown.length)
        private val sizePx = FloatArray(shown.length) { baseSizePx }
        private val color = IntArray(shown.length) { baseColor }

        init {
            for (span in shown.spanStyles) {
                val style = span.item
                val weight = style.fontWeight?.let { it.weight >= FontWeight.SemiBold.weight }
                val slant = style.fontStyle?.let { it == FontStyle.Italic }
                val family = style.fontFamily?.let { it == fonts.monospace || it == FontFamily.Monospace }
                val size = if (style.fontSize.isSp) style.fontSize.value else null
                val argb = if (style.color.isSpecified) style.color.toArgb() else null
                val strike = style.textDecoration?.let { it.contains(TextDecoration.LineThrough) }
                for (i in span.start.coerceAtLeast(0) until span.end.coerceAtMost(shown.length)) {
                    if (weight != null) bold[i] = weight
                    if (slant != null) italic[i] = slant
                    if (family != null) monospace[i] = family
                    if (size != null) sizePx[i] = size
                    if (argb != null) color[i] = argb
                    if (strike != null) strikethrough[i] = strike
                }
            }
        }

        fun at(index: Int): WordStyle {
            val face = when {
                monospace[index] -> if (bold[index]) TextFace.MONO_BOLD else TextFace.MONO
                bold[index] -> if (italic[index]) TextFace.BOLD_ITALIC else TextFace.BOLD
                else -> if (italic[index]) TextFace.ITALIC else TextFace.REGULAR
            }
            return WordStyle(face, sizePx[index], color[index], strikethrough[index])
        }
    }

    private fun Char.isPlaceholder() =
        this == MarkdownTextUtil.OBJECT_REPLACEMENT[0] || this == MarkdownTextUtil.WORD_JOINER[0]

    /**
     * Drops the cached layouts and formulas (e.g. when the LaTeX engine becomes available).
     */
    override fun clearCaches(): Unit = syncLock(lock) {
        layouts.clear()
        sizes.clear()
        formulaImages.clear()
    }

    private fun drawRichText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, scale: Float, paperColor: Int) {
        if (text.fontSize * PT_TO_MM * scale < 0.5f) return
        // Laid out as it is measured, then scaled: at the pixels of each zoom the lines would
        // wrap and round their heights differently, out of the box of the text
        val fontPx = text.fontSize * PT_TO_MM * LAYOUT_PX_PER_MM
        val widthPx = (text.width * LAYOUT_PX_PER_MM).toInt().coerceAtLeast(1)
        val key = LayoutKey(
            textId = text.id,
            version = text.version,
            content = text.text,
            fontPxKey = (fontPx * 100f).roundToInt(),
            widthPx = widthPx,
            color = text.color,
            alignment = text.alignment,
            paper = paperColor
        )
        val prepared = layouts.getOrPut(key) { prepare(text, fontPx, widthPx, paperColor) }

        val toCanvas = Matrix2D()
            .postScale(1f / LAYOUT_PX_PER_MM, 1f / LAYOUT_PX_PER_MM)
            .postRotate(text.rotation)
            .postTranslate(text.x, text.y)
            .postConcat(mmToCanvas)

        canvas.save()
        canvas.concat(toCanvas.toComposeMatrix())
        if (prepared.chips.isNotEmpty()) {
            chipPaint.color = prepared.chipColor
            for (chip in prepared.chips) {
                canvas.drawRoundRect(chip.rect.left, chip.rect.top, chip.rect.right, chip.rect.bottom, chip.radius, chip.radius, chipPaint)
            }
        }
        TextPainter.paint(canvas, prepared.layout)
        val rects = prepared.layout.placeholderRects
        for (block in prepared.codeBlocks) {
            val rect = rects.getOrNull(block.placeholderIndex) ?: continue
            block.layout.draw(canvas, rect.left + block.leftPx, rect.top)
        }
        for (formula in prepared.formulas) {
            val box = formulaBox(formula, rects.getOrNull(formula.placeholderIndex) ?: continue)
            // Rendered again at the size it is drawn, sharp at every zoom
            val image = prepared.formulaSizes[formula.image]
                ?.let { formulaImages.render(formula.latex, it * scale / LAYOUT_PX_PER_MM, text.color)?.bitmap }
                ?: formula.image
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

    private fun prepare(text: DrawText, fontPx: Float, widthPx: Int, paperColor: Int): PreparedText {
        val color = Color(text.color)
        val baseStyle = baseStyle(color, fontPx, text.alignment.toTextAlign())
        val formulaSizes = HashMap<ImageBitmap, Float>()
        val canvasText = MarkdownTextUtil.toCanvasText(
            text = text.text,
            baseColor = color,
            fontSizePx = fontPx,
            fontAscentPx = fontAscent(baseStyle, fontPx),
            lineWidthPx = widthPx.toFloat(),
            monospace = fonts.monospace,
            layoutCodeBlock = { source -> CodeBlockLayout.layout(measurer, fonts.monospace, source, fontPx, color, this.code, paperColor) },
            listPrefixWidth = { prefix ->
                measurer.measure(AnnotatedString(prefix), baseStyle.copy(textAlign = TextAlign.Start), softWrap = false, skipCache = true)
                    .getHorizontalPosition(prefix.length, usePrimaryDirection = true)
            }
        ) { latex, sizePx ->
            formulaImages.render(latex, sizePx, text.color)?.let {
                formulaSizes[it.bitmap] = sizePx
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
        return PreparedText(layout, canvasText.formulas, canvasText.codeBlocks, layout.codeChips(canvasText.inlineCode), CodeTheme.of(color, paperColor).background, formulaSizes)
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

        /** Resolution of the layouts of [drawText], [layout] and [measure]. */
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
internal class LruCache<K, V>(private val maxSize: Int) {
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
