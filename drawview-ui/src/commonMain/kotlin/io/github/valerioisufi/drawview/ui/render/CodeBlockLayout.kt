package io.github.valerioisufi.drawview.ui.render

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
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
import io.github.valerioisufi.drawview.model.text.code.CodeHighlighter
import io.github.valerioisufi.drawview.model.text.code.CodeToken
import io.github.valerioisufi.drawview.model.text.code.CodeTokenKind
import kotlin.math.max

/**
 * The colors of the code of a text of [text] color: its tokens, its background, the label of its
 * language. A dark text has the palette made for light paper, a light text the one made for dark
 * paper; the background is the color of the text, faint, so that it reads on every paper.
 */
internal class CodeTheme private constructor(val text: Color, private val tokens: Map<CodeTokenKind, Color>) {

    /** The background of the code, in the line and in the blocks. */
    val background: Color = text.copy(alpha = text.alpha * BACKGROUND_ALPHA)

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

        /** The colors of the code of a text drawn in [text]. */
        fun of(text: Color): CodeTheme = CodeTheme(text, if (text.luminance() > 0.5f) DARK_PAPER else LIGHT_PAPER)
    }
}

/**
 * A block of code laid out on the width of its text ([widthPx] x [heightPx] in pixels of the
 * destination): its code in the monospace of the notes and in the colors of its language, its
 * lines wrapped on the width less the padding, a wrapped line continuing under the start of its
 * code; on its background with rounded corners, the name of its language in its top-right corner.
 * The canvas and the editor draw a block with the same layout.
 *
 * @property naturalWidthPx the width of its longest line with the padding, unwrapped: what a text
 *   of automatic width grows to.
 */
public class CodeBlockLayout internal constructor(
    internal val code: TextLayoutResult,
    internal val label: TextLayoutResult?,
    public val widthPx: Float,
    public val heightPx: Float,
    public val naturalWidthPx: Float,
    internal val paddingPx: Float,
    internal val codeTopPx: Float,
    internal val labelTopPx: Float,
    internal val radiusPx: Float,
    internal val theme: CodeTheme,
    internal val fontPx: Float
) {
    private val backgroundPaint = Paint().apply {
        isAntiAlias = true
        color = theme.background
    }

    /** Where the label is drawn from the left of the block. */
    internal val labelLeftPx: Float get() = widthPx - paddingPx - (label?.size?.width ?: 0)

    /** Draws the block on [canvas], its top-left corner at [left], [top]. */
    internal fun draw(canvas: Canvas, left: Float, top: Float) {
        canvas.drawRoundRect(left, top, left + widthPx, top + heightPx, radiusPx, radiusPx, backgroundPaint)
        canvas.save()
        canvas.translate(left + paddingPx, top + codeTopPx)
        TextPainter.paint(canvas, code)
        canvas.restore()
        val label = label ?: return
        canvas.save()
        canvas.translate(left + labelLeftPx, top + labelTopPx)
        TextPainter.paint(canvas, label)
        canvas.restore()
    }

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

        /**
         * Lays out [code] written in [language] (its lines without the [indent] spaces of its
         * fence) in a text of [textFontPx] and [color] whose lines are [widthPx] wide, its tokens
         * told by [highlighter] (`null`: in one color).
         */
        fun layout(
            measurer: TextMeasurer,
            monospace: FontFamily,
            code: String,
            language: String,
            indent: Int,
            textFontPx: Float,
            widthPx: Float,
            color: Color,
            highlighter: CodeHighlighter?
        ): CodeBlockLayout {
            val fontPx = textFontPx * CODE_SCALE
            val theme = CodeTheme.of(color)
            val style = TextStyle(color = color, fontSize = fontPx.sp, fontFamily = monospace, letterSpacing = 0.sp, textAlign = TextAlign.Start)
            val advance = advance(measurer, style)
            val shown = shownCode(code, indent, advance, tokens(highlighter, code, language), theme)
            val padding = fontPx * PADDING_X
            val layout = measurer.measure(
                text = shown,
                style = style,
                softWrap = true,
                constraints = Constraints.fixedWidth((widthPx - 2f * padding).toInt().coerceAtLeast(1)),
                skipCache = true
            )
            val name = if (language.length > MAX_LABEL) language.take(MAX_LABEL) + "…" else language
            val label = name.takeIf { it.isNotEmpty() }?.let {
                measurer.measure(AnnotatedString(it), style.copy(color = theme.label, fontSize = (fontPx * LABEL_SCALE).sp), softWrap = false, skipCache = true)
            }
            val paddingY = fontPx * PADDING_Y
            val labelTop = paddingY * 0.5f
            val codeTop = if (label != null) labelTop + label.size.height + paddingY * 0.25f else paddingY
            return CodeBlockLayout(
                code = layout,
                label = label,
                widthPx = widthPx,
                heightPx = codeTop + layout.size.height + paddingY,
                naturalWidthPx = max(layout.multiParagraph.intrinsics.maxIntrinsicWidth, label?.size?.width?.toFloat() ?: 0f) + 2f * padding,
                paddingPx = padding,
                codeTopPx = codeTop,
                labelTopPx = labelTop,
                radiusPx = fontPx * RADIUS,
                theme = theme,
                fontPx = fontPx
            )
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
        private fun tokens(highlighter: CodeHighlighter?, code: String, language: String): List<CodeToken> {
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
        private fun shownCode(code: String, indent: Int, advance: Float, tokens: List<CodeToken>, theme: CodeTheme): AnnotatedString {
            // Where each offset of the code is drawn
            val offsets = IntArray(code.length + 1)
            return buildAnnotatedString {
                var lineStart = 0
                while (true) {
                    val lineEnd = code.indexOf('\n', lineStart).let { if (it < 0) code.length else it }
                    val paragraphStart = length
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
        }
    }
}
