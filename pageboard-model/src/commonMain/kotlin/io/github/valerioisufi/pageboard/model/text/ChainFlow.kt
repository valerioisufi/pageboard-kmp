package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.text.RichTextParser.Kind

/**
 * Splits the whole text of a chain of boxes (see [TextChains]) into the rooms of its boxes, from
 * the first: each box takes the most of the text that fits in its room, the rest goes on into the
 * next ones, more boxes of [Room] `extra` added while text is left.
 *
 * The text is cut between its lines, never inside a block of code, a table, a formula over several
 * lines or a list item with its continuation lines; each box takes one such unit at least, even
 * taller than its room. A single line taller than a whole new box is cut between its words, the
 * next part going on the same line ([Piece.continuesLine]). No box ends with a heading, nor with a
 * line ending with `:` before a block (a list, a table, a block of code, a formula): they go on with
 * what follows them; a heading keeps the first lines of a long paragraph after it, at least two,
 * the paragraph cut between its words. A numbered list cut between two boxes
 * goes on with its numbers: the first item of the next part is written with its number.
 *
 * @param heightOf the height in millimeters of a text laid out on a width in millimeters.
 */
public class ChainFlow(private val heightOf: (text: String, widthMm: Float) -> Float) {

    /** The room of a box: the width its lines wrap on, the height they may take. */
    public data class Room(val widthMm: Float, val heightMm: Float)

    /** The part of the text of a box, and whether it goes on the last line of the part before it. */
    public data class Piece(val text: String, val continuesLine: Boolean = false)

    /**
     * The parts of [text] for the boxes of [rooms], then for boxes of [extra] as many as needed;
     * at least one part (empty for an empty text).
     */
    public fun flow(text: String, rooms: List<Room>, extra: Room): List<Piece> {
        val pieces = mutableListOf<Piece>()
        // The numbers as shown: the lists are counted through the boxes, a part alone would count from its first number
        var rest = ListRenumbering.asShown(text)
        var continuesLine = false
        var index = 0
        while (true) {
            val room = rooms.getOrElse(index) { extra }
            val fresh = index >= rooms.size
            val cut = cutFor(rest, room, fresh)
            if (cut >= rest.length) {
                pieces += Piece(rest, continuesLine)
                return pieces
            }
            if (rest[cut] == '\n') {
                pieces += Piece(rest.substring(0, cut), continuesLine)
                rest = rest.substring(cut + 1)
                continuesLine = false
            } else {
                pieces += Piece(rest.substring(0, cut), continuesLine)
                rest = rest.substring(cut)
                continuesLine = true
            }
            index++
            // Safety: a text never needs more boxes than characters
            if (index > text.length + rooms.size) {
                pieces += Piece(rest, continuesLine)
                return pieces
            }
        }
    }

    /**
     * Where [text] is cut for [room]: the end of the most units that fit (the length of the text
     * when all of it fits; the line break after the last unit kept otherwise), one unit at least;
     * in a [fresh] room, a first unit of one line too tall is cut between its words.
     */
    private fun cutFor(text: String, room: Room, fresh: Boolean): Int {
        if (heightOf(text, room.widthMm) <= room.heightMm) return text.length
        val ends = unitEnds(text)
        // The most units that fit: the heights grow with the units, a binary search
        var low = 0
        var high = ends.size - 1
        var best = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (heightOf(text.substring(0, ends[middle]), room.widthMm) <= room.heightMm) {
                best = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (best >= 0) {
            val kept = keptWithItsHeading(text, ends, best)
            // A heading that would go on: the first lines of its paragraph rather come along
            if (kept != best) firstLinesAfterHeading(text, ends, best, room)?.let { return it }
            return ends[kept]
        }
        val first = text.substring(0, ends[0])
        if (fresh && '\n' !in first) return wordCut(first, room).takeIf { it in 1 until first.length } ?: ends[0]
        return ends[0]
    }

    /**
     * The unit of [ends] to cut after instead of [best], so that no heading ends a box: the last one
     * before the headings and the blank lines at the end of what fits. [best] when only headings
     * and blank lines fit, as a box always takes something.
     */
    private fun keptWithItsHeading(text: String, ends: List<Int>, best: Int): Int {
        val spans = RichTextParser.parse(text)
        val headingEnds = spans.filter { it.kind == Kind.HEADING }.mapTo(HashSet()) { it.end }
        // A line ending with `:` before a block goes on with the block, as a heading does
        val blockStarts = spans.filter { it.kind == Kind.LIST_ITEM || it.kind == Kind.TABLE || it.kind == Kind.CODE_BLOCK || (it.kind == Kind.FORMULA && it.display) }
            .mapTo(HashSet()) { it.start }
        for (k in ends.indices) {
            val end = ends[k]
            val start = if (k == 0) 0 else ends[k - 1] + 1
            if (end < text.length && end + 1 in blockStarts && text.substring(start, end).trimEnd().endsWith(':') && (end + 1 !in headingEnds)) headingEnds += end
        }
        var kept = best
        var afterHeading = false
        while (kept >= 0) {
            val end = ends[kept]
            val start = if (kept == 0) 0 else ends[kept - 1] + 1
            when {
                end in headingEnds -> afterHeading = true
                start >= end || text.substring(start, end).isBlank() -> Unit
                else -> break
            }
            kept--
        }
        return if (afterHeading && kept >= 0) kept else best
    }

    /**
     * Where to cut the paragraph after the heading ending the units up to [best] of [text], so that
     * its first lines stay with the heading in [room]: at least two lines of it, cut between its
     * words; `null` when they do not fit, or when what follows the heading is not a paragraph.
     */
    private fun firstLinesAfterHeading(text: String, ends: List<Int>, best: Int, room: Room): Int? {
        val next = best + 1
        if (next >= ends.size) return null
        val start = ends[best] + 1
        val lineEnd = ends[next]
        val headingEnd = ends[best]
        val spans = RichTextParser.parse(text)
        if (spans.none { it.kind == Kind.HEADING && it.end == headingEnd }) return null
        // A paragraph: one line of text, not a block nor a heading
        if (spans.any { it.start <= start && it.end > start && it.kind != Kind.BOLD && it.kind != Kind.ITALIC && it.kind != Kind.STRIKE && it.kind != Kind.CODE && it.kind != Kind.LINK && it.kind != Kind.FORMULA && it.kind != Kind.ESCAPE } ||
            text.substring(start, lineEnd).let { it.isBlank() || '\n' in it }) return null
        val words = mutableListOf<Int>()
        for (k in start until lineEnd) if (text[k] == ' ' && k + 1 < lineEnd && text[k + 1] != ' ') words += k + 1
        var low = 0
        var high = words.size - 1
        var cut = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (heightOf(text.substring(0, words[middle]), room.widthMm) <= room.heightMm) {
                cut = words[middle]
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (cut < 0) return null
        // At least two lines of it: taller than a line alone
        val oneLine = heightOf(text.substring(start, words.firstOrNull() ?: lineEnd).trim(), room.widthMm)
        return cut.takeIf { heightOf(text.substring(start, cut), room.widthMm) >= 2 * oneLine }
    }

    /** The end of the most words of [line] that fit in [room] (after the space following the last one). */
    private fun wordCut(line: String, room: Room): Int {
        val ends = mutableListOf<Int>()
        for ((k, c) in line.withIndex()) if (c == ' ' && k + 1 < line.length && line[k + 1] != ' ') ends += k + 1
        var low = 0
        var high = ends.size - 1
        var best = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (heightOf(line.substring(0, ends[middle]), room.widthMm) <= room.heightMm) {
                best = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return if (best >= 0) ends[best] else -1
    }

    /**
     * The ends of the units of [text] (each at a line break, or at the end of the text): its lines,
     * a block of code, a table, a formula over lines or a list item with its lines kept whole.
     */
    private fun unitEnds(text: String): List<Int> {
        val spans = RichTextParser.parse(text).filter {
            it.kind == Kind.CODE_BLOCK || it.kind == Kind.TABLE || it.kind == Kind.LIST_ITEM || (it.kind == Kind.FORMULA && it.display)
        }.sortedBy { it.start }
        val ends = mutableListOf<Int>()
        var k = text.indexOf('\n')
        var spanIndex = 0
        while (k >= 0) {
            while (spanIndex < spans.size && spans[spanIndex].end <= k) spanIndex++
            val inside = spans.drop(spanIndex).any { it.start < k && it.end > k }
            if (!inside) ends += k
            k = text.indexOf('\n', k + 1)
        }
        ends += text.length
        return ends
    }
}
