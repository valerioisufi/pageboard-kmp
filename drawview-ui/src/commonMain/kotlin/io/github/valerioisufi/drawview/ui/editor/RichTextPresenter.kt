package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import io.github.valerioisufi.drawview.model.text.RichTextParser.Span
import io.github.valerioisufi.drawview.model.text.code.CodeHighlighter
import io.github.valerioisufi.drawview.ui.render.CodeBlockLayout
import io.github.valerioisufi.drawview.ui.render.CodeBlockSource
import io.github.valerioisufi.drawview.ui.render.CodeTheme
import io.github.valerioisufi.drawview.ui.theme.NotesFonts
import kotlin.math.max
import kotlin.math.min

/**
 * The font of a text, per pixel of its size: the [ascent] and the [descent] of its lines, the
 * [advance] of the object replacement character (the struts drawn in it, see [Struts]).
 */
internal class FontRoom(val ascent: Float, val descent: Float, val advance: Float) {
    internal companion object {
        /** The room of [font], measured by [measurer] (at a size where the rounding is negligible). */
        fun of(measurer: TextMeasurer, font: FontFamily): FontRoom {
            val size = 1000f
            val line = measurer.measure(AnnotatedString(RichTextPresenter.OBJECT), TextStyle(fontSize = size.sp, fontFamily = font, letterSpacing = 0.sp), skipCache = true)
            val baseline = line.getLineBaseline(0)
            return FontRoom((baseline - line.getLineTop(0)) / size, (line.getLineBottom(0) - baseline) / size, line.getBoundingBox(0).width / size)
        }
    }
}

/**
 * The room of the objects of a text in its lines (its formulas, its blocks of code): three
 * characters, `U+FFFC U+2060 U+FFFC`, the first giving its line the room asked above the baseline
 * and the width of the object, the second keeping the line from breaking inside the object, the
 * third giving the room asked below the baseline. Being characters, they are laid out the same way
 * by the canvas and by the field of the editor, which accepts no placeholders.
 *
 * With the strut fonts ([above], [below]: all the room of their line above, or below, the
 * baseline) the room is exact; without them, the characters are in [textFont] at the size that
 * gives the larger of the two rooms ([room]), as large on both sides.
 */
internal class Struts(
    private val above: FontFamily?,
    private val below: FontFamily?,
    private val textFont: FontFamily,
    val room: FontRoom
) {
    /** The styles of the three characters of an object [widthPx] wide, [ascentPx] above and [descentPx] below the baseline. */
    fun styles(widthPx: Float, ascentPx: Float, descentPx: Float, density: Float): List<SpanStyle> {
        fun Float.sp() = (this / density).sp
        val ascent = max(ascentPx, MIN_ROOM_PX)
        val descent = max(descentPx, MIN_ROOM_PX)
        if (above != null && below != null) {
            return listOf(
                strut(above, ascent.sp(), (widthPx - ascent).sp()),
                // In the text font: Skia aborts on a joiner in a font that maps it
                JOINER,
                strut(below, descent.sp(), (-descent).sp())
            )
        }
        val size = max(max(ascent / room.ascent, descent / room.descent), MIN_ROOM_PX)
        return listOf(
            strut(textFont, size.sp(), (widthPx - size * room.advance).sp()),
            JOINER,
            strut(textFont, MIN_ROOM_PX.sp(), (-MIN_ROOM_PX * room.advance).sp())
        )
    }

    private fun strut(font: FontFamily, size: androidx.compose.ui.unit.TextUnit, spacing: androidx.compose.ui.unit.TextUnit) = SpanStyle(
        color = Color.Transparent,
        fontSize = size,
        fontFamily = font,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Normal,
        letterSpacing = spacing,
        textDecoration = TextDecoration.None
    )

    internal companion object {
        private val JOINER = SpanStyle(color = Color.Transparent)

        /** The least size of a strut (a size of zero is no size). */
        const val MIN_ROOM_PX: Float = 0.5f
    }
}

/**
 * How a text is presented: its size [fontSizePx] and [color] ([density] pixels per sp where it is
 * shown), the [struts] of its objects, the width of its lines ([lineWidthPx]: `null` when it has
 * none, its formulas then all in the line and its blocks of code as code in the line), the
 * [monospace] of its code, the colors of the tokens of its code ([highlighter]), its blocks of code
 * laid out by [layoutCodeBlock], the prefixes of its list items measured by [measurePrefix] (`null`:
 * approximated), its formulas rendered by [renderFormula].
 */
internal class Presentation<T>(
    val fontSizePx: Float,
    val color: Color,
    val struts: Struts,
    val density: Float = 1f,
    val lineWidthPx: Float? = null,
    val monospace: FontFamily = FontFamily.Monospace,
    val highlighter: CodeHighlighter? = null,
    val layoutCodeBlock: ((CodeBlockSource) -> CodeBlockLayout)? = null,
    val measurePrefix: ((String) -> Float)? = null,
    val renderFormula: (latex: String, fontSizePx: Float) -> MarkdownTextUtil.RenderedFormula<T>?
)

/**
 * A formula of a presented text, its three characters from [offset]: its [image] ([widthPx] x
 * [heightPx], its baseline at [baselineRatio] of its height), its [latex] as rendered; a [block]
 * formula takes its line, drawn in its middle.
 */
internal class PresentedFormula<T>(
    val offset: Int,
    val image: T,
    val widthPx: Float,
    val heightPx: Float,
    val baselineRatio: Float,
    val latex: String,
    val block: Boolean
)

/**
 * A block of code of a presented text, its three characters from [offset], taking its line: its
 * [layout], drawn [leftPx] from the left of the line (the text of its list item) from the top of the
 * line, its code starting at [codeStart] of the source.
 */
internal class PresentedCodeBlock(val offset: Int, val layout: CodeBlockLayout, val codeStart: Int, val leftPx: Float)

/**
 * A block of code whose code is being written: its code is in the text, laid out as in the block;
 * the rest of the block (its background, label and numbers: [CodeBlockLayout.drawChrome]) is drawn
 * from the top of the line of [offset] (its first line of code), [leftPx] from the left.
 */
internal class WrittenCodeBlock(val offset: Int, val layout: CodeBlockLayout, val leftPx: Float)

/**
 * A text as it is shown (see [RichTextPresenter]): the [text] laid out, the [mapping] of its
 * offsets and those of the source, its [formulas] and [codeBlocks] to draw over their characters,
 * its code in the line ([inlineCode]) and the blocks of code whose source is shown ([codeSources])
 * to draw on a background, the blocks whose code is written ([writtenBlocks]), the formula under
 * the caret ([formulaAtCaret]) whose source is shown.
 */
internal class PresentedText<T>(
    val text: AnnotatedString,
    val mapping: SourceMapping,
    val formulas: List<PresentedFormula<T>>,
    val codeBlocks: List<PresentedCodeBlock>,
    val inlineCode: List<MarkdownTextUtil.InlineCode>,
    val codeSources: List<IntRange>,
    val formulaAtCaret: Span?,
    val writtenBlocks: List<WrittenCodeBlock> = emptyList()
)

/**
 * The offsets of a source and of the text presenting it, both ways: where each offset of the
 * source is shown ([toShown]: a hidden character at the next one shown), and which offset of the
 * source each offset shown stands for ([toSource]: a character added at the offset it goes with).
 */
internal class SourceMapping(private val toShown: IntArray, private val toSource: IntArray) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = toShown[offset.coerceIn(0, toShown.lastIndex)]
    override fun transformedToOriginal(offset: Int): Int = toSource[offset.coerceIn(0, toSource.lastIndex)]
}

/**
 * Presents the texts written with the notation of [RichTextParser], once for every place that
 * shows them: the canvas (the tiles and the export), [io.github.valerioisufi.drawview.ui.components.MarkdownText]
 * and the field of the editor. With nothing revealed, the editor shows the text exactly as the
 * canvas, character by character; the caret reveals the source of what it touches.
 *
 * * Every line of the source is a paragraph, its line break shown as a zero-width space ending it
 *   (Compose adds an empty line around the line break of a paragraph); the last line break of a
 *   text stays one, for the empty line after it.
 * * The delimiters are hidden; shown faded (their color only) when the caret touches their span,
 *   the `#` of a heading only when the caret is on them or right after them.
 * * A formula is an object ([Struts]) drawn over its characters, or its source when the caret
 *   touches it or it cannot be rendered (in red then); alone on its lines, it takes its line. The
 *   source of a formula the caret touches keeps the room of the formula: its line does not move.
 * * A block of code is an object taking its line. With the caret in its code, its code is written
 *   in the text, laid out as in the block (its fences hidden, struts for its top and its bottom);
 *   with the caret on a fence, its source is shown.
 * * A list item shows its bullet or its number instead of its marker, its paragraph indented so
 *   that its wrapped lines start under its text.
 */
internal object RichTextPresenter {

    /** [text] as [presentation] shows it, the source of what [reveal] touches shown (`null`: nothing). */
    fun <T> present(text: String, presentation: Presentation<T>, reveal: TextRange? = null): PresentedText<T> {
        val p = presentation
        val spans = RichTextParser.parse(text)
        val selMin = reveal?.let { min(it.start, it.end) }
        val selMax = reveal?.let { max(it.start, it.end) }
        fun touched(span: Span) = selMin != null && selMax != null && selMin <= span.end && selMax >= span.start
        // The delimiters the caret reveals: those of the span it touches, of a heading only near its #
        fun revealed(span: Span) = if (span.kind == Kind.HEADING) {
            selMin != null && selMax != null && selMin <= span.contentStart && selMax >= span.start
        } else {
            touched(span)
        }

        // What each part of the source becomes
        val hidden = BooleanArray(text.length)
        val formulaAt = HashMap<Int, Pair<Span, MarkdownTextUtil.RenderedFormula<T>>>()
        val blockAt = HashMap<Int, Pair<Span, CodeBlockLayout>>()
        val blockLeft = HashMap<Int, Float>()
        val listItemAt = HashMap<Int, Span>()
        val errors = mutableListOf<Span>()
        val sources = mutableListOf<Span>()
        val written = mutableListOf<Pair<Span, CodeBlockLayout>>()
        // The formulas whose source is shown, with the room they keep
        val keptRoomAt = HashMap<Int, Pair<Span, MarkdownTextUtil.RenderedFormula<T>>>()
        var atCaret: Span? = null
        for (span in spans) {
            val touched = touched(span)
            when (span.kind) {
                Kind.LIST_ITEM -> listItemAt[span.start] = span
                Kind.CODE_BLOCK -> {
                    val layout = p.layoutCodeBlock
                    // The caret on a fence: the end of the opening line, or from the start of the closing one
                    val onFence = touched && (selMin!! < span.contentStart || selMax!! > span.contentEnd && span.contentEnd < span.end)
                    when {
                        !touched && layout != null && p.lineWidthPx != null -> {
                            val left = codeBlockLeft(span, p)
                            blockLeft[span.start] = left
                            blockAt[span.start] = span to layout(codeBlockSource(text, span, p.lineWidthPx - left))
                        }
                        touched && !onFence && layout != null && p.lineWidthPx != null && span.contentStart < span.contentEnd -> {
                            // Its code written as in the block, the fences hidden
                            val left = codeBlockLeft(span, p)
                            blockLeft[span.start] = left
                            written += span to layout(codeBlockSource(text, span, p.lineWidthPx - left))
                            sources += span
                            for (k in span.start until span.contentStart) hidden[k] = true
                            for (k in span.contentEnd until span.end) hidden[k] = true
                        }
                        touched -> sources += span
                        else -> for (delimiter in span.delimiters) for (i in delimiter) hidden[i] = true
                    }
                }
                Kind.FORMULA -> {
                    val rendered = p.renderFormula(MarkdownTextUtil.formulaSource(text, span), MarkdownTextUtil.formulaSizePx(span, p.fontSizePx))
                        ?.takeIf { it.widthPx > 0f && it.heightPx > 0f }
                    if (touched) {
                        if (atCaret == null) atCaret = span
                        if (rendered != null) keptRoomAt[span.start] = span to rendered
                        continue
                    }
                    if (rendered == null) {
                        errors += span
                        continue
                    }
                    formulaAt[span.start] = span to rendered
                    // A formula alone on its lines takes its line: the spaces around it go
                    if (span.block && p.lineWidthPx != null) {
                        var i = span.start - 1
                        while (i >= 0 && text[i] != '\n' && text[i].isWhitespace()) hidden[i--] = true
                        i = span.end
                        while (i < text.length && text[i] != '\n' && text[i].isWhitespace()) hidden[i++] = true
                    }
                }
                else -> if (!revealed(span)) for (delimiter in span.delimiters) for (i in delimiter) hidden[i] = true
            }
        }
        val writtenSpans = written.map { it.first }.toHashSet()

        val shown = Shown(text.length)
        val formulas = mutableListOf<PresentedFormula<T>>()
        val codeBlocks = mutableListOf<PresentedCodeBlock>()
        // The struts of the objects, styled last over every other style
        class Room(val offset: Int, val widthPx: Float, val ascentPx: Float, val descentPx: Float)
        val rooms = mutableListOf<Room>()
        val lineWidth = p.lineWidthPx ?: 0f
        val writtenBlocks = mutableListOf<WrittenCodeBlock>()
        /** The struts of a block whose code is written: the room of its bottom, after its last line. */
        fun bottomOf(span: Span, layout: CodeBlockLayout) {
            val offset = shown.insert(OBJECT_CHARS, span.contentEnd)
            val code = layout.code
            rooms += Room(offset, 0f, 0f, layout.heightPx - layout.codeTopPx - code.getLineBaseline(code.lineCount - 1))
        }
        // The column of the code of a block being written, for its tabs
        var column = 0

        var i = 0
        var lineStart = true
        while (i < text.length) {
            val writing = written.firstOrNull { (span, _) -> i >= span.contentStart && i <= span.contentEnd }
            if (writing != null && i == writing.first.contentEnd) bottomOf(writing.first, writing.second)
            if (lineStart && writing != null && i < writing.first.contentEnd) {
                lineStart = false
                val (span, layout) = writing
                val left = blockLeft.getValue(span.start)
                if (i == span.contentStart) {
                    // The top of the block above its first line of code, the rest of the block drawn from there
                    val offset = shown.insert(OBJECT_CHARS, i)
                    rooms += Room(offset, 0f, layout.codeTopPx + layout.code.getLineBaseline(0), 0f)
                    writtenBlocks += WrittenCodeBlock(offset, layout, left)
                }
                // The spaces of its list item taken off, the line at the code of the block, wrapped under its indent
                var k = i
                while (k < i + span.level && k < text.length && text[k] == ' ') shown.hide(k++)
                column = 0
                var leading = 0
                var m = k
                while (m < text.length && (text[m] == ' ' || text[m] == '\t')) {
                    leading = if (text[m] == '\t') (leading / TAB_WIDTH + 1) * TAB_WIDTH else leading + 1
                    m++
                }
                val first = left + layout.codeLeftPx
                shown.paragraph = ParagraphStyle(textIndent = TextIndent(firstLine = (first / p.density).sp, restLine = ((first + leading * layout.advancePx) / p.density).sp))
                i = k
                continue
            }
            if (lineStart) {
                lineStart = false
                val item = listItemAt[i]
                if (item != null) {
                    // The bullet or the number instead of the marker, the wrapped lines under the text
                    val prefix = MarkdownTextUtil.listPrefix(item)
                    shown.insert(prefix, item.contentStart)
                    for (k in i until item.contentStart) shown.hide(k)
                    shown.paragraph = ParagraphStyle(textIndent = TextIndent(firstLine = 0.sp, restLine = (prefixWidth(prefix, item, p) / p.density).sp))
                    i = item.contentStart
                    continue
                }
            }
            val block = blockAt[i]
            if (block != null) {
                val (span, layout) = block
                val offset = shown.obj(span.start, span.end)
                val descent = p.struts.room.descent * p.fontSizePx
                rooms += Room(offset, lineWidth * LINE_FILL, layout.heightPx - descent, descent)
                codeBlocks += PresentedCodeBlock(offset, layout, span.contentStart, blockLeft.getValue(span.start))
                i = span.end
                continue
            }
            val formula = formulaAt[i]
            if (formula != null) {
                val (span, rendered) = formula
                val offset = shown.obj(span.start, span.end)
                val latex = MarkdownTextUtil.formulaSource(text, span)
                if (span.block && p.lineWidthPx != null) {
                    val fit = min(1f, p.lineWidthPx / rendered.widthPx)
                    val height = rendered.heightPx * fit
                    rooms += Room(offset, lineWidth * LINE_FILL, height * rendered.baselineRatio, height * (1f - rendered.baselineRatio))
                    formulas += PresentedFormula(offset, rendered.image, rendered.widthPx * fit, height, rendered.baselineRatio, latex, block = true)
                } else {
                    rooms += Room(offset, rendered.widthPx, rendered.heightPx * rendered.baselineRatio, rendered.heightPx * (1f - rendered.baselineRatio))
                    formulas += PresentedFormula(offset, rendered.image, rendered.widthPx, rendered.heightPx, rendered.baselineRatio, latex, block = false)
                }
                i = span.end
                continue
            }
            val kept = keptRoomAt[i]
            if (kept != null) {
                // The room of the formula before its source: its line keeps its height
                val (span, rendered) = kept
                val fit = if (span.block && p.lineWidthPx != null) min(1f, p.lineWidthPx / rendered.widthPx) else 1f
                val height = rendered.heightPx * fit
                val offset = shown.insert(OBJECT_CHARS, i)
                rooms += Room(offset, 0f, height * rendered.baselineRatio, height * (1f - rendered.baselineRatio))
            }
            val c = text[i]
            if (writing != null && c == '\t' && !hidden[i]) {
                // A tab as the spaces up to the next column of the code, as the block draws it
                val spaces = TAB_WIDTH - column % TAB_WIDTH
                shown.keep(i, ' ')
                shown.insert(" ".repeat(spaces - 1), i + 1)
                column += spaces
                i++
                continue
            }
            if (writing != null && c != '\n' && !hidden[i]) column++
            when {
                c == '\n' && hidden[i] -> shown.hide(i)
                c == '\n' && i == text.lastIndex -> shown.keep(i, '\n')
                c == '\n' -> {
                    shown.keep(i, LINE_BREAK)
                    shown.endParagraph()
                }
                hidden[i] -> shown.hide(i)
                else -> shown.keep(i, c)
            }
            if (c == '\n') lineStart = true
            i++
        }
        // A block written at the end of the text, without its closing fence
        written.firstOrNull { (span, _) -> span.contentEnd == text.length }?.let { (span, layout) -> bottomOf(span, layout) }
        val mapping = shown.finish()

        // The styles, mapped on what is shown
        val out = shown.builder
        fun style(style: SpanStyle, start: Int, end: Int) {
            val from = mapping.originalToTransformed(start)
            val to = mapping.originalToTransformed(end)
            if (to > from) out.addStyle(style, from, to)
        }
        out.addStyle(SpanStyle(color = p.color), 0, out.length)
        // At the size of their text: the line does not change height
        val faded = SpanStyle(color = p.color.copy(alpha = p.color.alpha * FADED_ALPHA))
        val inlineCode = mutableListOf<MarkdownTextUtil.InlineCode>()
        val codeSources = mutableListOf<IntRange>()
        val errorSpans = errors.toHashSet()
        // The spans still open, the innermost last: the size of the code in a heading
        val open = ArrayList<Span>()
        for (span in spans) {
            while (open.isNotEmpty() && open.last().end <= span.start) open.removeAt(open.lastIndex)
            open += span
            if (span.kind == Kind.LIST_ITEM || span.start in blockAt && span.kind == Kind.CODE_BLOCK) continue
            if (span.kind == Kind.FORMULA && (span.start in formulaAt || span in errorSpans)) continue
            if (revealed(span)) for (delimiter in span.delimiters) style(faded, delimiter.first, delimiter.last + 1)
            val content = when (span.kind) {
                Kind.FORMULA -> SpanStyle(fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif, color = FORMULA_COLOR)
                else -> MarkdownTextUtil.contentStyle(span, p.fontSizePx / p.density, p.monospace)
            }
            if (content != null) style(content, span.contentStart, span.contentEnd)
            if (span.kind == Kind.CODE) {
                val heading = open.lastOrNull { it.kind == Kind.HEADING }
                val from = mapping.originalToTransformed(span.contentStart)
                val to = mapping.originalToTransformed(span.contentEnd)
                if (to > from) inlineCode += MarkdownTextUtil.InlineCode(from, to, p.fontSizePx * (heading?.let { MarkdownTextUtil.headingScale(it.level) } ?: 1f))
            }
        }
        for (span in errors) style(SpanStyle(color = ERROR_COLOR, fontStyle = FontStyle.Italic), span.start, span.end)
        // The source of a block of the caret in the colors of its language, on its background
        val theme = CodeTheme.of(p.color)
        for (span in sources) {
            val code = text.substring(span.contentStart, span.contentEnd)
            for (token in CodeBlockLayout.tokens(p.highlighter, code, span.language)) {
                style(SpanStyle(color = theme.color(token.kind)), span.contentStart + token.start, span.contentStart + token.end)
            }
            if (span !in writtenSpans) codeSources += mapping.originalToTransformed(span.start) until mapping.originalToTransformed(span.end)
        }
        // The code being written in the font of the code from its top to the end of its last line,
        // its line breaks and joiners included: its lines are as high as those of the block
        for ((block, written) in written.map { it.first }.zip(writtenBlocks)) {
            val codeStyle = MarkdownTextUtil.contentStyle(block, p.fontSizePx / p.density, p.monospace) ?: continue
            val end = mapping.originalToTransformed(block.end) + if (block.end < text.length && text[block.end] == '\n') 1 else 0
            if (end > written.offset) out.addStyle(codeStyle, written.offset, end)
        }
        for (room in rooms) {
            for ((k, strut) in p.struts.styles(room.widthPx, room.ascentPx, room.descentPx, p.density).withIndex()) {
                out.addStyle(strut, room.offset + k, room.offset + k + 1)
            }
        }
        return PresentedText(out.toAnnotatedString(), mapping, formulas, codeBlocks, inlineCode, codeSources, atCaret, writtenBlocks)
    }

    /**
     * The text being shown and the offsets of its source: each character of the source kept,
     * hidden, or replaced by an object; characters inserted; its paragraphs.
     */
    private class Shown(private val sourceLength: Int) {
        val builder = AnnotatedString.Builder()
        private val toShown = IntArray(sourceLength + 1)
        private val toSource = ArrayList<Int>()

        /** The style of the paragraph being shown (`null`: the default one). */
        var paragraph: ParagraphStyle? = null
        private var paragraphStart = 0

        /** The character [i] of the source, shown as [c]. */
        fun keep(i: Int, c: Char) {
            toShown[i] = builder.length
            toSource += i
            builder.append(c)
        }

        /** The character [i] of the source, not shown: at the next character shown. */
        fun hide(i: Int) {
            toShown[i] = builder.length
        }

        /** [s], standing for the offset [at] of the source: where it starts. */
        fun insert(s: String, at: Int): Int {
            val offset = builder.length
            repeat(s.length) { toSource += at }
            builder.append(s)
            return offset
        }

        /** The source from [start] to [end] shown as the three characters of an object: where they start. */
        fun obj(start: Int, end: Int): Int {
            val offset = builder.length
            for (k in start until end) toShown[k] = offset
            toSource += start
            toSource += end
            toSource += end
            builder.append(OBJECT)
            builder.append(WORD_JOINER)
            builder.append(OBJECT)
            return offset
        }

        /** Ends the paragraph being shown, after its line break. */
        fun endParagraph() {
            builder.addStyle(paragraph ?: ParagraphStyle(), paragraphStart, builder.length)
            paragraphStart = builder.length
            paragraph = null
        }

        /** The last paragraph ended, the offsets of the source and of the text shown. */
        fun finish(): SourceMapping {
            if (builder.length > paragraphStart) endParagraph()
            toShown[sourceLength] = builder.length
            toSource += sourceLength
            return SourceMapping(toShown, toSource.toIntArray())
        }
    }

    /** The width of the [prefix] of the list item [item]: measured, or approximated. */
    private fun prefixWidth(prefix: String, item: Span, p: Presentation<*>): Float =
        p.measurePrefix?.invoke(prefix) ?: MarkdownTextUtil.listPrefixWidthPx(item, p.fontSizePx)

    /**
     * Where the block of code [span] starts from the left of its line: at the text of its list
     * item, the block keeping half the line at least.
     */
    private fun codeBlockLeft(span: Span, p: Presentation<*>): Float =
        span.listItem?.let { item ->
            val prefix = MarkdownTextUtil.listPrefix(item)
            min(prefixWidth(prefix, item, p), (p.lineWidthPx ?: 0f) / 2f)
        } ?: 0f

    /** What the block of code [span] of [text] is laid out from, [widthPx] wide. */
    private fun codeBlockSource(text: String, span: Span, widthPx: Float) =
        CodeBlockSource(text.substring(span.contentStart, span.contentEnd), span.language, span.level, span.numbered, widthPx)

    /** The object replacement character: the struts of an object. */
    const val OBJECT: String = "￼"

    /** The word joiner between the struts of an object: no line break inside it. */
    const val WORD_JOINER: String = "⁠"

    /** The zero-width space ending a paragraph, standing for its line break. */
    const val LINE_BREAK: Char = '​'

    /** The three characters of the struts of an object. */
    private const val OBJECT_CHARS: String = OBJECT + WORD_JOINER + OBJECT

    /** The columns of a tab in a block of code (see [CodeBlockLayout]). */
    private const val TAB_WIDTH = 4

    /** Whether [c] stands for no text: a strut, a joiner, a line break shown as a zero-width space. */
    fun isInvisible(c: Char): Boolean = c == OBJECT[0] || c == WORD_JOINER[0] || c == LINE_BREAK

    /** Share of the line an object taking its line takes: all of it, without wrapping on a rounding. */
    private const val LINE_FILL = 0.999f

    /** The delimiters near the caret: faded. */
    private const val FADED_ALPHA = 0.45f

    private val FORMULA_COLOR = Color(0xFF1E88E5)
    private val ERROR_COLOR = Color(0xFFD32F2F)
}

/**
 * Where [formula] is drawn in this layout: its baseline on the baseline of its line; a formula
 * taking its line in the middle of the width.
 */
internal fun TextLayoutResult.formulaRect(formula: PresentedFormula<*>): Rect {
    val line = getLineForOffset(formula.offset)
    val left = if (formula.block) (size.width - formula.widthPx) / 2f else getHorizontalPosition(formula.offset, usePrimaryDirection = true)
    val top = getLineBaseline(line) - formula.heightPx * formula.baselineRatio
    return Rect(left, top, left + formula.widthPx, top + formula.heightPx)
}

/** Where the top-left corner of [block] is drawn in this layout: at the top of its line. */
internal fun TextLayoutResult.codeBlockOrigin(block: PresentedCodeBlock): Offset =
    Offset(block.leftPx, getLineTop(getLineForOffset(block.offset)))

/** The struts of the texts in these fonts, the room of their text measured by [measurer]. */
internal fun NotesFonts.struts(measurer: TextMeasurer): Struts = Struts(strutAbove, strutBelow, text, FontRoom.of(measurer, text))
