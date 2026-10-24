package io.github.valerioisufi.drawview.ui.editor

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
import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import io.github.valerioisufi.drawview.model.text.RichTextParser.Span
import io.github.valerioisufi.drawview.model.text.code.CodeHighlighter
import io.github.valerioisufi.drawview.ui.render.CodeBlockLayout
import io.github.valerioisufi.drawview.ui.render.CodeBlockSource
import io.github.valerioisufi.drawview.ui.render.CodeTheme
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
     * A block of code drawn over the field of the editor, on the character [offset] of its text
     * standing for its source, in the whole line: its [layout], the code of its source starting at
     * [codeStart] of the text written.
     */
    internal class EditorCodeBlock(val offset: Int, val layout: CodeBlockLayout, val codeStart: Int)

    /**
     * The text shown by the field of the editor: [text] with the formulas and the blocks of code
     * away from the caret standing as one character each (see [toEditorText]), [offsetMapping]
     * between the offsets of the text written and of [text], the [formulas] and the [codeBlocks]
     * to draw over their characters, the formula under the caret ([formulaAtCaret], its source
     * shown), if any; the parts of [text] that are blocks of code showing their source
     * ([codeSources]) and code in the line ([inlineCode]), drawn on a background.
     */
    internal class EditorText<T>(
        val text: AnnotatedString,
        val offsetMapping: OffsetMapping,
        val formulas: List<EditorFormula<T>>,
        val formulaAtCaret: Span?,
        val codeBlocks: List<EditorCodeBlock> = emptyList(),
        val codeSources: List<IntRange> = emptyList(),
        val inlineCode: List<InlineCode> = emptyList()
    )

    /**
     * The text of the field of the editor, as the page draws it: delimiters concealed away from
     * the caret (see [toLiveAnnotatedString]); every formula away from the caret rendered by
     * [renderFormula] and standing as one transparent character exactly as wide as its image and
     * giving its line room for it, the image drawn over it by the editor (no placeholder in a text
     * field); the formula under the caret shows its source. A formula that cannot be rendered is
     * red italic source, as on the page. Likewise every block of code away from the caret, laid
     * out by [layoutCodeBlock], stands as one character as wide as the line and as high as the
     * block; the block of the caret shows its source, its fences faded, its code in the colors told
     * by [highlighter].
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
        highlighter: CodeHighlighter? = null,
        layoutCodeBlock: ((CodeBlockSource) -> CodeBlockLayout)? = null,
        renderFormula: (latex: String, fontSizePx: Float) -> RenderedFormula<T>?
    ): EditorText<T> {
        val selMin = min(selection.start, selection.end)
        val selMax = max(selection.start, selection.end)
        val spans = RichTextParser.parse(text)
        fun Float.toSp() = (this / density).sp
        val faded = SpanStyle(color = defaultColor.copy(alpha = 0.45f), fontSize = (fontSizePx * 0.9f).toSp())

        class Concealed(val span: Span, val rendered: RenderedFormula<T>, val widthPx: Float, val heightPx: Float, val block: Boolean)
        class ConcealedBlock(val span: Span, val layout: CodeBlockLayout)
        val concealed = mutableListOf<Concealed>()
        val blocks = mutableListOf<ConcealedBlock>()
        val sources = mutableListOf<Span>()
        val errors = mutableListOf<Span>()
        var atCaret: Span? = null
        for (span in spans) {
            val touched = selMin <= span.end && selMax >= span.start
            if (span.kind == Kind.CODE_BLOCK) {
                if (touched || layoutCodeBlock == null) {
                    sources += span
                } else {
                    blocks += ConcealedBlock(span, layoutCodeBlock(codeBlockSource(text, span, lineWidthPx)))
                }
                continue
            }
            if (span.kind != Kind.FORMULA) continue
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
        // What stands as one character, in the order of the text (the spans never overlap)
        val hidden = (concealed.map { it.span } + blocks.map { it.span }).sortedBy { it.start }
        val mapping = ConcealMapping(hidden.map { it.start until it.end })

        val formulas = mutableListOf<EditorFormula<T>>()
        val codeBlocks = mutableListOf<EditorCodeBlock>()
        val codeSources = mutableListOf<IntRange>()
        val inlineCode = mutableListOf<InlineCode>()
        val annotated = buildAnnotatedString {
            var last = 0
            for (span in hidden) {
                append(text, last, span.start)
                last = span.end
                append(OBJECT_REPLACEMENT)
            }
            append(text, last, text.length)

            fun style(style: SpanStyle, start: Int, end: Int) {
                val from = mapping.originalToTransformed(start)
                val to = mapping.originalToTransformed(end)
                if (to > from) addStyle(style, from, to)
            }
            val hiddenSpans = hidden.toHashSet()
            val errorSpans = errors.toHashSet()
            // The spans still open, the innermost last: the size of the code in a heading
            val open = ArrayList<Span>()
            for (span in spans) {
                while (open.isNotEmpty() && open.last().end <= span.start) open.removeAt(open.lastIndex)
                open += span
                if (span in hiddenSpans || span in errorSpans) continue
                val touched = selMin <= span.end && selMax >= span.start
                for (delimiter in span.delimiters) style(if (touched) faded else CONCEALED, delimiter.first, delimiter.last + 1)
                val content = when (span.kind) {
                    Kind.FORMULA -> SpanStyle(fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif, color = FORMULA_COLOR)
                    else -> contentStyle(span, fontSizePx / density, monospace)
                }
                if (content != null) style(content, span.contentStart, span.contentEnd)
                if (span.kind == Kind.CODE) {
                    val heading = open.lastOrNull { it.kind == Kind.HEADING }
                    val from = mapping.originalToTransformed(span.contentStart)
                    val to = mapping.originalToTransformed(span.contentEnd)
                    if (to > from) inlineCode += InlineCode(from, to, fontSizePx * (heading?.let { headingScale(it.level) } ?: 1f))
                }
            }
            for (span in errors) style(SpanStyle(color = ERROR_COLOR, fontStyle = FontStyle.Italic), span.start, span.end)

            // The source of the block of the caret in the colors of its language, on its background
            val theme = CodeTheme.of(defaultColor)
            for (span in sources) {
                val code = text.substring(span.contentStart, span.contentEnd)
                for (token in CodeBlockLayout.tokens(highlighter, code, span.language)) {
                    style(SpanStyle(color = theme.color(token.kind)), span.contentStart + token.start, span.contentStart + token.end)
                }
                codeSources += mapping.originalToTransformed(span.start) until mapping.originalToTransformed(span.end)
            }

            // Last: the room of every formula and block overrides the styles around it
            fun room(offset: Int, size: Float, width: Float) = addStyle(
                SpanStyle(
                    color = Color.Transparent,
                    fontSize = size.coerceAtLeast(MIN_ROOM_PX).toSp(),
                    fontFamily = textFont,
                    fontWeight = FontWeight.Normal,
                    fontStyle = FontStyle.Normal,
                    letterSpacing = (width - size.coerceAtLeast(MIN_ROOM_PX) * room.advance).toSp(),
                    textDecoration = TextDecoration.None
                ),
                offset,
                offset + 1
            )
            for (formula in concealed) {
                val offset = mapping.originalToTransformed(formula.span.start)
                val size = if (formula.block) {
                    formula.heightPx / (room.ascent + room.descent)
                } else {
                    val ascent = formula.heightPx * formula.rendered.baselineRatio
                    max(ascent / room.ascent, (formula.heightPx - ascent) / room.descent)
                }
                room(offset, size, if (formula.block) lineWidthPx * LINE_FILL else formula.widthPx)
                formulas += EditorFormula(offset, formula.rendered.image, formula.widthPx, formula.heightPx, formula.rendered.baselineRatio, formula.block)
            }
            for (block in blocks) {
                val offset = mapping.originalToTransformed(block.span.start)
                room(offset, block.layout.heightPx / (room.ascent + room.descent), lineWidthPx * LINE_FILL)
                codeBlocks += EditorCodeBlock(offset, block.layout, block.span.contentStart)
            }
        }
        return EditorText(annotated, mapping, formulas, atCaret, codeBlocks, codeSources, inlineCode)
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

    /** What the block of code [span] of [text] is laid out from, in lines of [lineWidthPx]. */
    private fun codeBlockSource(text: String, span: Span, lineWidthPx: Float) =
        CodeBlockSource(text.substring(span.contentStart, span.contentEnd), span.language, span.level, span.numbered, lineWidthPx)

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
        Kind.CODE_BLOCK -> SpanStyle(fontFamily = monospace, fontSize = (baseFontSize * CodeBlockLayout.CODE_SCALE).sp)
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
     * Text of a block laid out on the canvas: the delimiters are removed and each formula and
     * block of code is replaced by placeholders.
     */
    public class CanvasText<T>(
        public val text: AnnotatedString,
        /** Placeholder ranges: per formula, the body (above the baseline) and the descent, or the line of a block formula or of a block of code. */
        public val placeholders: List<PlaceholderSpec>,
        public val formulas: List<InlineFormula<T>>,
        public val codeBlocks: List<CanvasCodeBlock> = emptyList(),
        public val inlineCode: List<InlineCode> = emptyList()
    )

    /**
     * A block of code of a text drawn on the canvas: its [layout], drawn in the placeholder
     * [placeholderIndex] of [CanvasText.placeholders], a line of its own.
     */
    public class CanvasCodeBlock(public val layout: CodeBlockLayout, public val placeholderIndex: Int)

    /**
     * Code in the line, from [start] to [end] of [CanvasText.text], in a font of [fontSizePx]:
     * drawn on a background.
     */
    public class InlineCode(public val start: Int, public val end: Int, public val fontSizePx: Float)

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
     * @param layoutCodeBlock lays out a block of code (see [CodeBlockSource]); `null`, or without
     *   [lineWidthPx], a block is drawn as code in the line.
     */
    public fun <T> toCanvasText(
        text: String,
        baseColor: Color,
        fontSizePx: Float,
        fontAscentPx: Float,
        lineWidthPx: Float? = null,
        monospace: FontFamily = FontFamily.Monospace,
        layoutCodeBlock: ((CodeBlockSource) -> CodeBlockLayout)? = null,
        renderFormula: (latex: String, fontSizePx: Float) -> RenderedFormula<T>?
    ): CanvasText<T> {
        val spans = RichTextParser.parse(text)
        val hidden = BooleanArray(text.length)
        val formulaAt = HashMap<Int, Span>()
        val listItemAt = HashMap<Int, Span>()
        val blockAt = HashMap<Int, Pair<Span, CodeBlockLayout>>()
        for (span in spans) {
            if (span.kind == Kind.CODE_BLOCK && layoutCodeBlock != null && lineWidthPx != null) {
                blockAt[span.start] = span to layoutCodeBlock(codeBlockSource(text, span, lineWidthPx))
                continue
            }
            when (span.kind) {
                Kind.FORMULA -> {
                    formulaAt[span.start] = span
                    // A block formula takes its lines alone: the spaces around it go
                    if (span.block && lineWidthPx != null) {
                        var i = span.start - 1
                        while (i >= 0 && text[i] != '\n' && text[i].isWhitespace()) hidden[i--] = true
                        i = span.end
                        while (i < text.length && text[i] != '\n' && text[i].isWhitespace()) hidden[i++] = true
                    }
                }
                Kind.LIST_ITEM -> {
                    listItemAt[span.start] = span
                }
                else -> {
                    for (delimiter in span.delimiters) for (i in delimiter) hidden[i] = true
                }
            }
        }

        val hasListItems = listItemAt.isNotEmpty()
        val placeholders = mutableListOf<PlaceholderSpec>()
        val formulas = mutableListOf<InlineFormula<T>>()
        val codeBlocks = mutableListOf<CanvasCodeBlock>()
        val inlineCode = mutableListOf<InlineCode>()
        val errors = mutableListOf<IntRange>()
        // Offset in the drawn text of every offset of the source
        val offsets = IntArray(text.length + 1)
        val annotated = buildAnnotatedString {
            /** The block of code [block] as a placeholder of its line, its offsets on it. */
            fun appendCodeBlock(block: Span, layout: CodeBlockLayout) {
                val start = length
                append(OBJECT_REPLACEMENT)
                placeholders += PlaceholderSpec(start, start + 1, (lineWidthPx ?: 0f) * LINE_FILL, layout.heightPx, PlaceholderVerticalAlign.Center)
                codeBlocks += CanvasCodeBlock(layout, placeholders.size - 1)
                offsets[block.start] = start
                for (k in block.start + 1..block.end) offsets[k] = length
            }

            if (!hasListItems) {
                var i = 0
                while (i < text.length) {
                    offsets[i] = length
                    val block = blockAt[i]
                    if (block != null) {
                        appendCodeBlock(block.first, block.second)
                        i = block.first.end
                        continue
                    }
                    val formula = formulaAt[i]
                    if (formula == null) {
                        if (!hidden[i]) append(text[i])
                        i++
                        continue
                    }
                    appendFormula(formula, text, fontSizePx, fontAscentPx, lineWidthPx, placeholders, formulas, errors, offsets, renderFormula)
                    i = formula.end
                }
                offsets[text.length] = length
            } else {
                // Line-by-line processing with ParagraphStyle hanging indent
                var lineStart = 0
                while (lineStart <= text.length) {
                    val block = blockAt[lineStart]
                    if (block != null) {
                        val pStart = length
                        appendCodeBlock(block.first, block.second)
                        addStyle(ParagraphStyle(), pStart, length)
                        if (block.first.end >= text.length) break
                        lineStart = block.first.end + 1
                        continue
                    }
                    val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
                    val listItem = listItemAt[lineStart]
                    val pStart = length

                    if (listItem != null) {
                        val prefix = listPrefix(listItem)
                        append(prefix)
                        for (k in lineStart until listItem.contentStart) offsets[k] = pStart + prefix.length
                        var i = listItem.contentStart
                        while (i < lineEnd) {
                            offsets[i] = length
                            val formula = formulaAt[i]
                            if (formula == null) {
                                if (!hidden[i]) append(text[i])
                                i++
                                continue
                            }
                            appendFormula(formula, text, fontSizePx, fontAscentPx, lineWidthPx, placeholders, formulas, errors, offsets, renderFormula)
                            i = formula.end
                        }
                    } else {
                        var i = lineStart
                        while (i < lineEnd) {
                            offsets[i] = length
                            val formula = formulaAt[i]
                            if (formula == null) {
                                if (!hidden[i]) append(text[i])
                                i++
                                continue
                            }
                            appendFormula(formula, text, fontSizePx, fontAscentPx, lineWidthPx, placeholders, formulas, errors, offsets, renderFormula)
                            i = formula.end
                        }
                    }

                    offsets[lineEnd] = length
                    val pEnd = length

                    if (listItem != null) {
                        val restLinePx = listPrefixWidthPx(listItem, fontSizePx)
                        addStyle(
                            ParagraphStyle(textIndent = TextIndent(firstLine = 0.sp, restLine = restLinePx.sp)),
                            pStart,
                            pEnd
                        )
                    } else {
                        addStyle(ParagraphStyle(), pStart, pEnd)
                    }

                    if (lineEnd >= text.length) break
                    lineStart = lineEnd + 1
                }
                offsets[text.length] = length
            }

            addStyle(SpanStyle(color = baseColor), 0, length)
            // The spans still open, the innermost last: the size of the code in a heading
            val open = ArrayList<Span>()
            for (span in spans) {
                while (open.isNotEmpty() && open.last().end <= span.start) open.removeAt(open.lastIndex)
                open += span
                if (span.kind == Kind.LIST_ITEM || (span.kind == Kind.CODE_BLOCK && span.start in blockAt)) continue
                val style = contentStyle(span, fontSizePx, monospace) ?: continue
                val start = offsets[span.contentStart]
                val end = offsets[span.contentEnd]
                if (end <= start) continue
                addStyle(style, start, end)
                if (span.kind == Kind.CODE) {
                    val heading = open.lastOrNull { it.kind == Kind.HEADING }
                    inlineCode += InlineCode(start, end, fontSizePx * (heading?.let { headingScale(it.level) } ?: 1f))
                }
            }
            for (error in errors) addStyle(SpanStyle(color = ERROR_COLOR, fontStyle = FontStyle.Italic), error.first, error.last + 1)
        }
        return CanvasText(annotated, placeholders, formulas, codeBlocks, inlineCode)
    }

    private fun <T> AnnotatedString.Builder.appendFormula(
        formula: Span,
        text: String,
        fontSizePx: Float,
        fontAscentPx: Float,
        lineWidthPx: Float?,
        placeholders: MutableList<PlaceholderSpec>,
        formulas: MutableList<InlineFormula<T>>,
        errors: MutableList<IntRange>,
        offsets: IntArray,
        renderFormula: (latex: String, fontSizePx: Float) -> RenderedFormula<T>?
    ) {
        val latex = formulaSource(text, formula)
        val rendered = renderFormula(latex, formulaSizePx(formula, fontSizePx))?.takeIf { it.widthPx > 0f && it.heightPx > 0f }
        when {
            rendered == null -> {
                val start = length
                append(text, formula.start, formula.end)
                errors += start until length
            }
            formula.block && lineWidthPx != null -> {
                val fit = min(1f, lineWidthPx / rendered.widthPx)
                val start = length
                append(OBJECT_REPLACEMENT)
                placeholders += PlaceholderSpec(start, start + 1, lineWidthPx * LINE_FILL, rendered.heightPx * fit, PlaceholderVerticalAlign.Center)
                formulas += InlineFormula(rendered.image, rendered.widthPx * fit, rendered.heightPx * fit, rendered.baselineRatio, placeholders.size - 1, latex, block = true)
            }
            else -> {
                val ascent = rendered.heightPx * rendered.baselineRatio
                val descent = rendered.heightPx - ascent
                val bodyStart = length
                append(OBJECT_REPLACEMENT)
                placeholders += PlaceholderSpec(bodyStart, bodyStart + 1, rendered.widthPx, ascent.coerceAtLeast(1f), PlaceholderVerticalAlign.AboveBaseline)
                formulas += InlineFormula(rendered.image, rendered.widthPx, rendered.heightPx, rendered.baselineRatio, placeholders.size - 1, latex)
                if (descent > 0.5f) {
                    append(WORD_JOINER)
                    val descentStart = length
                    append(OBJECT_REPLACEMENT)
                    placeholders += PlaceholderSpec(descentStart, descentStart + 1, 0.01f, fontAscentPx + descent, PlaceholderVerticalAlign.TextTop)
                }
            }
        }
        for (k in formula.start + 1 until formula.end) offsets[k] = length
    }

    /** The bullet glyph for a list item of [level]. */
    internal fun bulletGlyph(level: Int): String = when (level % 3) {
        0 -> "•"
        1 -> "◦"
        else -> "▪"
    }

    /** The prefix formatted for a list item [span] drawn on the canvas. */
    internal fun listPrefix(span: Span): String {
        val indent = "  ".repeat(span.level)
        return if (span.ordered) {
            "$indent${span.number}. "
        } else {
            "$indent${bulletGlyph(span.level)} "
        }
    }

    /** The approximate width in pixels of the prefix of [span] at [fontSizePx]. */
    internal fun listPrefixWidthPx(span: Span, fontSizePx: Float): Float {
        val indentPx = span.level * 0.7f * fontSizePx
        val markerPx = if (span.ordered) {
            (span.number.toString().length * 0.55f + 0.65f) * fontSizePx
        } else {
            0.95f * fontSizePx
        }
        return indentPx + markerPx
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
