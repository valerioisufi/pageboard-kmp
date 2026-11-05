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
import io.github.valerioisufi.drawview.model.text.TextBackground
import io.github.valerioisufi.drawview.model.text.TextRoom
import io.github.valerioisufi.drawview.model.text.TextSizes.PT_TO_MM
import io.github.valerioisufi.drawview.model.text.code.BuiltInCodeHighlighter
import io.github.valerioisufi.drawview.model.text.code.CodeHighlighter
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import io.github.valerioisufi.drawview.ui.editor.PresentedCodeBlock
import io.github.valerioisufi.drawview.ui.editor.PresentedFormula
import io.github.valerioisufi.drawview.ui.editor.PresentedTable
import io.github.valerioisufi.drawview.ui.editor.Presentation
import io.github.valerioisufi.drawview.ui.editor.RichTextPresenter
import io.github.valerioisufi.drawview.ui.editor.codeBlockOrigin
import io.github.valerioisufi.drawview.ui.editor.formulaRect
import io.github.valerioisufi.drawview.ui.editor.tableFormulas
import io.github.valerioisufi.drawview.ui.editor.tableOrigin
import io.github.valerioisufi.drawview.ui.editor.struts
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
    // The room of the formulas and of the blocks of code in the lines, as the editor gives it
    private val struts by lazy { fonts.struts(measurer) }

    private val imagePaint = Paint().apply {
        isAntiAlias = true
        filterQuality = FilterQuality.Medium
    }
    private val chipPaint = Paint().apply { isAntiAlias = true }
    private val fillPaint = Paint().apply { isAntiAlias = true }

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
     * A text laid out: its [layout], its [formulas] and [codeBlocks] over their characters, the
     * backgrounds of its code in the line ([chips], in [chipColor]), the size each image of its
     * formulas was rendered at ([formulaSizes], to render them again at the size they are drawn).
     */
    private class PreparedText(
        val layout: TextLayoutResult,
        val formulas: List<PresentedFormula<ImageBitmap>>,
        val codeBlocks: List<PresentedCodeBlock>,
        val chips: List<CodeChip>,
        val chipColor: Color,
        val formulaSizes: Map<ImageBitmap, Float>,
        val tables: List<PresentedTable<ImageBitmap>>
    ) {
        /** The formulas of the text and of the cells of its tables, where they are drawn in its layout. */
        val allFormulas: List<Pair<PresentedFormula<ImageBitmap>, Rect>>
            get() = formulas.map { it to layout.formulaRect(it) } + layout.tableFormulas(tables)
    }


    override fun drawText(canvas: Canvas, text: DrawText, mmToCanvas: Matrix2D, paperColor: Int) {
        val scale = mmToCanvas.scaleFactor
        if (scale <= 0f || text.width <= 0f) return
        syncLock(lock) { drawRichText(canvas, inItsBox(text).first, mmToCanvas, scale, paperColor) }
    }

    /**
     * The size of [text] as [drawText] lays it out, kept for the next texts of the same content and
     * size: for a text following its width, its longest line in its room and the height of its box.
     */
    override fun measure(text: DrawText): TextBlockSize? {
        if (text.width <= 0f) return null
        return syncLock(lock) {
            val size = sizeOf(text)
            if (!text.autoWidth) size else TextBlockSize(size.width, sizeOf(inItsBox(text).first).height)
        }
    }

    private fun sizeOf(text: DrawText): TextBlockSize =
        sizes.getOrPut(SizeKey(text.text, text.fontSize, text.width, text.alignment)) { measureLayout(text) }

    /**
     * [text] as it is laid out and drawn, with where its box is along its lines: a text following
     * its width (see [TextRoom]) on the width of its box, at its place in the room, its lines wrapping
     * and its block formulas centered in it as in the editor; any other text as it is.
     */
    private fun inItsBox(text: DrawText): Pair<DrawText, Float> {
        if (!text.autoWidth) return text to 0f
        val width = TextRoom.boxWidth(sizeOf(text).width, text.width)
        val left = TextRoom.boxLeft(width, text.width, text.alignment)
        val turned = Matrix2D().postRotate(text.rotation).mapPoint(left, 0f)
        val box = DrawText(text.id).apply {
            copyFrom(text)
            autoWidth = false
            x = text.x + turned.x
            y = text.y + turned.y
            this.width = width
        }
        return box to left
    }

    private fun measureLayout(text: DrawText): TextBlockSize {
        val scale = LAYOUT_PX_PER_MM
        val prepared = prepare(text, text.fontSize * PT_TO_MM * scale, (text.width * scale).toInt().coerceAtLeast(1), TextTileAdapter.NO_PAPER)
        val layout = prepared.layout
        // A formula alone on its line and a block of code take the line: their own width counts
        val blockLines = HashMap<Int, Float>()
        for (formula in prepared.formulas) {
            if (formula.block) blockLines[layout.getLineForOffset(formula.offset)] = formula.widthPx
        }
        for (block in prepared.codeBlocks) {
            blockLines[layout.getLineForOffset(block.offset)] = block.leftPx + block.layout.naturalWidthPx
        }
        for (table in prepared.tables) {
            blockLines[layout.getLineForOffset(table.offset)] = table.layout.widthPx
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
            // In its box, from the corner of the text
            val (placed, boxLeft) = inItsBox(text)
            val left = boxLeft * scale
            val prepared = prepare(placed, fontPx, (placed.width * scale).toInt().coerceAtLeast(1), paperColor)
            val layout = prepared.layout
            val words = mutableListOf<TextWord>()
            val boxes = mutableListOf<TextBox>()
            // The fill first, under the rest
            text.background?.let { fill ->
                boxes += fillRect(text, layout.size.width.toFloat(), layout.size.height.toFloat(), left)
                    .toBox(TextBackground.radiusMm(text.fontSize) * scale, Color(fill), scale)
            }
            for (chip in prepared.chips) boxes += chip.rect.translate(left, 0f).toBox(chip.radius, prepared.chipColor, scale)
            addWords(layout, CharStyles(layout.layoutInput.text, text.color, fontPx, mono = false), scale, left, 0f, words)
            val formulas = prepared.allFormulas.map { (formula, rect) ->
                val box = rect.translate(left, 0f)
                TextFormula(formula.latex, text.color, box.left / scale, box.top / scale, box.width / scale, box.height / scale)
            }
            for (table in prepared.tables) {
                val origin = layout.tableOrigin(table) + Offset(left, 0f)
                for ((rect, color) in table.layout.backgrounds()) boxes += rect.translate(origin).toBox(0f, color, scale)
                for (cell in table.layout.cells) {
                    addWords(cell.layout, CharStyles(cell.layout.layoutInput.text, text.color, fontPx, mono = false), scale, origin.x + cell.left, origin.y + cell.top, words)
                }
            }
            for (codeBlock in prepared.codeBlocks) {
                val origin = layout.codeBlockOrigin(codeBlock)
                val rect = Rect(origin.x + left, origin.y, origin.x + left + codeBlock.layout.widthPx, origin.y + codeBlock.layout.heightPx)
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
     * Adds to [words] the words of [layout] (its struts and line breaks left out) in the styles of their
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
                    strikethrough = style.strikethrough,
                    underline = style.underline
                )
                start = stop
            }
        }
    }

    /**
     * The fill of [text] (see [TextBackground]) around its lines laid out [widthPx] x [heightPx]
     * from [left], in the pixels of the layout.
     */
    private fun fillRect(text: DrawText, widthPx: Float, heightPx: Float, left: Float): Rect {
        val outset = TextBackground.outsetMm(text.fontSize) * LAYOUT_PX_PER_MM
        return Rect(left - outset, -outset, left + widthPx + outset, heightPx + outset)
    }

    /** [this] rectangle in pixels as a background of [color] in millimeters, its corners of [radius] pixels. */
    private fun Rect.toBox(radius: Float, color: Color, scale: Float) =
        TextBox(left / scale, top / scale, width / scale, height / scale, radius / scale, color.toArgb())

    private data class WordStyle(val face: TextFace, val sizePx: Float, val color: Int, val strikethrough: Boolean, val underline: Boolean)

    /**
     * The style of every character of [shown]: its spans over the base style of the block ([baseColor],
     * [baseSizePx], in the monospace when [mono]), a later span over an earlier one. The spans are
     * applied once each: the spans of a text nest only a few deep, so the work is linear.
     */
    private inner class CharStyles(shown: AnnotatedString, baseColor: Int, baseSizePx: Float, mono: Boolean) {
        private val bold = BooleanArray(shown.length)
        private val italic = BooleanArray(shown.length)
        private val monospace = BooleanArray(shown.length) { mono }
        private val symbol = BooleanArray(shown.length)
        private val strikethrough = BooleanArray(shown.length)
        private val underline = BooleanArray(shown.length)
        private val sizePx = FloatArray(shown.length) { baseSizePx }
        private val color = IntArray(shown.length) { baseColor }

        init {
            for (span in shown.spanStyles) {
                val style = span.item
                val weight = style.fontWeight?.let { it.weight >= FontWeight.SemiBold.weight }
                val slant = style.fontStyle?.let { it == FontStyle.Italic }
                val family = style.fontFamily?.let { it == fonts.monospace || it == FontFamily.Monospace }
                val symbols = style.fontFamily?.let { it == fonts.symbols }
                val size = if (style.fontSize.isSp) style.fontSize.value else null
                val argb = if (style.color.isSpecified) style.color.toArgb() else null
                val strike = style.textDecoration?.let { it.contains(TextDecoration.LineThrough) }
                val under = style.textDecoration?.let { it.contains(TextDecoration.Underline) }
                for (i in span.start.coerceAtLeast(0) until span.end.coerceAtMost(shown.length)) {
                    if (weight != null) bold[i] = weight
                    if (slant != null) italic[i] = slant
                    if (family != null) monospace[i] = family
                    if (symbols != null) symbol[i] = symbols
                    if (size != null) sizePx[i] = size
                    if (argb != null) color[i] = argb
                    if (strike != null) strikethrough[i] = strike
                    if (under != null) underline[i] = under
                }
            }
        }

        fun at(index: Int): WordStyle {
            val face = when {
                symbol[index] -> TextFace.SYMBOLS
                monospace[index] -> if (bold[index]) TextFace.MONO_BOLD else TextFace.MONO
                bold[index] -> if (italic[index]) TextFace.BOLD_ITALIC else TextFace.BOLD
                else -> if (italic[index]) TextFace.ITALIC else TextFace.REGULAR
            }
            return WordStyle(face, sizePx[index], color[index], strikethrough[index], underline[index])
        }
    }

    private fun Char.isPlaceholder() = RichTextPresenter.isInvisible(this)

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
        text.background?.let { fill ->
            val box = fillRect(text, prepared.layout.size.width.toFloat(), prepared.layout.size.height.toFloat(), 0f)
            val radius = TextBackground.radiusMm(text.fontSize) * LAYOUT_PX_PER_MM
            fillPaint.color = Color(fill)
            canvas.drawRoundRect(box.left, box.top, box.right, box.bottom, radius, radius, fillPaint)
        }
        if (prepared.chips.isNotEmpty()) {
            chipPaint.color = prepared.chipColor
            for (chip in prepared.chips) {
                canvas.drawRoundRect(chip.rect.left, chip.rect.top, chip.rect.right, chip.rect.bottom, chip.radius, chip.radius, chipPaint)
            }
        }
        TextPainter.paint(canvas, prepared.layout)
        for (block in prepared.codeBlocks) {
            val origin = prepared.layout.codeBlockOrigin(block)
            block.layout.draw(canvas, origin.x, origin.y)
        }
        for (table in prepared.tables) {
            val origin = prepared.layout.tableOrigin(table)
            table.layout.draw(canvas, origin.x, origin.y)
        }
        for ((formula, box) in prepared.allFormulas) {
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
        val presentation = Presentation(
            fontSizePx = fontPx,
            color = color,
            struts = struts,
            lineWidthPx = widthPx.toFloat(),
            monospace = fonts.monospace,
            highlighter = code,
            layoutCodeBlock = { source -> CodeBlockLayout.layout(measurer, fonts.monospace, source, fontPx, color, this.code, paperColor, fonts.text) },
            measurePrefix = { prefix ->
                measurer.measure(AnnotatedString(prefix), baseStyle.copy(textAlign = TextAlign.Start), softWrap = false, skipCache = true)
                    .getHorizontalPosition(prefix.length, usePrimaryDirection = true)
            },
            measureText = { cell, cellWidth, alignment -> TableLayout.measureCell(measurer, cell, baseStyle, cellWidth, alignment) },
            paperColor = paperColor,
            symbols = fonts.symbols
        ) { latex, sizePx ->
            formulaImages.render(latex, sizePx, text.color)?.let {
                formulaSizes[it.bitmap] = sizePx
                MarkdownTextUtil.RenderedFormula(it.bitmap, it.bitmap.width.toFloat(), it.bitmap.height.toFloat(), it.baselineRatio)
            }
        }
        val shown = RichTextPresenter.present(text.text, presentation)
        val layout = measurer.measure(
            text = shown.text,
            style = baseStyle,
            softWrap = true,
            // The whole width of the block: the lines are aligned in it (with a maximum width only,
            // the layout would take the width of its longest line)
            constraints = Constraints.fixedWidth(widthPx),
            skipCache = true
        )
        return PreparedText(layout, shown.formulas, shown.codeBlocks, layout.codeChips(shown.inlineCode), CodeTheme.of(color, paperColor).background, formulaSizes, shown.tables)
    }

    public companion object {
        private const val MAX_LAYOUTS = 256

        /** Resolution of the layouts of [drawText], [layout] and [measure]. */
        internal const val LAYOUT_PX_PER_MM = 20f
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
