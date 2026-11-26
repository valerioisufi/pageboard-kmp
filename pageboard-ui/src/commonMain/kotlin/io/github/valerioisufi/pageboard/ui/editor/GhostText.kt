package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/**
 * A suggestion shown after the end of the text being written (`docs/plans/text-box-hook.md`), lighter,
 * not part of the text: added to its presentation, with a mapping in which each of its characters
 * stands for the end of the source, so that the caret and the selection never go in it. The
 * presentation has spans and paragraphs only (see [RichTextPresenter]): they are kept.
 */
internal object GhostText {
    /** How much of the color of the text a suggestion has. */
    const val ALPHA: Float = 0.4f

    /** [presented] with [suggestion] after its end, in [color] made lighter. */
    fun <T> append(presented: PresentedText<T>, suggestion: String, color: Color): PresentedText<T> {
        if (suggestion.isEmpty()) return presented
        val shown = shownOf(suggestion)
        val base = presented.text
        val end = base.length + shown.length
        // On the last line of the text: its paragraph (each line is one) goes on through the suggestion
        val text = AnnotatedString.Builder(base.text + shown).apply {
            base.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
            base.paragraphStyles.forEach { addStyle(it.item, it.start, if (it.end == base.length) end else it.end) }
            addStyle(SpanStyle(color = color.copy(alpha = color.alpha * ALPHA)), base.length, end)
        }.toAnnotatedString()
        return PresentedText(
            text = text,
            mapping = presented.mapping.withShownAtEnd(shown.length),
            formulas = presented.formulas,
            codeBlocks = presented.codeBlocks,
            inlineCode = presented.inlineCode,
            hiddenAtCaret = presented.hiddenAtCaret,
            tables = presented.tables,
            quotes = presented.quotes,
            boxes = presented.boxes
        )
    }

    /** The range of the shown text that is the suggestion, in [presented] made by [append]. */
    fun rangeIn(presented: PresentedText<*>, suggestion: String): IntRange {
        val length = shownOf(suggestion).length
        return (presented.text.length - length) until presented.text.length
    }

    /**
     * [presented] with [suggestion] at [caret] of the source (the end of a line, see
     * `docs/plans/writing-suggestions-more.md`), in [color] made lighter: what is shown after it,
     * and every object placed there, moves on by its length. At the end of the text, [append].
     */
    fun <T> insert(presented: PresentedText<T>, caret: Int, suggestion: String, color: Color): PresentedText<T> {
        if (caret >= presented.mapping.sourceLength) return append(presented, suggestion, color)
        if (suggestion.isEmpty()) return presented
        val shown = shownOf(suggestion)
        val count = shown.length
        val base = presented.text
        val at = presented.mapping.originalToTransformed(caret).coerceIn(0, base.length)
        fun moved(offset: Int) = if (offset >= at) offset + count else offset
        fun movedRange(range: IntRange) = moved(range.first)..(if (range.last >= at) range.last + count else range.last)
        val text = AnnotatedString.Builder(base.text.substring(0, at) + shown + base.text.substring(at)).apply {
            // A style going on through the place of the suggestion takes it in
            base.spanStyles.forEach { addStyle(it.item, moved(it.start), if (it.end > at) it.end + count else it.end) }
            base.paragraphStyles.forEach { addStyle(it.item, moved(it.start), if (it.end >= at && it.start <= at) it.end + count else moved(it.end)) }
            addStyle(SpanStyle(color = color.copy(alpha = color.alpha * ALPHA)), at, at + count)
        }.toAnnotatedString()
        return PresentedText(
            text = text,
            mapping = presented.mapping.withShownAt(caret, count),
            formulas = presented.formulas.map { PresentedFormula(moved(it.offset), it.image, it.widthPx, it.heightPx, it.baselineRatio, it.latex, it.block, it.sourceEnd) },
            codeBlocks = presented.codeBlocks.map { PresentedCodeBlock(moved(it.offset), it.layout, it.codeStart, it.leftPx, it.codeEnd, it.start, it.end) },
            inlineCode = presented.inlineCode.map { MarkdownTextUtil.InlineCode(moved(it.start), if (it.end > at) it.end + count else it.end, it.fontSizePx) },
            hiddenAtCaret = presented.hiddenAtCaret?.let { HiddenSource(it.kind, it.source, moved(it.anchor), it.caretIn) },
            tables = presented.tables.map { PresentedTable(moved(it.offset), it.layout, it.span) },
            quotes = presented.quotes.map { PresentedQuote(movedRange(it.range), it.level) },
            boxes = presented.boxes.map { PresentedBox(movedRange(it.range), it.itemStart, it.checked) },
            links = presented.links.map { PresentedLink(it.url, moved(it.shownStart), moved(it.shownEnd), it.sourceStart, it.sourceEnd) },
            linkAtCaret = presented.linkAtCaret
        )
    }

    /** The range of the shown text that is the suggestion, in [presented] made by [insert] at [caret]. */
    fun rangeAt(presented: PresentedText<*>, caret: Int, suggestion: String): IntRange {
        val start = presented.mapping.originalToTransformed(caret)
        return start until start + shownOf(suggestion).length
    }

    /**
     * The suggestion as it is shown: on one line (a line break would start a paragraph the
     * presentation does not know), its marks of emphasis left out.
     */
    fun shownOf(suggestion: String): String =
        suggestion.lineSequence().first().replace("**", "").replace("__", "")
}
