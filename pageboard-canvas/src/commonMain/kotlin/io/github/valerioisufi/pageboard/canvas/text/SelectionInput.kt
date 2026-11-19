package io.github.valerioisufi.pageboard.canvas.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.model.text.RichTextParser
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import kotlin.math.max
import kotlin.math.min

/**
 * What the input of the user does to a selection of the text being written, recognized from the
 * [TextFieldValue] before and after the change (hardware and software keyboards alike): a
 * delimiter typed over a selection surrounds it instead of replacing it, and a link pasted over a
 * selection makes it a link `[selection](link)`.
 */
public object SelectionInput {

    /**
     * When [newValue] replaces the non-empty selection of [oldValue] with a single delimiter of
     * the notation (`*`, `_`, `~`, `` ` ``, `$`) or an opening bracket or quote (`(`, `[`, `{`,
     * `"`, `'`, `“`, `‘`, `«`), surrounds the selection with the delimiter and its closing twin,
     * the selection kept on the same characters; otherwise `null`.
     *
     * * The delimiters of the notation leave the spaces at the ends of the selection outside; the
     *   brackets and the quotes surround the selection as it is.
     * * In a block of code and in inline code nothing is surrounded; in a formula only the
     *   brackets are.
     * * A composition of an input method (a dead key composing an accent) is left alone.
     */
    public fun surround(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        val oldMin = min(oldValue.selection.start, oldValue.selection.end)
        val oldMax = max(oldValue.selection.start, oldValue.selection.end)
        if (oldMin == oldMax || newValue.composition != null || !newValue.selection.collapsed) return null
        val oldText = oldValue.text
        val newText = newValue.text
        if (newValue.selection.start != oldMin + 1) return null
        if (newText.length != oldText.length - (oldMax - oldMin) + 1) return null
        if (!newText.startsWith(oldText.substring(0, oldMin)) || !newText.endsWith(oldText.substring(oldMax))) return null

        val open = newText[oldMin]
        val isNotation = open in NOTATION_DELIMITERS
        val close = if (isNotation) open else PAIRS[open] ?: return null

        val spans = RichTextParser.parse(oldText)
        val inCode = spans.any {
            (it.kind == Kind.CODE_BLOCK && oldMin >= it.start && oldMax <= it.end) ||
                (it.kind == Kind.CODE && oldMin >= it.contentStart && oldMax <= it.contentEnd)
        }
        if (inCode) return null
        val inFormula = spans.any { it.kind == Kind.FORMULA && oldMin >= it.contentStart && oldMax <= it.contentEnd }
        if (inFormula && open !in BRACKETS) return null

        var from = oldMin
        var to = oldMax
        if (isNotation) {
            while (from < to && oldText[from].isWhitespace()) from++
            while (to > from && oldText[to - 1].isWhitespace()) to--
            if (from >= to) return null
        }
        return TextFieldValue(
            text = oldText.substring(0, from) + open + oldText.substring(from, to) + close + oldText.substring(to),
            selection = TextRange(from + 1, to + 1)
        )
    }

    /**
     * When [newValue] replaces the non-empty selection of [oldValue] (on a single line, not a link
     * itself, not in code or a formula) with a link address (`http://`, `https://`, `mailto:` or
     * `www.` without spaces), writes `[selection](address)` with the selection kept on the text;
     * otherwise `null`.
     */
    public fun link(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        val oldMin = min(oldValue.selection.start, oldValue.selection.end)
        val oldMax = max(oldValue.selection.start, oldValue.selection.end)
        if (oldMin == oldMax || newValue.composition != null || !newValue.selection.collapsed) return null
        val oldText = oldValue.text
        val newText = newValue.text
        val insertedLength = newText.length - (oldText.length - (oldMax - oldMin))
        if (insertedLength <= 0 || newValue.selection.start != oldMin + insertedLength) return null
        if (!newText.startsWith(oldText.substring(0, oldMin)) || !newText.endsWith(oldText.substring(oldMax))) return null

        val address = newText.substring(oldMin, oldMin + insertedLength).trim()
        if (!isLinkAddress(address)) return null

        var from = oldMin
        var to = oldMax
        while (from < to && oldText[from].isWhitespace()) from++
        while (to > from && oldText[to - 1].isWhitespace()) to--
        if (from >= to) return null
        val selected = oldText.substring(from, to)
        if ('\n' in selected || isLinkAddress(selected)) return null

        val spans = RichTextParser.parse(oldText)
        val forbidden = spans.any {
            (it.kind == Kind.CODE_BLOCK && oldMin >= it.start && oldMax <= it.end) ||
                ((it.kind == Kind.CODE || it.kind == Kind.FORMULA) && oldMin >= it.contentStart && oldMax <= it.contentEnd) ||
                (it.kind == Kind.LINK && oldMin < it.end && oldMax > it.start)
        }
        if (forbidden) return null

        return TextFieldValue(
            text = oldText.substring(0, from) + "[" + selected + "](" + address + ")" + oldText.substring(to),
            selection = TextRange(from + 1, to + 1)
        )
    }

    private fun isLinkAddress(text: String): Boolean =
        text.isNotEmpty() && text.none { it.isWhitespace() } &&
            LINK_PREFIXES.any { text.startsWith(it, ignoreCase = true) && text.length > it.length }

    private val LINK_PREFIXES = listOf("https://", "http://", "mailto:", "www.")

    private const val NOTATION_DELIMITERS = "*_~`$"
    private const val BRACKETS = "([{"

    private val PAIRS = mapOf(
        '(' to ')',
        '[' to ']',
        '{' to '}',
        '"' to '"',
        '\'' to '\'',
        '“' to '”',
        '‘' to '’',
        '«' to '»'
    )
}
