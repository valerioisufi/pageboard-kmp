package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.pageboard.model.text.RichTextParser
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Span
import io.github.valerioisufi.pageboard.model.text.code.CodeHighlighter
import io.github.valerioisufi.pageboard.ui.render.CodeBlockLayout
import io.github.valerioisufi.pageboard.ui.render.CodeBlockSource
import io.github.valerioisufi.pageboard.ui.render.CodeTheme
import kotlin.math.max
import kotlin.math.min

/**
 * The notation of [RichTextParser] in Compose styles: the source of a text styled as it is written
 * (see [toLiveAnnotatedString]), the styles, sizes and prefixes [RichTextPresenter] presents the
 * texts with.
 */
public object MarkdownTextUtil {

    /**
     * Builds the [AnnotatedString] shown by the field of the editor ("conceal" effect):
     * - when the caret touches a span, its delimiters are shown with a faded color;
     * - otherwise the delimiters are invisible (almost no size), showing only the formatted content.
     *
     * The text is never changed, so that the offsets of the field are preserved.
     */
    public fun toLiveAnnotatedString(
        text: String,
        selection: TextRange,
        defaultColor: Color,
        baseFontSizeSp: TextUnit = 16.sp,
        monospace: FontFamily = FontFamily.Monospace
    ): AnnotatedString {
        if (text.isEmpty()) return AnnotatedString(text)
        val selMin = min(selection.start, selection.end)
        val selMax = max(selection.start, selection.end)
        val faded = SpanStyle(color = defaultColor.copy(alpha = 0.45f), fontSize = baseFontSizeSp * 0.9f)
        return buildAnnotatedString {
            append(text)
            for (span in RichTextParser.parse(text)) {
                // The caret is "on the span" when it touches or is inside its delimiters
                val touched = selMin <= span.end && selMax >= span.start
                for (delimiter in span.delimiters) addStyle(if (touched) faded else CONCEALED, delimiter.first, delimiter.last + 1)
                val style = when (span.kind) {
                    Kind.FORMULA -> SpanStyle(fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif, color = FORMULA_COLOR)
                    else -> contentStyle(span, baseFontSizeSp.value, monospace)
                }
                if (style != null) addStyle(style, span.contentStart, span.contentEnd)
            }
        }
    }

    /** The source MicroTeX renders for the formula [span] of [text]: in display style when written `$$…$$`. */
    internal fun formulaSource(text: String, span: Span): String {
        val content = text.substring(span.contentStart, span.contentEnd)
        return if (span.display) "\\displaystyle $content" else content
    }

    /** The size of the formula [span] in a text of [fontSizePx]. */
    internal fun formulaSizePx(span: Span, fontSizePx: Float): Float = fontSizePx * if (span.display) DISPLAY_SCALE else INLINE_SCALE

    /** The style of the content of [span] (`null`: none), the sizes relative to [baseFontSize]. */
    internal fun contentStyle(span: Span, baseFontSize: Float, monospace: FontFamily): SpanStyle? = when (span.kind) {
        Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        Kind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        Kind.LINK -> SpanStyle(textDecoration = TextDecoration.Underline)
        Kind.IMAGE -> SpanStyle(fontStyle = FontStyle.Italic, textDecoration = TextDecoration.Underline)
        Kind.CODE -> SpanStyle(fontFamily = monospace)
        Kind.CODE_BLOCK -> SpanStyle(fontFamily = monospace, fontSize = (baseFontSize * CodeBlockLayout.CODE_SCALE).sp)
        Kind.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseFontSize * headingScale(span.level)).sp)
        Kind.FORMULA, Kind.ESCAPE, Kind.LIST_ITEM, Kind.TABLE, Kind.QUOTE -> null
    }

    /** How much bigger than the text a heading of [level] is: all six told apart, bold from the fourth at the size of the text and below. */
    internal fun headingScale(level: Int): Float = when (level) {
        1 -> 1.4f
        2 -> 1.25f
        3 -> 1.1f
        4 -> 1f
        5 -> 0.95f
        else -> 0.9f
    }

    /**
     * Code in the line, from [start] to [end] of the text shown, in a font of [fontSizePx]: drawn on
     * a background.
     */
    public class InlineCode(public val start: Int, public val end: Int, public val fontSizePx: Float)

    /** A formula rendered: its [image], [widthPx] x [heightPx], its baseline at [baselineRatio] of its height. */
    public class RenderedFormula<T>(public val image: T, public val widthPx: Float, public val heightPx: Float, public val baselineRatio: Float)

    /** Size of the formulas in the line, and of the display ones (`$$`), relative to the text. */
    private const val INLINE_SCALE = 1.1f
    private const val DISPLAY_SCALE = 1.2f

    /** Concealed delimiters: invisible, almost no room (the offsets of the field are kept). */
    private val CONCEALED = SpanStyle(color = Color.Transparent, fontSize = 0.001.sp)
    private val FORMULA_COLOR = Color(0xFF1E88E5)
}
