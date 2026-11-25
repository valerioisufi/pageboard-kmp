package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Span

/**
 * The places of the list items of a text in their lists: the item each one is nested in, and the
 * label it shows. The source keeps its markers as written; the labels follow the structure:
 *
 * * An item is nested in the item before it whose marker is less indented, in the same list: a
 *   list goes on over blank lines and the blocks of code under its items, and ends at a line of
 *   other text.
 * * The items of a list with the same parent and the same kind (bulleted or numbered) are
 *   siblings. A numbered item shows its ordinal among its siblings from the number of the first
 *   one (CommonMark: `1. 1. 1.` shows `1.`, `2.`, `3.`), as a number, a letter or a Roman numeral
 *   by the numbered items it is nested in (`1.`, `a.`, `i.`, then again), or in the style its list
 *   chose in the marker of its first item (see [ListMarker]). A bulleted item shows a disc, a
 *   circle or a square by its depth, an item of a checklist its box (see [TaskBox]).
 */
public object ListNumbering {

    /** The bullet of a bulleted item. */
    public enum class Bullet { DISC, CIRCLE, SQUARE }

    /** The numbering of a numbered item. */
    public enum class Numbering { DECIMAL, LOWER_ALPHA, LOWER_ROMAN, UPPER_ALPHA, UPPER_ROMAN }

    /**
     * A list item of a text: its [span], its [depth] (0 for an item nested in none), the index of
     * its [parent] in the items (-1 for none), the [list] of its siblings (an index shared by them)
     * and its [label]; [bullet] or [numbering] says how the label is drawn; [ordinal] is the
     * number of a numbered item (0 for a bulleted one); [run] is shared by the items not parted by
     * a line of other text; [style] is the one its list chose (`null` for a number by level);
     * [checked] says whether an item of a checklist is checked (`null` for any other item), its
     * label then the glyph of its box ([boxGlyphOf]) and [bullet] `null`.
     */
    public class Item(
        public val span: Span,
        public val depth: Int,
        public val parent: Int,
        public val list: Int,
        public val label: String,
        public val bullet: Bullet?,
        public val numbering: Numbering?,
        public val ordinal: Int = 0,
        public val run: Int = 0,
        public val style: NumberingStyle? = null,
        public val checked: Boolean? = null
    )

    /** The list items of [text] among its [spans] (see [RichTextParser.parse]), in their order. */
    public fun items(text: String, spans: List<Span>): List<Item> {
        val listItems = spans.filter { it.kind == Kind.LIST_ITEM }
        if (listItems.isEmpty()) return emptyList()
        // What belongs to a list besides its items: the blocks of code under them
        val underItems = spans.filter { it.kind == Kind.CODE_BLOCK && it.listItem != null }.sortedBy { it.start }
        val result = ArrayList<Item>(listItems.size)
        val indents = IntArray(listItems.size)
        // The items open, the innermost last (indices in the result)
        val open = ArrayList<Int>()
        // The siblings counted: by the index of their parent (-1 for the top), the last list and its count
        class Siblings(val list: Int, val ordered: Boolean, val first: Int, val style: NumberingStyle?, var count: Int)
        val siblings = HashMap<Int, Siblings>()
        var lists = 0
        var previousEnd = -1
        var run = 0
        for ((index, span) in listItems.withIndex()) {
            if (previousEnd >= 0 && breaksList(text, previousEnd, span.start, underItems)) {
                open.clear()
                siblings.clear()
                run++
            }
            val indent = RichTextParser.columnOf(text, span.start, markerStart(text, span))
            indents[index] = indent
            while (open.isNotEmpty() && indents[open.last()] >= indent) {
                siblings.remove(open.removeAt(open.lastIndex))
            }
            val parent = open.lastOrNull() ?: -1
            val previous = siblings[parent]
            // A marker of no style goes on any list; one of a style it cannot write starts another
            val fits = span.style == null || previous?.style?.admits(span.style) == true
            val group = if (previous != null && previous.ordered == span.ordered && fits) {
                previous.count++
                previous
            } else {
                Siblings(lists++, span.ordered, if (span.ordered) span.number else 0, span.style, 0).also { siblings[parent] = it }
            }
            val depth = open.size
            val label: String
            var bullet: Bullet? = null
            var numbering: Numbering? = null
            val ordinal = if (span.ordered) group.first + group.count else 0
            if (span.ordered) {
                val numberedAbove = open.count { result[it].span.ordered }
                numbering = group.style?.numbering ?: BY_LEVEL[numberedAbove % BY_LEVEL.size]
                val ordinalLabel = format(ordinal, numbering) + (group.style?.delimiter ?: '.')
                label = if (span.checked != null) "$ordinalLabel ${boxGlyphOf(span.checked)}" else ordinalLabel
            } else if (span.checked != null) {
                label = boxGlyphOf(span.checked)
            } else {
                bullet = Bullet.entries[depth % Bullet.entries.size]
                label = glyphOf(bullet)
            }
            result += Item(span, depth, parent, group.list, label, bullet, numbering, ordinal, run, group.style, span.checked)
            open += index
            previousEnd = span.end
        }
        return result
    }

    /**
     * The column of the text of [item] of [text] (its indent, its marker and the spaces after it, a
     * tab counted as 2): where its continuation lines start.
     */
    public fun textColumnOf(text: String, item: Span): Int = RichTextParser.columnOf(text, item.start, item.contentStart)

    /**
     * The continuation line of a list item of [text] whose indent has [offset] (from the start of
     * the line to its first character, both included), as the range of that indent; `null` for
     * any other offset.
     */
    public fun continuationIndentAt(text: String, offset: Int): IntRange? {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        if (lineStart == 0) return null
        // In a quote, its marker comes first on the line, hidden as the indent
        var contentStart = lineStart + BlockQuote.markerLength(text, lineStart)
        while (contentStart < text.length && (text[contentStart] == ' ' || text[contentStart] == '\t')) contentStart++
        if (offset > contentStart) return null
        val item = RichTextParser.parse(text).firstOrNull { it.kind == Kind.LIST_ITEM && it.start < lineStart && it.end >= contentStart && lineStart <= it.end }
        return if (item != null) lineStart..contentStart else null
    }

    /** The column of the marker of [item] of [text]: the spaces before it, a tab counted as 2. */
    public fun markerColumnOf(text: String, item: Span): Int = RichTextParser.columnOf(text, item.start, markerStart(text, item))

    /** The glyph of [bullet] (in a font that has it: the monospace font of the notes has all three). */
    public fun glyphOf(bullet: Bullet): String = when (bullet) {
        Bullet.DISC -> "•"
        Bullet.CIRCLE -> "◦"
        Bullet.SQUARE -> "▪"
    }

    /**
     * The glyph of the box of an item of a checklist, [checked] or not: `☑` or `☐`, in the symbols
     * font of the notes (see [TextSymbols]).
     */
    public fun boxGlyphOf(checked: Boolean): String = if (checked) "☑" else "☐"

    /**
     * Number of completed items and total items across every checklist item in [text] (`(completed, total)`).
     */
    public fun checklistCounts(text: String, spans: List<Span> = RichTextParser.parse(text)): Pair<Int, Int> {
        var done = 0
        var total = 0
        for (span in spans) {
            if (span.kind == Kind.LIST_ITEM && span.checked != null) {
                total++
                if (span.checked) done++
            }
        }
        return done to total
    }

    /**
     * Completed and total checklist item counts (`(completed, total)`) for the checklist run
     * containing [offset], falling back to the whole text's checklist counts if [offset] is not on
     * a checklist run. Returns `0 to 0` when [text] contains no checklist items.
     */
    public fun checklistCountsAt(text: String, offset: Int, spans: List<Span> = RichTextParser.parse(text)): Pair<Int, Int> {
        val items = items(text, spans)
        val tasks = items.filter { it.checked != null }
        if (tasks.isEmpty()) return 0 to 0
        val current = items.firstOrNull { offset >= it.span.start && offset <= it.span.end }
        val inRun = current?.let { target -> tasks.filter { it.run == target.run } }.orEmpty()
        val scope = if (inRun.isNotEmpty()) inRun else tasks
        val done = scope.count { it.checked == true }
        return done to scope.size
    }

    /** [number] written in [numbering]: `3`, `c`, `C`, `iii`, `III`; a number below 1 as a number. */
    public fun format(number: Int, numbering: Numbering): String = when {
        number < 1 || numbering == Numbering.DECIMAL -> number.toString()
        numbering == Numbering.LOWER_ALPHA -> alpha(number)
        numbering == Numbering.UPPER_ALPHA -> alpha(number).uppercase()
        numbering == Numbering.UPPER_ROMAN -> roman(number).uppercase()
        else -> roman(number)
    }

    /** `a` … `z`, `aa` … `az`, `ba`…: the letters of spreadsheets. */
    private fun alpha(number: Int): String {
        val out = StringBuilder()
        var n = number
        while (n > 0) {
            n--
            out.append('a' + n % 26)
            n /= 26
        }
        return out.reverse().toString()
    }

    private fun roman(number: Int): String {
        if (number >= 4000) return number.toString()
        val out = StringBuilder()
        var n = number
        for ((value, letters) in ROMAN) {
            while (n >= value) {
                out.append(letters)
                n -= value
            }
        }
        return out.toString()
    }

    /** The numbering of a list with no style, by the numbered items it is nested in. */
    private val BY_LEVEL = listOf(Numbering.DECIMAL, Numbering.LOWER_ALPHA, Numbering.LOWER_ROMAN)

    private val ROMAN = listOf(
        1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc",
        50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i"
    )

    /** Where the marker of [item] starts: after the spaces of its line. */
    internal fun markerStart(text: String, item: Span): Int {
        var k = item.start
        while (k < item.contentStart && (text[k] == ' ' || text[k] == '\t')) k++
        return k
    }

    /**
     * Whether a line of other text is between [from] (the end of an item) and [to] (the start of
     * the next one): a line not blank, out of the blocks of code under the items, its marker of a
     * quote apart. A list never goes from a quote to out of it.
     */
    private fun breaksList(text: String, from: Int, to: Int, underItems: List<Span>): Boolean {
        if (BlockQuote.isQuotedAt(text, from) != BlockQuote.isQuotedAt(text, to)) return true
        var lineStart = from
        while (lineStart < to) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0 || it > to) to else it }
            val covered = RichTextParser.isIn(underItems, lineStart)
            val textStart = if (lineStart == 0 || text[lineStart - 1] == '\n') lineStart + BlockQuote.markerLength(text, lineStart) else lineStart
            if (!covered && textStart < lineEnd && text.substring(textStart, lineEnd).isNotBlank()) return true
            lineStart = lineEnd + 1
        }
        return false
    }
}
