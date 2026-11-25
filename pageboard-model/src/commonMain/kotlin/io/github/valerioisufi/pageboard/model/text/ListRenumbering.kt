package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind
import io.github.valerioisufi.pageboard.model.text.RichTextParser.Span

/**
 * The numbers written in the source of the lists an edit touched, made right: the labels of a list
 * are computed from its first number, and the copy, the search and the agents read the source.
 *
 * The rules, for each numbered list after the edit:
 *
 * * A list made of the same items as before keeps its markers as written (`1. 1. 1.` stays).
 * * The start belongs to the list, not to its first item: a list keeps its start when its first
 *   item goes, and a part of a list cut by other text goes on from the items before it.
 * * An item moved to another level starts a list from 1; a list new to the text starts from the
 *   number written in its first marker.
 *
 * The lines of an item (continuation lines, blocks of code under it) follow when its marker
 * changes length: they are indented at its text.
 */
public object ListRenumbering {

    /** The text after an edit with its numbers made right, and where its offsets went. */
    public class Renumbered internal constructor(
        public val text: String,
        private val replacements: List<Replacement>
    ) {
        /** Where [offset] of the text given to [after] is in [text]. */
        public fun map(offset: Int): Int {
            var shift = 0
            for (replacement in replacements) {
                if (offset <= replacement.start) break
                val removedUpTo = minOf(offset, replacement.start + replacement.removed)
                val inside = removedUpTo - replacement.start
                if (offset < replacement.start + replacement.removed) {
                    return replacement.start + shift + minOf(inside, replacement.inserted.length)
                }
                shift += replacement.inserted.length - replacement.removed
            }
            return offset + shift
        }
    }

    /** [removed] characters from [start] replaced by [inserted]. */
    internal class Replacement(val start: Int, val removed: Int, val inserted: String)

    /** [new] with its numbers made right after the edit from [old], or `null` when they are. */
    public fun after(old: String, new: String): Renumbered? {
        if (old == new) return null
        var prefix = 0
        val most = minOf(old.length, new.length)
        while (prefix < most && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < most - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
        if (cannotChangeTheLists(old, new, prefix, suffix)) return null

        val spans = RichTextParser.parse(new)
        val items = ListNumbering.items(new, spans)
        if (items.none { it.span.ordered }) return null
        val oldItems = ListNumbering.items(old, RichTextParser.parse(old))
        val oldByMarker = HashMap<Int, Int>()
        for ((index, item) in oldItems.withIndex()) oldByMarker[ListNumbering.markerStart(old, item.span)] = index

        // The end the two texts share, free of the start they share: `10.` and `11.` share a `1`
        var tail = 0
        while (tail < most && old[old.length - 1 - tail] == new[new.length - 1 - tail]) tail++
        // The item before the edit of each item after it: the same marker at the same place
        val origins = IntArray(items.size) { index ->
            val span = items[index].span
            val marker = ListNumbering.markerStart(new, span)
            val before = when {
                span.contentStart <= prefix -> marker
                marker >= new.length - tail -> marker + old.length - new.length
                else -> -1
            }
            val origin = oldByMarker[before]
            if (origin != null && sameMarker(old, oldItems[origin].span, new, span)) origin else -1
        }
        // The items of the lists before the edit still at their level, in a numbered list
        val kept = HashSet<Int>()
        for ((index, item) in items.withIndex()) {
            val origin = origins[index]
            if (origin >= 0 && item.span.ordered && oldItems[origin].depth == item.depth) kept += origin
        }

        val replacements = ArrayList<Replacement>()
        // The numbers written by this edit, for the markers by level nested in them
        val numbers = HashMap<Int, Int>()
        fun parentsOf(index: Int): List<Int> {
            val parents = ArrayList<Int>()
            var parent = items[index].parent
            while (parent >= 0) {
                if (items[parent].span.ordered) parents += numbers[parent] ?: items[parent].ordinal
                parent = items[parent].parent
            }
            return parents.asReversed()
        }
        for (list in items.indices.filter { items[it].span.ordered }.groupBy { items[it].list }.values) {
            val first = items[list[0]]
            val origin = origins[list[0]]
            if (origin >= 0 && oldItems[origin].depth == first.depth) {
                val oldList = oldItems[origin].list
                val whole = oldItems.count { it.list == oldList } == list.size &&
                    list.all { origins[it] >= 0 && oldItems[origins[it]].list == oldList && oldItems[origins[it]].depth == items[it].depth }
                if (whole) continue
            }
            val moved = origin >= 0 && oldItems[origin].depth != first.depth
            val start = when {
                origin < 0 -> first.span.number
                moved -> 1
                else -> startOf(oldItems, origin, kept)
            }
            // An item moved to a new level takes the numbering of that level, not the style it had
            val style = if (moved) null else first.style
            for ((position, index) in list.withIndex()) {
                numbers[index] = start + position
                val parents = if (style?.outline == true) parentsOf(index) else emptyList()
                write(new, items[index].span, style, start + position, spans, replacements, parents)
            }
        }
        // The numbers of the parents written in a marker by level follow them
        for ((index, item) in items.withIndex()) {
            if (item.style?.outline != true || index in numbers) continue
            write(new, item.span, item.style, item.span.number, spans, replacements, parentsOf(index))
        }
        if (replacements.isEmpty()) return null
        return Renumbered(applied(new, replacements), replacements)
    }

    /**
     * [text] with every number written as it is shown: the source of a text whose pieces come from
     * several boxes, whose lists are counted through them (see [ChainFlow]).
     */
    internal fun asShown(text: String): String {
        val spans = RichTextParser.parse(text)
        val replacements = ArrayList<Replacement>()
        val items = ListNumbering.items(text, spans)
        for ((index, item) in items.withIndex()) {
            if (!item.span.ordered) continue
            val parents = if (item.style?.outline == true) ListNumbering.outlineParents(items, index) else emptyList()
            write(text, item.span, item.style, item.ordinal, spans, replacements, parents)
        }
        return if (replacements.isEmpty()) text else applied(text, replacements)
    }

    /**
     * [text] with the markers of [items] replaced: each written by [marker] from its position (with
     * the spaces after it), the lines of each item following its text.
     */
    public fun withMarkers(text: String, items: List<Span>, marker: (Int) -> String): Renumbered {
        val spans = RichTextParser.parse(text)
        val replacements = ArrayList<Replacement>()
        for ((position, item) in items.withIndex()) {
            val start = ListNumbering.markerStart(text, item)
            val written = marker(position)
            val length = item.contentStart - start
            replacements += Replacement(start, length, written)
            if (written.length != length) moveLines(text, item, written.length - length, spans, replacements)
        }
        return Renumbered(applied(text, replacements), replacements)
    }

    /** [text] with [replacements], which end up sorted. */
    private fun applied(text: String, replacements: MutableList<Replacement>): String {
        replacements.sortBy { it.start }
        val out = StringBuilder(text.length + 8)
        var from = 0
        for (replacement in replacements) {
            out.append(text, from, replacement.start).append(replacement.inserted)
            from = replacement.start + replacement.removed
        }
        out.append(text, from, text.length)
        return out.toString()
    }

    /**
     * Whether an edit cannot change a list, read without parsing: letters only, replacing letters
     * only, in the middle of a word. Typing a word costs a comparison.
     */
    private fun cannotChangeTheLists(old: String, new: String, prefix: Int, suffix: Int): Boolean {
        if (prefix == 0 || !old[prefix - 1].isLetter()) return false
        for (k in prefix until old.length - suffix) if (!old[k].isLetter()) return false
        for (k in prefix until new.length - suffix) if (!new[k].isLetter()) return false
        return true
    }

    /** The numbers of the parents written in the marker of [item] (a marker by level). */
    private fun writtenParents(text: String, item: Span): List<Int> =
        ListMarker.at(text, ListNumbering.markerStart(text, item))?.parents.orEmpty()

    /** Whether the markers of two items are written the same, their indentation apart. */
    private fun sameMarker(old: String, oldSpan: Span, new: String, span: Span): Boolean {
        val oldMarker = ListNumbering.markerStart(old, oldSpan)
        val marker = ListNumbering.markerStart(new, span)
        val length = span.contentStart - marker
        return oldSpan.contentStart - oldMarker == length && old.regionMatches(oldMarker, new, marker, length)
    }

    /**
     * The number the list of the item [origin] goes on from: the start of its list before the edit,
     * plus the items of that list before [origin] still there ([kept]).
     */
    private fun startOf(oldItems: List<ListNumbering.Item>, origin: Int, kept: Set<Int>): Int {
        val list = oldItems[origin].list
        var start = -1
        var before = 0
        for (index in 0 until origin) {
            if (oldItems[index].list != list) continue
            if (start < 0) start = oldItems[index].ordinal
            if (index in kept) before++
        }
        return (if (start < 0) oldItems[origin].ordinal else start) + before
    }

    /**
     * Adds to [replacements] the marker of [number] in [style] for [item] (after the numbers of its
     * [parents] for a marker by level), and its lines moved to follow its text; nothing when it is
     * written so.
     */
    private fun write(text: String, item: Span, style: NumberingStyle?, number: Int, spans: List<Span>, replacements: MutableList<Replacement>, parents: List<Int> = emptyList()) {
        if (item.number == number && item.style == style && (style?.outline != true || writtenParents(text, item) == parents)) return
        val marker = ListNumbering.markerStart(text, item)
        val box = if (item.checked != null) TaskBox.write(item.checked) + " " else ""
        val written = ListMarker.write(style, number, parents) + box
        val length = item.contentStart - marker
        replacements += Replacement(marker, length, written)
        if (written.length != length) moveLines(text, item, written.length - length, spans, replacements)
    }

    /** Adds to [replacements] the lines of [item] after its first one moved by [shift] columns. */
    private fun moveLines(text: String, item: Span, shift: Int, spans: List<Span>, replacements: MutableList<Replacement>) {
        var end = item.end
        for (span in spans) if (span.kind == Kind.CODE_BLOCK && span.listItem?.start == item.start) end = maxOf(end, span.end)
        var line = text.indexOf('\n', item.contentStart)
        while (line in 0 until end) {
            // The line of an item in a quote has its indentation after its marker
            val lineStart = line + 1 + BlockQuote.markerLength(text, line + 1)
            var spaces = 0
            while (lineStart + spaces < text.length && text[lineStart + spaces] == ' ') spaces++
            val blank = lineStart + spaces >= text.length || text[lineStart + spaces] == '\n'
            if (!blank) {
                if (shift > 0) {
                    replacements += Replacement(lineStart, 0, " ".repeat(shift))
                } else if (spaces > 0) {
                    replacements += Replacement(lineStart, minOf(spaces, -shift), "")
                }
            }
            line = text.indexOf('\n', lineStart)
        }
    }
}
