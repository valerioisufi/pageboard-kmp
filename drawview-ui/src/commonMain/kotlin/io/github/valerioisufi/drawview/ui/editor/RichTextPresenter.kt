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
import io.github.valerioisufi.drawview.model.text.BlockQuote
import io.github.valerioisufi.drawview.model.text.ListNumbering
import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import io.github.valerioisufi.drawview.model.text.RichTextParser.Span
import io.github.valerioisufi.drawview.model.text.TextSymbols
import io.github.valerioisufi.drawview.model.text.code.CodeHighlighter
import io.github.valerioisufi.drawview.ui.render.CodeChip
import io.github.valerioisufi.drawview.ui.render.CodeBlockLayout
import io.github.valerioisufi.drawview.ui.render.CodeBlockSource
import io.github.valerioisufi.drawview.ui.render.CodeTheme
import io.github.valerioisufi.drawview.ui.render.TableLayout
import io.github.valerioisufi.drawview.model.TextAlignment
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

/** How far from the left a block quote starts its text, relative to the size of the text. */
internal const val QUOTE_INDENT: Float = 0.9f

/** How wide the bar of a block quote is, relative to the size of the text. */
private const val QUOTE_BAR_WIDTH: Float = 0.15f

/** How opaque the bar of a block quote is, of the color of its text. */
internal const val QUOTE_BAR_ALPHA: Float = 0.3f

/**
 * The bars of the block [quotes] of a text in this layout, at the size [fontSizePx] of the text: at
 * the left of the box, from the top of the first line of each one to the bottom of its last. They
 * take no room; the canvas, the editor and the export draw them the same way.
 */
internal fun TextLayoutResult.quoteBars(quotes: List<IntRange>, fontSizePx: Float): List<CodeChip> {
    if (quotes.isEmpty()) return emptyList()
    val length = layoutInput.text.length
    val width = fontSizePx * QUOTE_BAR_WIDTH
    return quotes.map { quote ->
        val top = getLineTop(getLineForOffset(quote.first.coerceIn(0, length)))
        val bottom = getLineBottom(getLineForOffset(quote.last.coerceIn(0, length)))
        CodeChip(Rect(0f, top, width, bottom), width / 2f)
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
 * approximated), the cells of its tables laid out by [measureText] (in the style of the text, on a
 * width or on one line; `null`: the tables shown as their source), the color of its paper
 * ([paperColor], ARGB, of no alpha when not known), its symbols in [symbols] (`null`: in the fonts
 * of each system), its block quotes shown as such when [quotes] (not in the cells of a table, whose
 * lines are not lines of a text), its formulas rendered by [renderFormula].
 */
internal data class Presentation<T>(
    val fontSizePx: Float,
    val color: Color,
    val struts: Struts,
    val density: Float = 1f,
    val lineWidthPx: Float? = null,
    val monospace: FontFamily = FontFamily.Monospace,
    val highlighter: CodeHighlighter? = null,
    val layoutCodeBlock: ((CodeBlockSource) -> CodeBlockLayout)? = null,
    val measurePrefix: ((String) -> Float)? = null,
    val measureText: ((text: AnnotatedString, widthPx: Float?, alignment: TextAlignment) -> TextLayoutResult)? = null,
    val paperColor: Int = 0,
    val symbols: FontFamily? = null,
    val quotes: Boolean = true,
    val renderFormula: (latex: String, fontSizePx: Float) -> MarkdownTextUtil.RenderedFormula<T>?
)

/** A table of a presented text, its three characters from [offset], taking its line: its [layout], drawn from the left of the line. */
internal class PresentedTable<T>(val offset: Int, val layout: TableLayout<T>, val span: Span)

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
 * the caret ([formulaAtCaret]) whose source is shown, the block quotes ([quotes]: the part of the
 * text shown of each one) to draw a bar at their left.
 */
internal class PresentedText<T>(
    val text: AnnotatedString,
    val mapping: SourceMapping,
    val formulas: List<PresentedFormula<T>>,
    val codeBlocks: List<PresentedCodeBlock>,
    val inlineCode: List<MarkdownTextUtil.InlineCode>,
    val codeSources: List<IntRange>,
    val formulaAtCaret: Span?,
    val writtenBlocks: List<WrittenCodeBlock> = emptyList(),
    val tables: List<PresentedTable<T>> = emptyList(),
    val quotes: List<IntRange> = emptyList()
)

/**
 * The offsets of a source and of the text presenting it, both ways: where each offset of the
 * source is shown ([toShown]: a hidden character at the next one shown), and which offset of the
 * source each offset shown stands for ([toSource]: a character added at the offset it goes with);
 * the offsets where the caret may not stand ([noCaret]: in a marker of a line, a heading or an
 * escape, not shown) and those inside an object (a formula, a block of code, a table: [inObject]).
 */
internal class SourceMapping(
    private val toShown: IntArray,
    private val toSource: IntArray,
    private val noCaret: BooleanArray = BooleanArray(toShown.size),
    private val inObject: BooleanArray = BooleanArray(toShown.size)
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = toShown[offset.coerceIn(0, toShown.lastIndex)]
    override fun transformedToOriginal(offset: Int): Int = toSource[offset.coerceIn(0, toSource.lastIndex)]

    /** The length of the source. */
    val sourceLength: Int get() = toShown.lastIndex

    /** Whether the caret may stand at [offset] of the source: not in a marker that is not shown. */
    fun caretMayStand(offset: Int): Boolean = !noCaret[offset.coerceIn(0, noCaret.lastIndex)]

    /** Whether [offset] of the source is inside an object, which the caret moves in by its own rules. */
    fun insideObject(offset: Int): Boolean = inObject[offset.coerceIn(0, inObject.lastIndex)]
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
 * * The delimiters are hidden, with the caret too: the emphasis, the code in the line, the escapes,
 *   the `#` of a heading; the caret never stands in a marker or between an escape and what it
 *   escapes ([SourceMapping.caretMayStand]). The address of a link is shown, faded (its color
 *   only), when the caret touches the link.
 * * A formula is an object ([Struts]) drawn over its characters, or its source when the caret
 *   touches it or it cannot be rendered (in red then); alone on its lines, it takes its line. The
 *   source of a formula the caret touches keeps the room of the formula: its line does not move.
 * * A block of code is an object taking its line. With the caret in its code, its code is written
 *   in the text, laid out as in the block (its fences hidden, struts for its top and its bottom);
 *   with the caret on a fence, its source is shown.
 * * A list item shows its label (see [ListNumbering]) instead of its marker, right-aligned in the
 *   column of the labels of its list, its paragraph indented so that its wrapped lines and its
 *   continuation lines start under its text; a nested item starts at the text of its parent.
 * * A line of a block quote has its marker hidden, also under the caret, and its paragraph indented
 *   from the bar drawn at the left of the quote ([quoteBars]); a list in a quote starts there.
 */
internal object RichTextPresenter {

    /** [text] as [presentation] shows it, the source of what [reveal] touches shown (`null`: nothing). */
    fun <T> present(text: String, presentation: Presentation<T>, reveal: TextRange? = null): PresentedText<T> {
        val p = presentation
        val spans = RichTextParser.parse(text)
        val selMin = reveal?.let { min(it.start, it.end) }
        val selMax = reveal?.let { max(it.start, it.end) }
        fun touched(span: Span) = selMin != null && selMax != null && selMin <= span.end && selMax >= span.start
        // The delimiters the caret reveals: only the address of a link it touches (see
        // docs/text-fidelity.md); the emphasis, the code in the line, the escapes and the headings
        // never, so that their lines do not move with the caret
        fun revealed(span: Span) = span.kind == Kind.LINK && touched(span)

        // What each part of the source becomes
        val hidden = BooleanArray(text.length)
        // The offsets where the caret may not stand: in a marker that is not shown
        val noCaret = BooleanArray(text.length + 1)
        val formulaAt = HashMap<Int, Pair<Span, MarkdownTextUtil.RenderedFormula<T>>>()
        val blockAt = HashMap<Int, Pair<Span, CodeBlockLayout>>()
        val blockLeft = HashMap<Int, Float>()
        // The list items where their lines start, laid out in their columns
        val lists = listLayout(text, spans, p)
        // The lines of the block quotes, by their start: the length of their markers
        // A `>` alone with the caret right after it may be the start of `>=` or `>5`: it stays as typed
        fun beingTyped(quote: Span) = selMax != null && quote.start == quote.contentStart - 1 && quote.end == quote.contentStart && selMax == quote.end
        val quoteLines = if (p.quotes) quoteLinesOf(text, spans.filterNot(::beingTyped)) else emptyMap()
        val quoteLeft = QUOTE_INDENT * p.fontSizePx
        // The tables laid out as grids (the spans in them are presented by their cells)
        val tableAt = HashMap<Int, Pair<Span, TableLayout<T>>>()
        for (span in spans) {
            if (span.kind == Kind.TABLE) TableLayout.layout(text, span, p, reveal)?.let { tableAt[span.start] = span to it }
        }
        var tableEnd = -1
        val errors = mutableListOf<Span>()
        val sources = mutableListOf<Span>()
        val written = mutableListOf<Pair<Span, CodeBlockLayout>>()
        // The formulas whose source is shown, with the room they keep
        val keptRoomAt = HashMap<Int, Pair<Span, MarkdownTextUtil.RenderedFormula<T>>>()
        var atCaret: Span? = null
        for (span in spans) {
            if (span.start in tableAt) tableEnd = span.end
            if (span.start < tableEnd && span.kind != Kind.TABLE) continue
            val touched = touched(span)
            when (span.kind) {
                Kind.LIST_ITEM, Kind.QUOTE -> Unit
                Kind.CODE_BLOCK -> {
                    val layout = p.layoutCodeBlock
                    // The caret on a fence: the end of the opening line, or from the start of the closing one
                    val onFence = touched && (selMin!! < span.contentStart || selMax!! > span.contentEnd && span.contentEnd < span.end)
                    when {
                        !touched && layout != null && p.lineWidthPx != null -> {
                            val left = codeBlockLeft(span, p, lists)
                            blockLeft[span.start] = left
                            blockAt[span.start] = span to layout(codeBlockSource(text, span, p.lineWidthPx - left))
                        }
                        touched && !onFence && layout != null && p.lineWidthPx != null && span.contentStart < span.contentEnd -> {
                            // Its code written as in the block, the fences hidden
                            val left = codeBlockLeft(span, p, lists)
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
                else -> if (!revealed(span)) {
                    for (delimiter in span.delimiters) for (i in delimiter) hidden[i] = true
                    // The caret stands after the marker of a heading, never in it, and never
                    // between an escape and the character it escapes
                    when (span.kind) {
                        Kind.HEADING -> for (i in span.start until span.contentStart) noCaret[i] = true
                        Kind.ESCAPE -> for (delimiter in span.delimiters) for (i in delimiter) if (i + 1 <= text.length) noCaret[i + 1] = true
                        else -> Unit
                    }
                }
            }
        }
        val writtenSpans = written.map { it.first }.toHashSet()

        val shown = Shown(text.length, noCaret)
        val formulas = mutableListOf<PresentedFormula<T>>()
        val codeBlocks = mutableListOf<PresentedCodeBlock>()
        val tables = mutableListOf<PresentedTable<T>>()
        // The struts of the objects, styled last over every other style
        class Room(val offset: Int, val widthPx: Float, val ascentPx: Float, val descentPx: Float)
        val rooms = mutableListOf<Room>()
        // The labels of the list items, where they are shown
        val labels = mutableListOf<Pair<IntRange, ListItemLayout>>()
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
        // Where the last object taking its line ends in the source
        var objectEnd = -1
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
                while (k < i + span.level && k < text.length && text[k] == ' ') shown.hideMarker(k++)
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
                val marker = quoteLines[i]
                if (marker != null) {
                    for (k in i until i + marker) shown.hideMarker(k)
                    i += marker
                    val left = (quoteLeft / p.density).sp
                    shown.paragraph = ParagraphStyle(textIndent = TextIndent(firstLine = left, restLine = left))
                    // An empty line of a quote: its paragraph, so that the caret stands at its text
                    if (i >= text.length || text[i] == '\n') rooms += Room(shown.insert(OBJECT_CHARS, i), 0f, 0f, 0f)
                    if (i >= text.length) break
                }
                val item = lists.itemAt[i]
                if (item != null) {
                    // The label in its column instead of the marker, the wrapped lines under the text
                    val leading = shown.insert(OBJECT_CHARS, item.span.contentStart)
                    rooms += Room(leading, item.labelLeftPx, 0f, 0f)
                    val label = shown.insert(item.label, item.span.contentStart)
                    labels += label until label + item.label.length to item
                    val gap = shown.insert(OBJECT_CHARS, item.span.contentStart)
                    rooms += Room(gap, item.textLeftPx - item.labelLeftPx - item.labelWidthPx, 0f, 0f)
                    for (k in i until item.span.contentStart) shown.hideMarker(k)
                    shown.paragraph = ParagraphStyle(textIndent = TextIndent(firstLine = 0.sp, restLine = (item.textLeftPx / p.density).sp))
                    i = item.span.contentStart
                    continue
                }
                val continued = lists.continuationAt[i]
                if (continued != null) {
                    // A line break in the item: the line at its text, without a marker
                    var k = i
                    while (k < text.length && (text[k] == ' ' || text[k] == '\t')) shown.hideMarker(k++)
                    val left = (continued.textLeftPx / p.density).sp
                    shown.paragraph = ParagraphStyle(textIndent = TextIndent(firstLine = left, restLine = left))
                    i = k
                    continue
                }
            }
            val table = tableAt[i]
            if (table != null) {
                val (span, layout) = table
                val offset = shown.obj(span.start, span.end)
                val descent = p.struts.room.descent * p.fontSizePx
                rooms += Room(offset, lineWidth * LINE_FILL, layout.heightPx - descent, descent)
                tables += PresentedTable(offset, layout, span)
                objectEnd = span.end
                i = span.end
                continue
            }
            val block = blockAt[i]
            if (block != null) {
                val (span, layout) = block
                val offset = shown.obj(span.start, span.end)
                val descent = p.struts.room.descent * p.fontSizePx
                rooms += Room(offset, lineWidth * LINE_FILL, layout.heightPx - descent, descent)
                codeBlocks += PresentedCodeBlock(offset, layout, span.contentStart, blockLeft.getValue(span.start))
                objectEnd = span.end
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
                c == '\n' && i == text.lastIndex && i == objectEnd -> {
                    // The empty line after a line break takes the room of its paragraph, an object's:
                    // the object's paragraph ends, the empty line is one of its own
                    shown.keep(i, LINE_BREAK)
                    shown.endParagraph()
                    shown.insert(LINE_BREAK.toString(), i + 1)
                }
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
            if (span.kind == Kind.LIST_ITEM || span.kind == Kind.QUOTE || span.start in blockAt && span.kind == Kind.CODE_BLOCK) continue
            if (span.kind == Kind.FORMULA && (span.start in formulaAt || span in errorSpans)) continue
            // Faded where they are shown: the address of a link, the fences of a block whose source is shown
            if (revealed(span) || span.kind == Kind.CODE_BLOCK && span in sources && span !in writtenSpans) for (delimiter in span.delimiters) style(faded, delimiter.first, delimiter.last + 1)
            val content = when (span.kind) {
                Kind.FORMULA -> SpanStyle(fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif, color = FORMULA_COLOR)
                else -> MarkdownTextUtil.contentStyle(span, p.fontSizePx / p.density, p.monospace)
            }
            if (content != null) {
                val combined = if (content.textDecoration != null && open.any { it.kind == Kind.STRIKE } && open.any { it.kind == Kind.LINK }) {
                    content.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.combine(listOf(androidx.compose.ui.text.style.TextDecoration.Underline, androidx.compose.ui.text.style.TextDecoration.LineThrough)))
                } else {
                    content
                }
                style(combined, span.contentStart, span.contentEnd)
            }
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
        symbolsIn(text, spans, p.symbols) { index -> style(SpanStyle(fontFamily = p.symbols), index, index + 1) }
        // The bullets in the monospace font, which has all three, small enough for the lines of the
        // text to keep their height (a shift of the baseline would make them higher)
        val bulletScale = bulletScale(p)
        val bulletSize = if (bulletScale < 1f) (p.fontSizePx * bulletScale / p.density).sp else androidx.compose.ui.unit.TextUnit.Unspecified
        for ((range, item) in labels) {
            if (item.bullet != null) out.addStyle(SpanStyle(fontFamily = p.monospace, fontSize = bulletSize), range.first, range.last + 1)
        }
        for (room in rooms) {
            for ((k, strut) in p.struts.styles(room.widthPx, room.ascentPx, room.descentPx, p.density).withIndex()) {
                out.addStyle(strut, room.offset + k, room.offset + k + 1)
            }
        }
        val quotes = if (!p.quotes) emptyList() else spans.filter { it.kind == Kind.QUOTE && !beingTyped(it) }.map { quote ->
            mapping.originalToTransformed(quote.start) until max(mapping.originalToTransformed(quote.end), mapping.originalToTransformed(quote.start) + 1)
        }
        return PresentedText(out.toAnnotatedString(), mapping, formulas, codeBlocks, inlineCode, codeSources, atCaret, writtenBlocks, tables, quotes)
    }

    /**
     * The text being shown and the offsets of its source: each character of the source kept,
     * hidden, or replaced by an object; characters inserted; its paragraphs.
     */
    private class Shown(private val sourceLength: Int, private val noCaret: BooleanArray) {
        val builder = AnnotatedString.Builder()
        private val toShown = IntArray(sourceLength + 1)
        private val toSource = ArrayList<Int>()
        private val inObject = BooleanArray(sourceLength + 1)

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

        /** The character [i] of a marker of a line (a list item, a quote), not shown: the caret is never before it. */
        fun hideMarker(i: Int) {
            hide(i)
            noCaret[i] = true
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
            for (k in start + 1 until end) inObject[k] = true
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
            return SourceMapping(toShown, toSource.toIntArray(), noCaret, inObject)
        }
    }

    /**
     * The list items of [text] laid out (see [ListNumbering]): each label right-aligned in the
     * column of the labels of its list, the text after a gap, a nested item at the text of its
     * parent.
     */
    private fun listLayout(text: String, spans: List<Span>, p: Presentation<*>): ListLayout {
        val items = ListNumbering.items(text, spans)
        if (items.isEmpty()) return ListLayout.EMPTY
        fun widthOf(item: ListNumbering.Item): Float = if (item.bullet != null) {
            BULLET_ADVANCE * bulletScale(p) * p.fontSizePx
        } else {
            p.measurePrefix?.invoke(item.label) ?: (item.label.length * ORDINAL_ADVANCE * p.fontSizePx)
        }
        val widths = FloatArray(items.size) { widthOf(items[it]) }
        // The column of the labels of each list: its widest label
        val columns = HashMap<Int, Float>()
        for ((index, item) in items.withIndex()) columns[item.list] = max(columns[item.list] ?: 0f, widths[index])
        val gap = LABEL_GAP * p.fontSizePx
        val laidOut = ArrayList<ListItemLayout>(items.size)
        for ((index, item) in items.withIndex()) {
            val outer = if (p.quotes && BlockQuote.isQuotedAt(text, item.span.start)) QUOTE_INDENT * p.fontSizePx else 0f
            val left = if (item.parent >= 0) laidOut[item.parent].textLeftPx else outer
            val column = columns.getValue(item.list)
            // At most half the line to the text, so that the text keeps room
            val textLeft = min(left + column + gap, (p.lineWidthPx ?: Float.MAX_VALUE) / 2f)
            val labelLeft = (textLeft - gap - widths[index]).coerceAtLeast(0f)
            laidOut += ListItemLayout(item.span, item.label, item.bullet, labelLeft, widths[index], textLeft)
        }
        val itemAt = HashMap<Int, ListItemLayout>()
        val continuationAt = HashMap<Int, ListItemLayout>()
        for (item in laidOut) {
            itemAt[item.span.start] = item
            var k = text.indexOf('\n', item.span.contentStart)
            while (k in 0 until item.span.end) {
                // In a quote, where the text of the line starts: its marker is hidden before
                val marker = if (p.quotes) BlockQuote.markerLength(text, k + 1) else 0
                continuationAt[k + 1 + marker] = item
                k = text.indexOf('\n', k + 1)
            }
        }
        return ListLayout(itemAt, continuationAt)
    }

    /** The lines of the block quotes of [text], by their start: the length of their markers. */
    private fun quoteLinesOf(text: String, spans: List<Span>): Map<Int, Int> {
        val lines = HashMap<Int, Int>()
        for (quote in spans) {
            if (quote.kind != Kind.QUOTE) continue
            var lineStart = quote.start
            while (lineStart <= quote.end) {
                lines[lineStart] = BlockQuote.markerLength(text, lineStart)
                val next = text.indexOf('\n', lineStart)
                if (next < 0) break
                lineStart = next + 1
            }
        }
        return lines
    }

    /**
     * Calls [symbol] for each character of [text] taken from the symbols font [font]: not in its code,
     * which has a font of its own, nor in its formulas.
     */
    private inline fun symbolsIn(text: String, spans: List<Span>, font: FontFamily?, symbol: (Int) -> Unit) {
        if (font == null) return
        var next = 0
        val verbatim = spans.filter { it.kind == Kind.CODE || it.kind == Kind.CODE_BLOCK || it.kind == Kind.FORMULA }
        for (index in text.indices) {
            if (!TextSymbols.isAt(text, index)) continue
            while (next < verbatim.size && verbatim[next].end <= index) next++
            if (next < verbatim.size && verbatim[next].start <= index) continue
            symbol(index)
        }
    }

    /**
     * The size of the bullets relative to the text: 1, or less in a font whose lines are lower
     * than those of the monospace font, so that a bullet does not make its line higher.
     */
    private fun bulletScale(p: Presentation<*>): Float =
        minOf(1f, p.struts.room.ascent / MONO_ASCENT, p.struts.room.descent / MONO_DESCENT)

    /** A list item laid out: its [label] ([labelWidthPx] wide) from [labelLeftPx], its text from [textLeftPx]. */
    private class ListItemLayout(
        val span: Span,
        val label: String,
        val bullet: ListNumbering.Bullet?,
        val labelLeftPx: Float,
        val labelWidthPx: Float,
        val textLeftPx: Float
    )

    /** The list items of a text by the start of their lines: their first lines, their continuation lines. */
    private class ListLayout(val itemAt: Map<Int, ListItemLayout>, val continuationAt: Map<Int, ListItemLayout>) {
        companion object {
            val EMPTY = ListLayout(emptyMap(), emptyMap())
        }
    }

    /**
     * Where the block of code [span] starts from the left of its line: at the text of its list
     * item, the block keeping half the line at least.
     */
    private fun codeBlockLeft(span: Span, p: Presentation<*>, lists: ListLayout): Float =
        span.listItem?.let { item ->
            min(lists.itemAt[item.start]?.textLeftPx ?: 0f, (p.lineWidthPx ?: 0f) / 2f)
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

    /** The advance of a bullet in the monospace font, per pixel of the size. */
    private const val BULLET_ADVANCE = 0.6f

    /** The room of the lines of the monospace font of the notes (Noto Sans Mono) above and below the baseline, per pixel of the size. */
    private const val MONO_ASCENT = 1.069f
    private const val MONO_DESCENT = 0.293f

    /** The advance of a character of a label not measured, per pixel of the size. */
    private const val ORDINAL_ADVANCE = 0.55f

    /** The gap between a label and the text of its item, per pixel of the size. */
    private const val LABEL_GAP = 0.4f

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

/** Where the top-left corner of [table] is drawn in this layout: at the left of its line, at its top. */
internal fun TextLayoutResult.tableOrigin(table: PresentedTable<*>): Offset = Offset(0f, getLineTop(getLineForOffset(table.offset)))

/** The formulas in the cells of [tables], where they are drawn in this layout. */
internal fun <T> TextLayoutResult.tableFormulas(tables: List<PresentedTable<T>>): List<Pair<PresentedFormula<T>, Rect>> =
    tables.flatMap { table ->
        val origin = tableOrigin(table)
        table.layout.formulas().map { (formula, rect) -> formula to rect.translate(origin) }
    }

/** Where the top-left corner of [block] is drawn in this layout: at the top of its line. */
internal fun TextLayoutResult.codeBlockOrigin(block: PresentedCodeBlock): Offset =
    Offset(block.leftPx, getLineTop(getLineForOffset(block.offset)))

/** The struts of the texts in these fonts, the room of their text measured by [measurer]. */
internal fun NotesFonts.struts(measurer: TextMeasurer): Struts = Struts(strutAbove, strutBelow, text, FontRoom.of(measurer, text))
