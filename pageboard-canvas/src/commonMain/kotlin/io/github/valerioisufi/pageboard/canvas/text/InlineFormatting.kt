package io.github.valerioisufi.pageboard.canvas.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.canvas.text.RichTextEditing.Format
import io.github.valerioisufi.pageboard.model.text.RichTextParser
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Span
import kotlin.math.max
import kotlin.math.min

/**
 * The formats of the characters of a text being written (bold, italic, strikethrough, code,
 * formula) as the notation reads them: which formats a part of the text has, and a format added
 * to a selection or taken off it by the spans of [RichTextParser], not by the characters around
 * the selection.
 *
 * A selection is formatted line by line, after the markers of the lists and of the headings, the
 * spaces at its ends left out: a delimiter never goes before `- ` nor touches a space, which would
 * break the notation. Inside inline code or a formula the other formats do not apply.
 */
internal object InlineFormatting {

    /** The formats the selection of [value] has: those of every line it covers, or at its caret. */
    fun formatsAt(value: TextFieldValue): Set<Format> {
        val spans = RichTextParser.parse(value.text)
        val start = min(value.selection.start, value.selection.end)
        val end = max(value.selection.start, value.selection.end)
        if (start == end) return Format.entries.filterTo(mutableSetOf()) { spanAtCaret(spans, it.kind, start) != null }
        val segments = segments(value.text, spans, start, end)
        if (segments.isEmpty()) return emptySet()
        return Format.entries.filterTo(mutableSetOf()) { format -> segments.all { activeSpan(spans, format.kind, it) != null } }
    }

    /**
     * Adds [format] to the selection of [value], or takes it off when every line of the selection
     * has it.
     *
     * * At the caret: inside a span of the format the span loses it, elsewhere the two delimiters
     *   are inserted with the caret between them.
     * * A part of a span of the format loses it, the rest keeping it (`**a|b|c**` → `**a**b**c**`);
     *   the whole span, its delimiters selected too, loses it.
     * * Added, the format takes in the spans of the format the selection touches: their delimiters
     *   go, one pair surrounds them all. Next to the character of its delimiter it uses the other
     *   one of the notation (italic in bold is `**_x_**`), so that both are read.
     *
     * The selection ends on the characters formatted.
     */
    fun toggle(value: TextFieldValue, format: Format): TextFieldValue {
        val text = value.text
        val spans = RichTextParser.parse(text)
        val start = min(value.selection.start, value.selection.end)
        val end = max(value.selection.start, value.selection.end)
        if (start == end) return toggleAtCaret(value, spans, format, start)
        val segments = segments(text, spans, start, end)
        if (segments.isEmpty() || segments.any { inOtherVerbatim(spans, format.kind, it) }) return value
        val active = segments.map { activeSpan(spans, format.kind, it) }
        val edits = mutableListOf<Edit>()
        val formatted: List<IntRange>
        if (active.all { it != null }) {
            // Each span once, from the first to the last of its parts selected
            val bySpan = segments.indices.groupBy { active[it]!! }
            formatted = bySpan.map { (span, indices) ->
                val part = segments[indices.first()].first until segments[indices.last()].last + 1
                edits += removal(text, spans, span, part)
                part
            }
        } else {
            formatted = segments.indices.filter { active[it] == null }.map { index ->
                var part = segments[index]
                // The first and the last characters kept inside the part
                var innerStart = part.first
                var innerEnd = part.last + 1
                // The spans of the format the part touches are taken in
                for (span in spans) {
                    if (span.kind != format.kind || span.end <= part.first || span.start > part.last) continue
                    if (span.start <= part.first) innerStart = span.contentStart
                    if (span.end >= part.last + 1) innerEnd = span.contentEnd
                    part = min(part.first, span.start) until max(part.last + 1, span.end)
                    edits += Edit(span.start, span.contentStart, "")
                    edits += Edit(span.contentEnd, span.end, "")
                }
                val delimiter = delimiterFor(text, format, part.first, part.last + 1, innerStart, innerEnd)
                edits += Edit(part.first, part.first, delimiter)
                edits += Edit(part.last + 1, part.last + 1, delimiter)
                part
            }
        }
        val sorted = edits.sortedWith(compareBy<Edit> { it.start }.thenBy { it.end })
        return TextFieldValue(
            text = apply(text, sorted),
            selection = TextRange(map(sorted, formatted.first().first, after = true), map(sorted, formatted.last().last + 1, after = false))
        )
    }

    /** At the caret [at]: the span of the format around it loses it, or the delimiters are inserted. */
    private fun toggleAtCaret(value: TextFieldValue, spans: List<Span>, format: Format, at: Int): TextFieldValue {
        val text = value.text
        val span = spanAtCaret(spans, format.kind, at)
        if (span != null) {
            val edits = listOf(Edit(span.start, span.contentStart, ""), Edit(span.contentEnd, span.end, ""))
            return TextFieldValue(apply(text, edits), TextRange(map(edits, at, after = false)))
        }
        if (spans.any {
                (it.isVerbatim && it.kind != format.kind && at > it.contentStart && at < it.contentEnd) ||
                    (it.kind == Kind.LINK && at > it.contentEnd && at < it.end)
            }
        ) return value
        val delimiter = delimiterFor(text, format, at, at)
        return TextFieldValue(text.substring(0, at) + delimiter + delimiter + text.substring(at), TextRange(at + delimiter.length))
    }

    /**
     * The edits taking the format of [span] off its [part]: a delimiter removed where the part
     * reaches an end of the content (across whitespace or nested span delimiters), inserted where
     * it does not (the rest of the span keeping the format), never after or before a space.
     */
    private fun removal(text: String, spans: List<Span>, span: Span, part: IntRange): List<Edit> {
        val delimiter = text.substring(span.start, span.contentStart)
        val innerDelimiter = BooleanArray(text.length)
        for (other in spans) {
            if (other !== span && other.start >= span.contentStart && other.end <= span.contentEnd) {
                for (range in other.delimiters) for (k in range) innerDelimiter[k] = true
            }
        }
        val edits = mutableListOf<Edit>()
        var close = part.first
        while (close > span.contentStart && (text[close - 1].isWhitespace() || innerDelimiter[close - 1])) close--
        edits += if (close > span.contentStart) Edit(close, close, delimiter) else Edit(span.start, span.contentStart, "")
        var open = part.last + 1
        while (open < span.contentEnd && (text[open].isWhitespace() || innerDelimiter[open])) open++
        edits += if (open < span.contentEnd) Edit(open, open, delimiter) else Edit(span.contentEnd, span.end, "")
        return edits
    }

    /**
     * The parts of the selection from [start] to [end] to format, one per line: after the marker
     * of a list item or a heading, without the spaces at their ends, the empty ones left out.
     */
    private fun segments(text: String, spans: List<Span>, start: Int, end: Int): List<IntRange> {
        val segments = mutableListOf<IntRange>()
        var lineStart = text.lastIndexOf('\n', start - 1) + 1
        while (lineStart < end) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val marker = spans.firstOrNull { (it.kind == Kind.LIST_ITEM || it.kind == Kind.HEADING) && it.start == lineStart }
            var from = max(start, lineStart)
            if (marker != null) from = max(from, marker.contentStart)
            var to = min(end, lineEnd)
            while (from < to && text[from].isWhitespace()) from++
            while (to > from && text[to - 1].isWhitespace()) to--
            if (from < to) segments += from until to
            lineStart = lineEnd + 1
        }
        return segments
    }

    /**
     * The span of [kind] that [part] has: the innermost one holding it, its delimiters included,
     * whose content it overlaps.
     */
    private fun activeSpan(spans: List<Span>, kind: Kind, part: IntRange): Span? = spans.lastOrNull {
        it.kind == kind && it.start <= part.first && part.last + 1 <= it.end && part.first < it.contentEnd && part.last + 1 > it.contentStart
    }

    /** The innermost span of [kind] whose content holds the caret [at] (at its ends too). */
    private fun spanAtCaret(spans: List<Span>, kind: Kind, at: Int): Span? =
        spans.lastOrNull { it.kind == kind && it.contentStart <= at && at <= it.contentEnd }

    /** Whether [part] is inside the code or the formula of a span of a kind other than [kind], or in a link's address. */
    private fun inOtherVerbatim(spans: List<Span>, kind: Kind, part: IntRange): Boolean =
        spans.any {
            (it.isVerbatim && it.kind != kind && it.contentStart <= part.first && part.last + 1 <= it.contentEnd) ||
                (it.kind == Kind.LINK && part.last + 1 > it.contentEnd && part.first < it.end)
        }

    private val Span.isVerbatim get() = kind == Kind.CODE || kind == Kind.FORMULA || kind == Kind.CODE_BLOCK

    /**
     * The delimiter of [format] around the text from [start] to [end]: the first of the notation
     * whose character is neither right outside its ends nor the first or the last character kept
     * inside, from [innerStart] to [innerEnd].
     */
    private fun delimiterFor(text: String, format: Format, start: Int, end: Int, innerStart: Int = start, innerEnd: Int = end): String {
        val inside = if (innerEnd > innerStart) listOfNotNull(text.getOrNull(innerStart), text.getOrNull(innerEnd - 1)) else emptyList()
        val around = listOfNotNull(text.getOrNull(start - 1), text.getOrNull(end)) + inside
        return format.delimiters.firstOrNull { it[0] !in around } ?: format.delimiter
    }

    /** The span kind of a format. */
    internal val Format.kind: Kind
        get() = when (this) {
            Format.BOLD -> Kind.BOLD
            Format.ITALIC -> Kind.ITALIC
            Format.STRIKETHROUGH -> Kind.STRIKE
            Format.CODE -> Kind.CODE
            Format.FORMULA -> Kind.FORMULA
        }

    /** The delimiters the notation reads for a format, the one written by default first. */
    private val Format.delimiters: List<String>
        get() = when (this) {
            Format.BOLD -> listOf("**", "__")
            Format.ITALIC -> listOf("*", "_")
            else -> listOf(delimiter)
        }

    /** The characters from [start] to [end] replaced by [text]. */
    internal class Edit(val start: Int, val end: Int, val text: String)

    /** [text] with the [edits] (sorted, not overlapping) applied. */
    internal fun apply(text: String, edits: List<Edit>): String {
        val out = StringBuilder()
        var position = 0
        for (edit in edits) {
            out.append(text, position, edit.start).append(edit.text)
            position = edit.end
        }
        return out.append(text, position, text.length).toString()
    }

    /**
     * Where [offset] is once the [edits] (sorted) are applied: after the text inserted right at it
     * when [after], before it otherwise; in a range replaced, at the start of its replacement (or
     * its end, [after]).
     */
    internal fun map(edits: List<Edit>, offset: Int, after: Boolean): Int {
        var shift = 0
        for (edit in edits) {
            when {
                edit.end < offset || (edit.end == offset && edit.start < edit.end) -> shift += edit.text.length - (edit.end - edit.start)
                edit.start == offset && edit.end == offset -> if (after) shift += edit.text.length
                edit.start < offset && offset < edit.end -> return edit.start + shift + if (after) edit.text.length else 0
                else -> break
            }
        }
        return offset + shift
    }
}
