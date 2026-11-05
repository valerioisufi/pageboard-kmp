package io.github.valerioisufi.drawview.ui.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.model.text.code.CodeHighlighter
import io.github.valerioisufi.drawview.model.text.code.CodeToken
import io.github.valerioisufi.drawview.model.text.code.CodeTokenKind
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
import kotlin.math.max

/**
 * The colors of the code of a text of [text] color on its [paper]: its tokens, its background, the
 * label of its language. A dark text has the palette made for light paper, a light text the one
 * made for dark paper; the background is the color of the text, faint, over the paper (opaque: the
 * lines of the paper do not show through), or translucent when the paper is not known.
 */
internal class CodeTheme private constructor(val text: Color, paper: Color?, private val tokens: Map<CodeTokenKind, Color>) {

    /** The background of the code, in the line and in the blocks. */
    val background: Color = text.copy(alpha = text.alpha * BACKGROUND_ALPHA).let { faint -> paper?.let { faint.compositeOver(it) } ?: faint }

    /** The name of the language of a block. */
    val label: Color = text.copy(alpha = text.alpha * LABEL_ALPHA)

    /** The color of a token of [kind]. */
    fun color(kind: CodeTokenKind): Color = tokens[kind] ?: text

    companion object {
        private const val BACKGROUND_ALPHA = 0.07f
        private const val LABEL_ALPHA = 0.55f

        // The colors of GitHub, at least 4.5:1 on their paper
        private val LIGHT_PAPER = mapOf(
            CodeTokenKind.KEYWORD to Color(0xFFCF222E),
            CodeTokenKind.TYPE to Color(0xFF953800),
            CodeTokenKind.LITERAL to Color(0xFF0550AE),
            CodeTokenKind.STRING to Color(0xFF0A3069),
            CodeTokenKind.NUMBER to Color(0xFF0550AE),
            CodeTokenKind.COMMENT to Color(0xFF6E7781),
            CodeTokenKind.ANNOTATION to Color(0xFF116329),
            CodeTokenKind.FUNCTION to Color(0xFF8250DF),
            CodeTokenKind.PROPERTY to Color(0xFF0550AE)
        )
        private val DARK_PAPER = mapOf(
            CodeTokenKind.KEYWORD to Color(0xFFFF7B72),
            CodeTokenKind.TYPE to Color(0xFFFFA657),
            CodeTokenKind.LITERAL to Color(0xFF79C0FF),
            CodeTokenKind.STRING to Color(0xFFA5D6FF),
            CodeTokenKind.NUMBER to Color(0xFF79C0FF),
            CodeTokenKind.COMMENT to Color(0xFF8B949E),
            CodeTokenKind.ANNOTATION to Color(0xFF7EE787),
            CodeTokenKind.FUNCTION to Color(0xFFD2A8FF),
            CodeTokenKind.PROPERTY to Color(0xFF79C0FF)
        )

        /**
         * The colors of the code of a text drawn in [text] on the paper of [paperColor] (ARGB, or
         * no paper: a color of no alpha).
         */
        fun of(text: Color, paperColor: Int = TextTileAdapter.NO_PAPER): CodeTheme {
            val paper = Color(paperColor).takeIf { it.alpha > 0f }
            return CodeTheme(text, paper, if (text.luminance() > 0.5f) DARK_PAPER else LIGHT_PAPER)
        }
    }
}

/**
 * What a block of code is laid out from: its [code] (the lines between its fences), its
 * [language], the [indent] spaces of its fence taken off its lines, whether it shows the numbers of
 * its lines ([numbered]), and the [widthPx] it takes in its text.
 */
public data class CodeBlockSource(
    val code: String,
    val language: String,
    val indent: Int,
    val numbered: Boolean,
    val widthPx: Float
)

/**
 * A block of code laid out on the width of its text ([widthPx] x [heightPx] in pixels of the
 * destination): its code in the monospace of the notes and in the colors of its language, its
 * lines wrapped on the width less the padding, a wrapped line continuing under the start of its
 * code; on its background with rounded corners, the name of its language in its top-right corner,
 * the numbers of its lines in a column on its left when it asks for them. The canvas and the
 * editor draw a block with the same layout.
 *
 * @property naturalWidthPx the width of its longest line with the padding, unwrapped: what a text
 *   of automatic width grows to.
 */
public class CodeBlockLayout internal constructor(
    internal val code: TextLayoutResult,
    private val shownOffsets: IntArray,
    internal val label: TextLayoutResult?,
    public val widthPx: Float,
    public val heightPx: Float,
    public val naturalWidthPx: Float,
    internal val paddingPx: Float,
    internal val codeTopPx: Float,
    internal val labelTopPx: Float,
    internal val radiusPx: Float,
    internal val theme: CodeTheme,
    internal val fontPx: Float,
    internal val codeLeftPx: Float,
    internal val numbers: List<LineNumber>,
    internal val advancePx: Float = 0f
) {
    private val backgroundPaint = Paint().apply {
        isAntiAlias = true
        color = theme.background
    }

    /** Where the label is drawn from the left of the block. */
    internal val labelLeftPx: Float get() = widthPx - paddingPx - (label?.size?.width ?: 0)

    /**
     * The offset in the code of the character at [point] of the block (from its top-left corner):
     * where a tap on the block puts the caret.
     */
    internal fun codeOffsetAt(point: Offset): Int {
        val local = Offset(point.x - codeLeftPx, point.y - codeTopPx)
        val shown = code.getOffsetForPosition(local)
        // At the end of a line, before its line break: the next line starts at the same offset drawn
        val atLineEnd = shown == code.getLineEnd(code.getLineForVerticalPosition(local.y))
        // The offsets drawn of the code only grow: the first one at or after, the last one at or before
        var low = 0
        var high = shownOffsets.lastIndex
        while (low < high) {
            val middle = (low + high + if (atLineEnd) 0 else 1) / 2
            if (atLineEnd) {
                if (shownOffsets[middle] >= shown) high = middle else low = middle + 1
            } else {
                if (shownOffsets[middle] <= shown) low = middle else high = middle - 1
            }
        }
        return low
    }

    /** The offset drawn of the offset [offset] of the code. */
    private fun shown(offset: Int): Int = shownOffsets[offset.coerceIn(0, shownOffsets.lastIndex)]

    /** The caret before the character [offset] of the code, in the pixels of the block (from its top-left corner). */
    internal fun cursorRect(offset: Int): Rect = code.getCursorRect(shown(offset)).translate(codeLeftPx, codeTopPx)

    /** The selection of the code from [start] to [end], in the pixels of the block. */
    internal fun selectionPath(start: Int, end: Int): Path = code.getPathForRange(shown(start), shown(end)).apply { translate(Offset(codeLeftPx, codeTopPx)) }

    /** The number of the lines of the code laid out (its wrapped lines counted). */
    internal val lineCount: Int get() = code.lineCount

    /** The line of the code laid out the caret at [offset] of the code is on. */
    internal fun lineOf(offset: Int): Int = code.getLineForOffset(shown(offset))

    /** The offset of the code on its line [line] at [x] of the block: where a move up or down of the caret goes. */
    internal fun offsetOnLine(line: Int, x: Float): Int {
        val at = line.coerceIn(0, code.lineCount - 1)
        return codeOffsetAt(Offset(x, codeTopPx + (code.getLineTop(at) + code.getLineBottom(at)) / 2f))
    }

    /** Where the label is, in the pixels of the block: `null` without a language. */
    internal val labelRect: Rect? get() = label?.let { Rect(labelLeftPx, labelTopPx, labelLeftPx + it.size.width, labelTopPx + it.size.height) }

    /** Draws the block on [canvas], its top-left corner at [left], [top]. */
    internal fun draw(canvas: Canvas, left: Float, top: Float) {
        drawChrome(canvas, left, top)
        canvas.save()
        canvas.translate(left + codeLeftPx, top + codeTopPx)
        TextPainter.paint(canvas, code)
        canvas.restore()
    }

    /**
     * Draws the block without its code (its background, the numbers of its lines, its label) on
     * [canvas], its top-left corner at [left], [top]: around its code being written in the editor.
     */
    internal fun drawChrome(canvas: Canvas, left: Float, top: Float) {
        canvas.drawRoundRect(left, top, left + widthPx, top + heightPx, radiusPx, radiusPx, backgroundPaint)
        for (number in numbers) {
            canvas.save()
            canvas.translate(left + number.left, top + number.top)
            TextPainter.paint(canvas, number.layout)
            canvas.restore()
        }
        val label = label ?: return
        canvas.save()
        canvas.translate(left + labelLeftPx, top + labelTopPx)
        TextPainter.paint(canvas, label)
        canvas.restore()
    }

    /** The number of a line of the code, laid out, its top-left corner at [left], [top] of the block. */
    internal class LineNumber(val layout: TextLayoutResult, val left: Float, val top: Float)

    internal companion object {
        /** The size of the code of a block, and of the name of its language, relative to the text. */
        const val CODE_SCALE = 0.9f
        private const val LABEL_SCALE = 0.7f

        /** The padding on the sides and above and below, and the corners, relative to the code. */
        private const val PADDING_X = 0.6f
        private const val PADDING_Y = 0.4f
        private const val RADIUS = 0.3f

        /** The columns of a tab. */
        private const val TAB_WIDTH = 4

        /** The longest name of a language shown. */
        private const val MAX_LABEL = 24

        /** The corners of the background of a block, or of its source, in a text of [textFontPx]. */
        fun cornerRadius(textFontPx: Float): Float = textFontPx * CODE_SCALE * RADIUS

        /**
         * Lays out the block of [source] in a text of [textFontPx] and [color], on the paper of
         * [paperColor], its tokens told by [highlighter] (`null`: in one color). Its code is laid
         * out in paragraphs of the text in [textFont], as when it is written in the editor (Compose
         * gives the first line of a paragraph the room of the font of the paragraph above it).
         */
        fun layout(
            measurer: TextMeasurer,
            monospace: FontFamily,
            source: CodeBlockSource,
            textFontPx: Float,
            color: Color,
            highlighter: CodeHighlighter?,
            paperColor: Int,
            textFont: FontFamily = FontFamily.Default
        ): CodeBlockLayout {
            val code = source.code
            val language = source.language
            val widthPx = source.widthPx
            val fontPx = textFontPx * CODE_SCALE
            val theme = CodeTheme.of(color, paperColor)
            val style = TextStyle(color = color, fontSize = fontPx.sp, fontFamily = monospace, letterSpacing = 0.sp, textAlign = TextAlign.Start)
            val advance = advance(measurer, style)
            val shownCode = shownCode(code, source.indent, advance, tokens(highlighter, code, language), theme)
            val shown = shownCode.text
            val padding = fontPx * PADDING_X
            // The column of the numbers: as many digits as the last line has, then one free
            val lineCount = shownCode.lineStarts.size
            val gutter = if (source.numbered) (lineCount.toString().length + 1) * advance else 0f
            val layout = measurer.measure(
                text = buildAnnotatedString {
                    append(shown)
                    addStyle(SpanStyle(fontFamily = monospace, fontSize = fontPx.sp), 0, length)
                },
                style = style.copy(fontFamily = textFont, fontSize = textFontPx.sp),
                softWrap = true,
                constraints = Constraints.fixedWidth((widthPx - 2f * padding - gutter).toInt().coerceAtLeast(1)),
                skipCache = true
            )
            val name = if (language.length > MAX_LABEL) language.take(MAX_LABEL) + "…" else language
            val label = name.takeIf { it.isNotEmpty() }?.let {
                measurer.measure(AnnotatedString(it), style.copy(color = theme.label, fontSize = (fontPx * LABEL_SCALE).sp), softWrap = false, skipCache = true)
            }
            val paddingY = fontPx * PADDING_Y
            val labelTop = paddingY * 0.5f
            val codeTop = if (label != null) labelTop + label.size.height + paddingY * 0.25f else paddingY
            val numbers = if (source.numbered) {
                lineNumbers(measurer, layout, shownCode.lineStarts, style.copy(color = theme.label), padding + gutter - advance, codeTop)
            } else {
                emptyList()
            }
            return CodeBlockLayout(
                code = layout,
                shownOffsets = shownCode.shownOffsets,
                label = label,
                widthPx = widthPx,
                heightPx = codeTop + layout.size.height + paddingY,
                naturalWidthPx = max(layout.multiParagraph.intrinsics.maxIntrinsicWidth + gutter, label?.size?.width?.toFloat() ?: 0f) + 2f * padding,
                paddingPx = padding,
                codeTopPx = codeTop,
                labelTopPx = labelTop,
                radiusPx = fontPx * RADIUS,
                theme = theme,
                fontPx = fontPx,
                codeLeftPx = padding + gutter,
                numbers = numbers,
                advancePx = advance
            )
        }

        /**
         * The numbers of the lines of the code laid out in [layout] (its paragraphs starting at
         * [lineStarts]), right aligned on [right], each on the first line of its paragraph (a
         * wrapped line has none), its baseline on the one of the code, the code [codeTop] below the
         * top of the block.
         */
        private fun lineNumbers(
            measurer: TextMeasurer,
            layout: TextLayoutResult,
            lineStarts: IntArray,
            style: TextStyle,
            right: Float,
            codeTop: Float
        ): List<LineNumber> {
            val numbers = ArrayList<LineNumber>(lineStarts.size)
            // The lines of the layout in order: a line starting where the next paragraph starts is its first one
            var paragraph = 0
            for (line in 0 until layout.lineCount) {
                if (line > 0) {
                    if (paragraph + 1 >= lineStarts.size || layout.getLineStart(line) != lineStarts[paragraph + 1]) continue
                    paragraph++
                }
                val number = measurer.measure(AnnotatedString((paragraph + 1).toString()), style, softWrap = false, skipCache = true)
                numbers += LineNumber(number, right - number.size.width, codeTop + layout.getLineBaseline(line) - number.firstBaseline)
            }
            return numbers
        }

        /** The width of a character of the monospace of [style]. */
        private fun advance(measurer: TextMeasurer, style: TextStyle): Float {
            val sample = measurer.measure(AnnotatedString("0".repeat(10)), style, softWrap = false, skipCache = true)
            return sample.getLineRight(0) / 10f
        }

        /**
         * The tokens of [code], those of an engine kept only sorted, apart and in the code (an
         * engine failing draws the code in one color).
         */
        fun tokens(highlighter: CodeHighlighter?, code: String, language: String): List<CodeToken> {
            if (highlighter == null || language.isEmpty() || code.length > CodeHighlighter.MAX_HIGHLIGHTED) return emptyList()
            val tokens = try {
                highlighter.tokens(code, language)
            } catch (_: Exception) {
                return emptyList()
            }
            var last = 0
            return tokens.filter { token ->
                (token.start >= last && token.end > token.start && token.end <= code.length).also { if (it) last = token.end }
            }
        }

        /**
         * [code] as it is drawn: a paragraph per line (the line breaks between them would add
         * empty lines), up to [indent] spaces taken off each line, the tabs as spaces up to the
         * next column of [TAB_WIDTH], a wrapped line indented as far as its code, the [tokens] in
         * their colors.
         */
        private fun shownCode(code: String, indent: Int, advance: Float, tokens: List<CodeToken>, theme: CodeTheme): ShownCode {
            // Where each offset of the code is drawn, and each of its lines starts
            val offsets = IntArray(code.length + 1)
            val lineStarts = ArrayList<Int>()
            val text = buildAnnotatedString {
                var lineStart = 0
                while (true) {
                    val lineEnd = code.indexOf('\n', lineStart).let { if (it < 0) code.length else it }
                    val paragraphStart = length
                    lineStarts += paragraphStart
                    var i = lineStart
                    var removed = 0
                    while (i < lineEnd && removed < indent && code[i] == ' ') {
                        offsets[i++] = length
                        removed++
                    }
                    var column = 0
                    var leading = true
                    var leadingColumns = 0
                    while (i < lineEnd) {
                        val c = code[i]
                        offsets[i] = length
                        if (c == '\t') {
                            val spaces = TAB_WIDTH - column % TAB_WIDTH
                            repeat(spaces) { append(' ') }
                            column += spaces
                        } else {
                            append(c)
                            column++
                        }
                        if (leading && (c == ' ' || c == '\t')) leadingColumns = column else leading = false
                        i++
                    }
                    offsets[lineEnd] = length
                    addStyle(ParagraphStyle(textIndent = TextIndent(firstLine = 0.sp, restLine = (leadingColumns * advance).sp)), paragraphStart, length)
                    if (lineEnd >= code.length) break
                    lineStart = lineEnd + 1
                }
                for (token in tokens) {
                    val start = offsets[token.start]
                    val end = offsets[token.end]
                    if (end > start) addStyle(SpanStyle(color = theme.color(token.kind)), start, end)
                }
            }
            return ShownCode(text, offsets, lineStarts.toIntArray())
        }

        /**
         * The code as it is drawn, where every offset of the code is in [text], and where each of
         * its lines (a paragraph) starts in [text].
         */
        private class ShownCode(val text: AnnotatedString, val shownOffsets: IntArray, val lineStarts: IntArray)
    }
}

/** The background of code in the line on one line of a text: [rect], its corners of [radius]. */
internal class CodeChip(val rect: Rect, val radius: Float)

/**
 * The backgrounds of [inlineCode] in this layout: one per line of each code, from the left of its
 * first character to the right of its last one on the line and a little beyond, around the letters
 * of its font; they take no room. The canvas and the editor draw them the same way.
 */
internal fun TextLayoutResult.codeChips(inlineCode: List<MarkdownTextUtil.InlineCode>): List<CodeChip> {
    if (inlineCode.isEmpty()) return emptyList()
    val chips = mutableListOf<CodeChip>()
    val length = layoutInput.text.length
    for (code in inlineCode) {
        if (code.start < 0 || code.end > length || code.end <= code.start) continue
        val first = getLineForOffset(code.start)
        val last = getLineForOffset(code.end - 1)
        val padding = code.fontSizePx * CHIP_PADDING
        for (line in first..last) {
            val left = if (line == first) getBoundingBox(code.start).left else getLineLeft(line)
            val right = if (line == last) getBoundingBox(code.end - 1).right else getLineRight(line)
            val baseline = getLineBaseline(line)
            val rect = Rect(left - padding, baseline - code.fontSizePx * CHIP_ASCENT, right + padding, baseline + code.fontSizePx * CHIP_DESCENT)
            chips += CodeChip(rect, code.fontSizePx * CHIP_RADIUS)
        }
    }
    return chips
}

/**
 * The background of code in the line, relative to its font: beyond its characters on the sides,
 * above and below its baseline, its corners.
 */
private const val CHIP_PADDING = 0.15f
private const val CHIP_ASCENT = 0.9f
private const val CHIP_DESCENT = 0.25f
private const val CHIP_RADIUS = 0.2f
