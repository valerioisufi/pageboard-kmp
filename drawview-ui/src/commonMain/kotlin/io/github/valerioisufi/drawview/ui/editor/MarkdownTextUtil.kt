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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.core.text.RichTextParser
import io.github.valerioisufi.drawview.core.text.RichTextParser.Kind
import io.github.valerioisufi.drawview.core.text.RichTextParser.Span
import kotlin.math.max
import kotlin.math.min

/**
 * The texts written with the notation of [RichTextParser], as Compose shows them:
 * 1. the field of the editor, Obsidian-like: the delimiters away from the caret concealed, the
 *    formatting applied, the offsets of the text kept (see [toLiveAnnotatedString]);
 * 2. the text drawn on the canvas: delimiters removed, formulas rendered by MicroTeX in their
 *    place (see [toCanvasText]);
 * 3. the helpers of the formatting bar of the editor.
 */
object MarkdownTextUtil {

    /**
     * Builds the [AnnotatedString] shown by the field of the editor ("conceal" effect):
     * - when the caret touches a span, its delimiters are shown with a faded color;
     * - otherwise the delimiters are invisible (almost no size), showing only the formatted content.
     *
     * The text is never changed, so that the offsets of the field are preserved.
     */
    fun toLiveAnnotatedString(
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

    /** The style of the content of [span] (`null`: none), the sizes relative to [baseFontSize]. */
    private fun contentStyle(span: Span, baseFontSize: Float, monospace: FontFamily): SpanStyle? = when (span.kind) {
        Kind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        Kind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        Kind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        Kind.CODE -> SpanStyle(fontFamily = monospace)
        Kind.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = (baseFontSize * headingScale(span.level)).sp)
        Kind.FORMULA, Kind.ESCAPE -> null
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
    class InlineFormula<T>(
        val image: T,
        val widthPx: Float,
        val heightPx: Float,
        val baselineRatio: Float,
        val placeholderIndex: Int,
        val latex: String = "",
        val block: Boolean = false
    )

    /**
     * Text of a block laid out on the canvas: the delimiters are removed and each formula is
     * replaced by placeholders.
     */
    class CanvasText<T>(
        val text: AnnotatedString,
        /** Placeholder ranges: per formula, the body (above the baseline) and the descent, or the line of a block formula. */
        val placeholders: List<PlaceholderSpec>,
        val formulas: List<InlineFormula<T>>
    )

    /**
     * A placeholder of [CanvasText] in pixels: the body of a formula (bottom on the baseline), the
     * zero-width one reserving its descent (top on the top of the font), or the line of a block
     * formula (centered in the line).
     */
    class PlaceholderSpec(val start: Int, val end: Int, val widthPx: Float, val heightPx: Float, val verticalAlign: PlaceholderVerticalAlign)

    /**
     * Rendered formula used by [toCanvasText].
     */
    class RenderedFormula<T>(val image: T, val widthPx: Float, val heightPx: Float, val baselineRatio: Float)

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
    fun <T> toCanvasText(
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
                val content = text.substring(formula.contentStart, formula.contentEnd)
                val latex = if (formula.display) "\\displaystyle $content" else content
                val rendered = renderFormula(latex, fontSizePx * if (formula.display) DISPLAY_SCALE else INLINE_SCALE)
                    ?.takeIf { it.widthPx > 0f && it.heightPx > 0f }
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

    // --- Editing helpers (formatting bar) ---

    /**
     * Adds or removes the Markdown delimiters around the selection, or inserts them at the
     * cursor (placing the cursor between them).
     */
    fun toggleDelimiters(currentValue: TextFieldValue, prefix: String, suffix: String): TextFieldValue {
        val text = currentValue.text
        val sel = currentValue.selection

        val start = min(sel.start, sel.end)
        val end = max(sel.start, sel.end)

        if (start != end) {
            val selected = text.substring(start, end)
            val pLen = prefix.length
            val sLen = suffix.length
            // Already wrapped: remove the delimiters
            if (start >= pLen && end + sLen <= text.length &&
                text.substring(start - pLen, start) == prefix &&
                text.substring(end, end + sLen) == suffix
            ) {
                val newText = text.substring(0, start - pLen) + selected + text.substring(end + sLen)
                return currentValue.copy(
                    text = newText,
                    selection = TextRange(start - pLen, end - pLen)
                )
            } else {
                val newText = text.substring(0, start) + prefix + selected + suffix + text.substring(end)
                return currentValue.copy(
                    text = newText,
                    selection = TextRange(start + pLen, end + pLen)
                )
            }
        } else {
            val newText = text.substring(0, start) + prefix + suffix + text.substring(start)
            val newCursor = start + prefix.length
            return currentValue.copy(
                text = newText,
                selection = TextRange(newCursor, newCursor)
            )
        }
    }

    /**
     * Makes the line of the caret (of the start of the selection) a heading, or a heading line
     * plain text again: the `#` and the spaces after them at the start of the line.
     */
    fun toggleHeading(currentValue: TextFieldValue): TextFieldValue {
        val text = currentValue.text
        val sel = currentValue.selection
        val lineStart = text.lastIndexOf('\n', min(sel.start, sel.end) - 1) + 1
        var prefixEnd = lineStart
        while (prefixEnd < text.length && text[prefixEnd] == '#') prefixEnd++
        val isHeading = prefixEnd > lineStart && prefixEnd < text.length && text[prefixEnd] == ' '
        if (isHeading) {
            while (prefixEnd < text.length && text[prefixEnd] == ' ') prefixEnd++
        }
        val removed = if (isHeading) prefixEnd - lineStart else 0
        val inserted = if (isHeading) "" else HEADING_PREFIX
        val shift = inserted.length - removed
        fun moved(offset: Int) = if (offset < lineStart) offset else max(lineStart, offset + shift)
        return currentValue.copy(
            text = text.substring(0, lineStart) + inserted + text.substring(lineStart + removed),
            selection = TextRange(moved(sel.start), moved(sel.end))
        )
    }

    private const val HEADING_PREFIX = "# "
    internal const val OBJECT_REPLACEMENT = "￼"
    internal const val WORD_JOINER = "⁠"

    /** Size of the formulas in the line, and of the display ones (`$$`), relative to the text. */
    private const val INLINE_SCALE = 1.1f
    private const val DISPLAY_SCALE = 1.2f

    /** Share of the line a block formula takes: all of it, without wrapping on a rounding. */
    private const val LINE_FILL = 0.999f

    /** Concealed delimiters: invisible, almost no room (the offsets of the field are kept). */
    private val CONCEALED = SpanStyle(color = Color.Transparent, fontSize = 0.001.sp)
    private val FORMULA_COLOR = Color(0xFF1E88E5)
    private val ERROR_COLOR = Color(0xFFD32F2F)
}
