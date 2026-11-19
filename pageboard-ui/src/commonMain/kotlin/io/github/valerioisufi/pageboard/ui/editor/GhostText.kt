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
     * The suggestion as it is shown: on one line (a line break would start a paragraph the
     * presentation does not know), its marks of emphasis left out.
     */
    fun shownOf(suggestion: String): String =
        suggestion.lineSequence().first().replace("**", "").replace("__", "")
}
