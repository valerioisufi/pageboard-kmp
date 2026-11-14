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
 * formula takes its line, drawn in its middle. Its source ends at [sourceEnd] (before its closing
 * delimiter): a tap on it puts the caret there.
 */
internal class PresentedFormula<T>(
    val offset: Int,
    val image: T,
    val widthPx: Float,
    val heightPx: Float,
    val baselineRatio: Float,
    val latex: String,
    val block: Boolean,
    val sourceEnd: Int = -1
)

/**
 * What the caret is in whose source the page hides, to show in a strip by it (see
 * docs/text-fidelity.md): the LaTeX of a formula, the address of a link. [source] is the part of the
 * source shown (end exclusive), [anchor] where its formula or its link starts in the text shown;
 * [caretIn] whether the caret is in that source (in the text of a link it is not).
 */
internal class HiddenSource(val kind: Kind, val source: IntRange, val anchor: Int, val caretIn: Boolean) {
    /** The length of the source shown. */
    val length: Int get() = source.last + 1 - source.first
}

/**
 * A block of code of a presented text, its three characters from [offset], taking its line: its
 * [layout], drawn [leftPx] from the left of the line (the text of its list item) from the top of the
 * line, its code from [codeStart] to [codeEnd] of the source, the block from [start] to [end].
 */
internal class PresentedCodeBlock(
    val offset: Int,
    val layout: CodeBlockLayout,
    val codeStart: Int,
    val leftPx: Float,
    val codeEnd: Int = codeStart,
    val start: Int = codeStart,
    val end: Int = codeEnd
) {
    /** Whether [offset] of the source is in its code (its ends included): the caret is drawn in the block. */
    fun holds(offset: Int): Boolean = offset in codeStart..codeEnd
}

/**
 * A text as it is shown (see [RichTextPresenter]): the [text] laid out, the [mapping] of its
 * offsets and those of the source, its [formulas] and [codeBlocks] to draw over their characters,
 * its code in the line ([inlineCode]) to draw on a background, the hidden source of what the caret
 * is in ([hiddenAtCaret]), the block quotes ([quotes]: the part of the text shown of each one) to
 * draw a bar at their left.
 */
internal class PresentedText<T>(
    val text: AnnotatedString,
    val mapping: SourceMapping,
    val formulas: List<PresentedFormula<T>>,
    val codeBlocks: List<PresentedCodeBlock>,
    val inlineCode: List<MarkdownTextUtil.InlineCode>,
    val hiddenAtCaret: HiddenSource?,
    val tables: List<PresentedTable<T>> = emptyList(),
    val quotes: List<IntRange> = emptyList(),
    val boxes: List<PresentedBox> = emptyList()
)

/**
 * The box of an item of a checklist in a presented text: the characters of its glyph ([range]),
 * the start of its item in the source ([itemStart]), whether it is [checked]. A tap on it checks or
 * unchecks the item.
 */
internal class PresentedBox(val range: IntRange, val itemStart: Int, val checked: Boolean)

/** The box of a checklist of [shown] under [point] of [layout] (laid out from it), or `null`. */
internal fun TextLayoutResult.boxAt(shown: PresentedText<*>, point: androidx.compose.ui.geometry.Offset): PresentedBox? {
    if (shown.boxes.isEmpty() || layoutInput.text.text != shown.text.text) return null
    return shown.boxes.firstOrNull { box ->
        val first = getBoundingBox(box.range.first)
        val last = getBoundingBox(box.range.last)
        // A little larger than the glyph: a target for a finger
        val slack = first.height * 0.25f
        point.x in (first.left - slack)..(last.right + slack) && point.y in (first.top - slack)..(first.bottom + slack)
    }
}

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

    /**
     * This mapping with [count] characters shown after the end of the text that stand for no
     * character of the source: each of them for its end (see [GhostText]).
     */
    fun withShownAtEnd(count: Int): SourceMapping {
        val end = sourceLength
        return SourceMapping(toShown, toSource + IntArray(count) { end }, noCaret, inObject)
    }
}

/**
 * Presents the texts written with the notation of [RichTextParser], once for every place that
 * shows them: the canvas (the tiles and the export), [io.github.valerioisufi.drawview.ui.components.MarkdownText]
 * and the field of the editor. The editor shows the text exactly as the canvas, character by
 * character, wherever the caret is; the source the page
 * hides of what the caret is in goes in a strip by it ([PresentedText.hiddenAtCaret]).
 *
 * * Every line of the source is a paragraph, its line break shown as a zero-width space ending it
 *   (Compose adds an empty line around the line break of a paragraph); the last line break of a
 *   text stays one, for the empty line after it.
 * * The delimiters are hidden, with the caret too: the emphasis, the code in the line, the escapes,
 *   the `#` of a heading, the address of a link; the caret never stands in a marker or between an
 *   escape and what it escapes ([SourceMapping.caretMayStand]).
 * * A formula is an object ([Struts]) drawn over its characters, with the caret in it too, or its
 *   source when it cannot be rendered (in red then); alone on its lines, it takes its line.
 * * A block of code is an object taking its line, with the caret in it too: the editor draws the
 *   caret and the selection in its code, and the line of its opening fence goes in the strip.
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
        // The caret in a span, not at its ends
        fun within(span: Span) = selMin != null && selMax != null && selMin > span.start && selMax < span.end

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
        // The formula, the link or the block whose opening fence the caret is in, whose source goes in the strip
        var atCaret: Span? = null
        for (span in spans) {
            if (span.start in tableAt) tableEnd = span.end
            if (span.start < tableEnd && span.kind != Kind.TABLE) continue
            val touched = touched(span)
            when (span.kind) {
                Kind.LIST_ITEM, Kind.QUOTE -> Unit
                Kind.CODE_BLOCK -> {
                    val layout = p.layoutCodeBlock
                    if (layout != null && p.lineWidthPx != null) {
                        // A block, with the caret in it too: its caret is drawn in its code, its
                        // opening fence written in the strip
                        val left = codeBlockLeft(span, p, lists)
                        blockLeft[span.start] = left
                        blockAt[span.start] = span to layout(codeBlockSource(text, span, p.lineWidthPx - left))
                        if (atCaret == null && touched && selMin!! >= span.start && selMax!! < span.contentStart) atCaret = span
                    } else {
                        for (delimiter in span.delimiters) for (i in delimiter) hidden[i] = true
                    }
                }
                Kind.FORMULA -> {
                    val rendered = p.renderFormula(MarkdownTextUtil.formulaSource(text, span), MarkdownTextUtil.formulaSizePx(span, p.fontSizePx))
                        ?.takeIf { it.widthPx > 0f && it.heightPx > 0f }
                    // Drawn as on the page with the caret in it too: its source goes in the strip
                    if (within(span) && atCaret == null) atCaret = span
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
                else -> {
                    // Hidden with the caret too: the lines do not move with it (docs/text-fidelity.md)
                    for (delimiter in span.delimiters) for (i in delimiter) hidden[i] = true
                    // The caret stands after the marker of a heading, never in it, and never
                    // between an escape and the character it escapes
                    when (span.kind) {
                        Kind.HEADING -> for (i in span.start until span.contentStart) noCaret[i] = true
                        Kind.ESCAPE -> for (delimiter in span.delimiters) for (i in delimiter) if (i + 1 <= text.length) noCaret[i + 1] = true
                        // Its address goes in the strip, with the caret in its text or in it
                        Kind.LINK -> if (atCaret == null && touched && selMin!! >= span.contentStart && selMax!! < span.end) atCaret = span
                        else -> Unit
                    }
                }
            }
        }
        val shown = Shown(text.length, noCaret)
        // The address of a link is edited in the strip: the caret in it keeps its own moves
        for (span in spans) if (span.kind == Kind.LINK) shown.markObject(span.contentEnd + 1, span.end)
        val formulas = mutableListOf<PresentedFormula<T>>()
        val codeBlocks = mutableListOf<PresentedCodeBlock>()
        val tables = mutableListOf<PresentedTable<T>>()
        // The struts of the objects, styled last over every other style
        class Room(val offset: Int, val widthPx: Float, val ascentPx: Float, val descentPx: Float)
        val rooms = mutableListOf<Room>()
        // The labels of the list items, where they are shown
        val labels = mutableListOf<Pair<IntRange, ListItemLayout>>()
        val lineWidth = p.lineWidthPx ?: 0f

        var i = 0
        var lineStart = true
        // Where the last object taking its line ends in the source
        var objectEnd = -1
        while (i < text.length) {
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
                codeBlocks += PresentedCodeBlock(offset, layout, span.contentStart, blockLeft.getValue(span.start), span.contentEnd, span.start, span.end)
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
                    formulas += PresentedFormula(offset, rendered.image, rendered.widthPx * fit, height, rendered.baselineRatio, latex, block = true, sourceEnd = span.contentEnd)
                } else {
                    rooms += Room(offset, rendered.widthPx, rendered.heightPx * rendered.baselineRatio, rendered.heightPx * (1f - rendered.baselineRatio))
                    formulas += PresentedFormula(offset, rendered.image, rendered.widthPx, rendered.heightPx, rendered.baselineRatio, latex, block = false, sourceEnd = span.contentEnd)
                }
                i = span.end
                continue
            }
            val c = text[i]
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
        val mapping = shown.finish()

        // The styles, mapped on what is shown
        val out = shown.builder
        fun style(style: SpanStyle, start: Int, end: Int) {
            val from = mapping.originalToTransformed(start)
            val to = mapping.originalToTransformed(end)
            if (to > from) out.addStyle(style, from, to)
        }
        out.addStyle(SpanStyle(color = p.color), 0, out.length)
        val inlineCode = mutableListOf<MarkdownTextUtil.InlineCode>()
        val errorSpans = errors.toHashSet()
        // The spans still open, the innermost last: the size of the code in a heading
        val open = ArrayList<Span>()
        for (span in spans) {
            while (open.isNotEmpty() && open.last().end <= span.start) open.removeAt(open.lastIndex)
            open += span
            if (span.kind == Kind.LIST_ITEM || span.kind == Kind.QUOTE || span.start in blockAt && span.kind == Kind.CODE_BLOCK) continue
            if (span.kind == Kind.FORMULA && (span.start in formulaAt || span in errorSpans)) continue
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
        symbolsIn(text, spans, p.symbols) { index -> style(SpanStyle(fontFamily = p.symbols), index, index + 1) }
        // The bullets in the monospace font, which has all three, small enough for the lines of the
        // text to keep their height (a shift of the baseline would make them higher)
        val bulletScale = bulletScale(p)
        val bulletSize = if (bulletScale < 1f) (p.fontSizePx * bulletScale / p.density).sp else androidx.compose.ui.unit.TextUnit.Unspecified
        for ((range, item) in labels) {
            if (item.bullet != null) out.addStyle(SpanStyle(fontFamily = p.monospace, fontSize = bulletSize), range.first, range.last + 1)
            if (item.checked != null) {
                // The box in the symbols font, the text of an item done struck through and faded
                p.symbols?.let { out.addStyle(SpanStyle(fontFamily = it), range.first, range.last + 1) }
                if (item.checked) {
                    val from = mapping.originalToTransformed(item.span.contentStart)
                    val to = mapping.originalToTransformed(item.span.end)
                    if (to > from) out.addStyle(SpanStyle(color = p.color.copy(alpha = p.color.alpha * DONE_ALPHA), textDecoration = TextDecoration.LineThrough), from, to)
                }
            }
        }
        for (room in rooms) {
            for ((k, strut) in p.struts.styles(room.widthPx, room.ascentPx, room.descentPx, p.density).withIndex()) {
                out.addStyle(strut, room.offset + k, room.offset + k + 1)
            }
        }
        val quotes = if (!p.quotes) emptyList() else spans.filter { it.kind == Kind.QUOTE && !beingTyped(it) }.map { quote ->
            mapping.originalToTransformed(quote.start) until max(mapping.originalToTransformed(quote.end), mapping.originalToTransformed(quote.start) + 1)
        }
        val hiddenAtCaret = atCaret?.let { span ->
            when (span.kind) {
                Kind.FORMULA -> HiddenSource(Kind.FORMULA, span.contentStart until span.contentEnd, mapping.originalToTransformed(span.start), caretIn = true)
                // The line of the opening fence of a block (its language and its words)
                Kind.CODE_BLOCK -> {
                    val lineEnd = text.indexOf('\n', span.start).let { if (it < 0 || it > span.end) span.end else it }
                    HiddenSource(Kind.CODE_BLOCK, span.start until lineEnd, mapping.originalToTransformed(span.start), caretIn = true)
                }
                else -> {
                    // The address of a link: between `](` and `)`
                    val address = (span.contentEnd + 2).coerceAtMost(span.end - 1) until (span.end - 1)
                    HiddenSource(Kind.LINK, address, mapping.originalToTransformed(span.contentStart), caretIn = selMin!! >= address.first && selMax!! <= address.last + 1)
                }
            }
        }
        val boxes = labels.mapNotNull { (range, item) -> item.checked?.let { PresentedBox(range, item.span.start, it) } }
        return PresentedText(out.toAnnotatedString(), mapping, formulas, codeBlocks, inlineCode, hiddenAtCaret, tables, quotes, boxes)
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

        /** The offsets from [from] to [until] of the source are inside an object: the caret keeps its own moves there. */
        fun markObject(from: Int, until: Int) {
            for (k in from.coerceAtLeast(0) until until.coerceAtMost(sourceLength + 1)) inObject[k] = true
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
        } else if (item.checked != null) {
            BOX_ADVANCE * p.fontSizePx
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
            laidOut += ListItemLayout(item.span, item.label, item.bullet, labelLeft, widths[index], textLeft, item.checked)
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

    /**
     * A list item laid out: its [label] ([labelWidthPx] wide) from [labelLeftPx], its text from
     * [textLeftPx]; [checked] for an item of a checklist (see [ListNumbering.Item.checked]).
     */
    private class ListItemLayout(
        val span: Span,
        val label: String,
        val bullet: ListNumbering.Bullet?,
        val labelLeftPx: Float,
        val labelWidthPx: Float,
        val textLeftPx: Float,
        val checked: Boolean? = null
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

    /** Whether [c] stands for no text: a strut, a joiner, a line break shown as a zero-width space. */
    fun isInvisible(c: Char): Boolean = c == OBJECT[0] || c == WORD_JOINER[0] || c == LINE_BREAK

    /** Share of the line an object taking its line takes: all of it, without wrapping on a rounding. */
    private const val LINE_FILL = 0.999f

    /** The advance of the box of an item of a checklist in the symbols font, per pixel of the size. */
    private const val BOX_ADVANCE = 0.816f

    /** The opacity of the text of an item of a checklist checked. */
    private const val DONE_ALPHA = 0.55f

    /** The advance of a bullet in the monospace font, per pixel of the size. */
    private const val BULLET_ADVANCE = 0.6f

    /** The room of the lines of the monospace font of the notes (Noto Sans Mono) above and below the baseline, per pixel of the size. */
    private const val MONO_ASCENT = 1.069f
    private const val MONO_DESCENT = 0.293f

    /** The advance of a character of a label not measured, per pixel of the size. */
    private const val ORDINAL_ADVANCE = 0.55f

    /** The gap between a label and the text of its item, per pixel of the size. */
    private const val LABEL_GAP = 0.4f


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
