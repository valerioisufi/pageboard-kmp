package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * A suggestion shown after the end of the text being written (`docs/text-box-hook.md`), lighter,
 * not part of the text: added to its presentation, with a mapping in which each of its characters
 * stands for the end of the source, so that the caret and the selection never go in it.
 */
internal object GhostText {
    /** How much of the color of the text a suggestion has. */
    const val ALPHA: Float = 0.4f

    /** [presented] with [suggestion] after its end, in [color] made lighter. */
    fun <T> append(presented: PresentedText<T>, suggestion: String, color: Color): PresentedText<T> {
        if (suggestion.isEmpty()) return presented
        val shown = shownOf(suggestion)
        val text = buildAnnotatedString {
            append(presented.text)
            withStyle(SpanStyle(color = color.copy(alpha = color.alpha * ALPHA))) { append(shown) }
        }
        return PresentedText(
            text = text,
            mapping = presented.mapping.withShownAtEnd(shown.length),
            formulas = presented.formulas,
            codeBlocks = presented.codeBlocks,
            inlineCode = presented.inlineCode,
            hiddenAtCaret = presented.hiddenAtCaret,
            tables = presented.tables,
            quotes = presented.quotes
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
