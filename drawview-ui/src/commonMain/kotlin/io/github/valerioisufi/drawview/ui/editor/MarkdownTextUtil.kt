package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import io.github.valerioisufi.drawview.model.text.RichTextParser.Span
import kotlin.math.max
import kotlin.math.min

/**
 * The texts written with the notation of [RichTextParser], as Compose shows them:
 * 1. the field of the editor, Obsidian-like: the delimiters away from the caret concealed, the
 *    formatting applied, the offsets of the text kept (see [toLiveAnnotatedString]);
 * 2. the text drawn on the canvas: delimiters removed, formulas rendered by MicroTeX in their
 *    place (see [toCanvasText]).
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

    /**
     * The font of a text, per pixel of its size: the [ascent] and the [descent] of a line, the
     * [advance] of the character standing for a concealed formula (see [toEditorText]).
     */
    internal class FontRoom(public val ascent: Float, public val descent: Float, public val advance: Float)

    /**
     * A formula drawn over the field of the editor, on the character [offset] of its text standing
     * for the source of the formula; [widthPx] x [heightPx], its baseline at [baselineRatio] of its
     * height, or centered in its line when [block].
     */
    internal class EditorFormula<T>(
        val offset: Int,
        val image: T,
        val widthPx: Float,
        val heightPx: Float,
        val baselineRatio: Float,
        val block: Boolean
    )

    /**
     * The text shown by the field of the editor: [text] with the formulas away from the caret
     * standing as one character each (see [toEditorText]), [offsetMapping] between the offsets of
     * the text written and of [text], the [formulas] to draw over their characters, and the
     * formula under the caret ([formulaAtCaret], its source shown), if any.
     */
    internal class EditorText<T>(
        val text: AnnotatedString,
        val offsetMapping: OffsetMapping,
        val formulas: List<EditorFormula<T>>,
        val formulaAtCaret: Span?
    )

    /**
     * The text of the field of the editor, as the page draws it: delimiters concealed away from
     * the caret (see [toLiveAnnotatedString]); every formula away from the caret rendered by
     * [renderFormula] and standing as one transparent character exactly as wide as its image and
     * giving its line room for it, the image drawn over it by the editor (no placeholder in a text
     * field); the formula under the caret shows its source. A formula that cannot be rendered is
     * red italic source, as on the page.
     *
     * @param fontSizePx size of the text in pixels; [density] pixels per sp.
     * @param lineWidthPx width of the lines (a block formula takes a line).
     * @param room the font of the text per pixel of its size.
     */
    internal fun <T> toEditorText(
        text: String,
        selection: TextRange,
        defaultColor: Color,
        fontSizePx: Float,
        density: Float,
        lineWidthPx: Float,
        room: FontRoom,
        textFont: FontFamily,
        monospace: FontFamily = FontFamily.Monospace,
        renderFormula: (latex: String, fontSizePx: Float) -> RenderedFormula<T>?
    ): EditorText<T> {
        val selMin = min(selection.start, selection.end)
        val selMax = max(selection.start, selection.end)
        val spans = RichTextParser.parse(text)
        fun Float.toSp() = (this / density).sp
        val faded = SpanStyle(color = defaultColor.copy(alpha = 0.45f), fontSize = (fontSizePx * 0.9f).toSp())

        class Concealed(val span: Span, val rendered: RenderedFormula<T>, val widthPx: Float, val heightPx: Float, val block: Boolean)
        val concealed = mutableListOf<Concealed>()
        val errors = mutableListOf<Span>()
        var atCaret: Span? = null
        for (span in spans) {
            if (span.kind != Kind.FORMULA) continue
            val touched = selMin <= span.end && selMax >= span.start
            if (touched) {
                if (atCaret == null) atCaret = span
                continue
            }
            val rendered = renderFormula(formulaSource(text, span), formulaSizePx(span, fontSizePx))?.takeIf { it.widthPx > 0f && it.heightPx > 0f }
            when {
                rendered == null -> errors += span
                span.block -> {
                    val fit = min(1f, lineWidthPx / rendered.widthPx)
                    concealed += Concealed(span, rendered, rendered.widthPx * fit, rendered.heightPx * fit, block = true)
                }
                else -> concealed += Concealed(span, rendered, rendered.widthPx, rendered.heightPx, block = false)
            }
        }
        val mapping = ConcealMapping(concealed.map { it.span.start until it.span.end })

        val formulas = mutableListOf<EditorFormula<T>>()
        val annotated = buildAnnotatedString {
            var last = 0
            for (formula in concealed) {
                append(text, last, formula.span.start)
                last = formula.span.end
                append(OBJECT_REPLACEMENT)
            }
            append(text, last, text.length)

            fun style(style: SpanStyle, start: Int, end: Int) {
                val from = mapping.originalToTransformed(start)
                val to = mapping.originalToTransformed(end)
                if (to > from) addStyle(style, from, to)
            }
            val concealedSpans = concealed.map { it.span }.toSet()
            for (span in spans) {
                if (span in concealedSpans || span in errors) continue
                val touched = selMin <= span.end && selMax >= span.start
                for (delimiter in span.delimiters) style(if (touched) faded else CONCEALED, delimiter.first, delimiter.last + 1)
                val content = when (span.kind) {
                    Kind.FORMULA -> SpanStyle(fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif, color = FORMULA_COLOR)
                    else -> contentStyle(span, fontSizePx / density, monospace)
                }
                if (content != null) style(content, span.contentStart, span.contentEnd)
            }
            for (span in errors) style(SpanStyle(color = ERROR_COLOR, fontStyle = FontStyle.Italic), span.start, span.end)

            // Last: the room of every formula overrides the styles around it
            for (formula in concealed) {
                val offset = mapping.originalToTransformed(formula.span.start)
                val size = if (formula.block) {
                    formula.heightPx / (room.ascent + room.descent)
                } else {
                    val ascent = formula.heightPx * formula.rendered.baselineRatio
                    max(ascent / room.ascent, (formula.heightPx - ascent) / room.descent)
                }.coerceAtLeast(MIN_ROOM_PX)
                val width = if (formula.block) lineWidthPx * LINE_FILL else formula.widthPx
                addStyle(
                    SpanStyle(
                        color = Color.Transparent,
                        fontSize = size.toSp(),
                        fontFamily = textFont,
                        fontWeight = FontWeight.Normal,
                        fontStyle = FontStyle.Normal,
                        letterSpacing = (width - size * room.advance).toSp(),
                        textDecoration = TextDecoration.None
                    ),
                    offset,
                    offset + 1
                )
                formulas += EditorFormula(offset, formula.rendered.image, formula.widthPx, formula.heightPx, formula.rendered.baselineRatio, formula.block)
            }
        }
        return EditorText(annotated, mapping, formulas, atCaret)
    }

    /**
     * The offsets of a text whose [ranges] (sorted, apart) each stand as one character, and of the
     * text written: an offset inside a range goes to its character.
     */
    private class ConcealMapping(private val ranges: List<IntRange>) : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int {
            var removed = 0
            for (range in ranges) {
                val end = range.last + 1
                when {
                    offset >= end -> removed += end - range.first - 1
                    offset > range.first -> return range.first - removed
                    else -> break
                }
            }
            return offset - removed
        }

        override fun transformedToOriginal(offset: Int): Int {
            var removed = 0
            for (range in ranges) {
                if (offset > range.first - removed) removed += range.last + 1 - range.first - 1 else break
            }
            return offset + removed
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
    private fun contentStyle(span: Span, baseFontSize: Float, monospace: FontFamily): SpanStyle? = when (span.kind) {
        Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        Kind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        Kind.CODE -> SpanStyle(fontFamily = monospace)
        Kind.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseFontSize * headingScale(span.level)).sp)
        Kind.FORMULA, Kind.ESCAPE, Kind.LIST_ITEM -> null
    }

    /** How much bigger than the text a heading of [level] is. */
    private fun headingScale(level: Int): Float = when (level) {
        1 -> 1.4f
        2 -> 1.25f
        else -> 1.1f
    }

    /**
     * A formula of a text drawn on the canvas.
     *
     * @property placeholderIndex index (in [CanvasText.placeholders]) of the placeholder that
     * reserves its room: above the baseline, or the whole line for a [block] formula.
     * @property baselineRatio position of the formula baseline (0 = top, 1 = bottom).
     * @property latex the source of the formula as it is rendered.
     * @property block a formula on a line of its own, centered in its placeholder.
     */
    public class InlineFormula<T>(
        public val image: T,
        public val widthPx: Float,
        public val heightPx: Float,
        public val baselineRatio: Float,
        public val placeholderIndex: Int,
        public val latex: String = "",
        public val block: Boolean = false
    )

    /**
     * Text of a block laid out on the canvas: the delimiters are removed and each formula is
     * replaced by placeholders.
     */
    public class CanvasText<T>(
        public val text: AnnotatedString,
        /** Placeholder ranges: per formula, the body (above the baseline) and the descent, or the line of a block formula. */
        public val placeholders: List<PlaceholderSpec>,
        public val formulas: List<InlineFormula<T>>
    )

    /**
     * A placeholder of [CanvasText] in pixels: the body of a formula (bottom on the baseline), the
     * zero-width one reserving its descent (top on the top of the font), or the line of a block
     * formula (centered in the line).
     */
    public class PlaceholderSpec(public val start: Int, public val end: Int, public val widthPx: Float, public val heightPx: Float, public val verticalAlign: PlaceholderVerticalAlign)

    /**
     * Rendered formula used by [toCanvasText].
     */
    public class RenderedFormula<T>(public val image: T, public val widthPx: Float, public val heightPx: Float, public val baselineRatio: Float)

    /**
     * Converts a text into the text drawn on the canvas (the equivalent of the Android `Spanned` of
     * the original app): delimiters and escapes removed, the formulas rendered by [renderFormula]
     * and placed in the line, aligned on its baseline; a formula alone on its lines is centered on
     * a line of its own when [lineWidthPx] is given. Invalid formulas are shown as red italic
     * source.
     *
     * @param fontAscentPx ascent of the base font, used to reserve the descent of the formulas.
     * @param lineWidthPx width of the lines, `null` when the text has none (block formulas then
     *   stay in the line).
     */
    public fun <T> toCanvasText(
        text: String,
        baseColor: Color,
        fontSizePx: Float,
        fontAscentPx: Float,
        lineWidthPx: Float? = null,
        monospace: FontFamily = FontFamily.Monospace,
        renderFormula: (latex: String, fontSizePx: Float) -> RenderedFormula<T>?
    ): CanvasText<T> {
        val spans = RichTextParser.parse(text)
        val hidden = BooleanArray(text.length)
        val formulaAt = HashMap<Int, Span>()
        for (span in spans) {
            if (span.kind == Kind.FORMULA) {
                formulaAt[span.start] = span
                // A block formula takes its lines alone: the spaces around it go
                if (span.block && lineWidthPx != null) {
                    var i = span.start - 1
                    while (i >= 0 && text[i] != '\n' && text[i].isWhitespace()) hidden[i--] = true
                    i = span.end
                    while (i < text.length && text[i] != '\n' && text[i].isWhitespace()) hidden[i++] = true
                }
            } else {
                for (delimiter in span.delimiters) for (i in delimiter) hidden[i] = true
            }
        }

        val placeholders = mutableListOf<PlaceholderSpec>()
        val formulas = mutableListOf<InlineFormula<T>>()
        val errors = mutableListOf<IntRange>()
        // Offset in the drawn text of every offset of the source
        val offsets = IntArray(text.length + 1)
        val annotated = buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                offsets[i] = length
                val formula = formulaAt[i]
                if (formula == null) {
                    if (!hidden[i]) append(text[i])
                    i++
                    continue
                }
                val latex = formulaSource(text, formula)
                val rendered = renderFormula(latex, formulaSizePx(formula, fontSizePx))?.takeIf { it.widthPx > 0f && it.heightPx > 0f }
                when {
                    rendered == null -> {
                        // The source, highlighted when it cannot be rendered
                        val start = length
                        append(text, formula.start, formula.end)
                        errors += start until length
                    }
                    formula.block && lineWidthPx != null -> {
                        // The whole line, the formula centered in it (smaller when wider than the line)
                        val fit = min(1f, lineWidthPx / rendered.widthPx)
                        val start = length
                        append(OBJECT_REPLACEMENT)
                        placeholders += PlaceholderSpec(start, start + 1, lineWidthPx * LINE_FILL, rendered.heightPx * fit, PlaceholderVerticalAlign.Center)
                        formulas += InlineFormula(rendered.image, rendered.widthPx * fit, rendered.heightPx * fit, rendered.baselineRatio, placeholders.size - 1, latex, block = true)
                    }
                    else -> {
                        val ascent = rendered.heightPx * rendered.baselineRatio
                        val descent = rendered.heightPx - ascent
                        // Body of the formula: bottom on the baseline
                        val bodyStart = length
                        append(OBJECT_REPLACEMENT)
                        placeholders += PlaceholderSpec(bodyStart, bodyStart + 1, rendered.widthPx, ascent.coerceAtLeast(1f), PlaceholderVerticalAlign.AboveBaseline)
                        formulas += InlineFormula(rendered.image, rendered.widthPx, rendered.heightPx, rendered.baselineRatio, placeholders.size - 1, latex)
                        if (descent > 0.5f) {
                            // Zero-width placeholder hanging from the top of the font down to the
                            // descent of the formula (a word joiner prevents a line break)
                            append(WORD_JOINER)
                            val descentStart = length
                            append(OBJECT_REPLACEMENT)
                            placeholders += PlaceholderSpec(descentStart, descentStart + 1, 0.01f, fontAscentPx + descent, PlaceholderVerticalAlign.TextTop)
                        }
                    }
                }
                for (k in formula.start + 1 until formula.end) offsets[k] = length
                i = formula.end
            }
            offsets[text.length] = length

            addStyle(SpanStyle(color = baseColor), 0, length)
            for (span in spans) {
                val style = contentStyle(span, fontSizePx, monospace) ?: continue
                val start = offsets[span.contentStart]
                val end = offsets[span.contentEnd]
                if (end > start) addStyle(style, start, end)
            }
            for (error in errors) addStyle(SpanStyle(color = ERROR_COLOR, fontStyle = FontStyle.Italic), error.first, error.last + 1)
        }
        return CanvasText(annotated, placeholders, formulas)
    }

    internal const val OBJECT_REPLACEMENT = "￼"
    internal const val WORD_JOINER = "⁠"

    /** Size of the formulas in the line, and of the display ones (`$$`), relative to the text. */
    private const val INLINE_SCALE = 1.1f
    private const val DISPLAY_SCALE = 1.2f

    /** Share of the line a block formula takes: all of it, without wrapping on a rounding. */
    private const val LINE_FILL = 0.999f

    /** The least size of the character standing for a formula (a size of zero is no size). */
    private const val MIN_ROOM_PX = 0.5f

    /** Concealed delimiters: invisible, almost no room (the offsets of the field are kept). */
    private val CONCEALED = SpanStyle(color = Color.Transparent, fontSize = 0.001.sp)
    private val FORMULA_COLOR = Color(0xFF1E88E5)
    private val ERROR_COLOR = Color(0xFFD32F2F)
}
