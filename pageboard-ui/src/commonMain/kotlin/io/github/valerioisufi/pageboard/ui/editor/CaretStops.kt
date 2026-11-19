package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Where the caret of the text being written can stand, from the text as it is shown (its
 * [SourceMapping]): the delimiters are not shown, so several offsets of the source are shown at the
 * same place, and the field moves the caret by the characters of the source.
 *
 * * A move by a key (the arrows, Home and End, a word, with Shift too) that would leave the caret at
 *   the same place on the screen goes on in its direction: every press moves the caret.
 * * A place given by a pointer (a mouse, a finger, a pen, a handle) is where the pointer is: it is
 *   never moved on, only settled among the offsets shown there. Moved on, a drag starting at the
 *   edge of a format would leave out the character it starts on (see docs/plans/pointer-selection.md).
 * * Among the offsets shown at the same place, the caret takes the one inside the format on its
 *   left (typing goes on in the format of the character on the left, as in the word processors),
 *   never one in a marker that is not shown; a selection takes the offsets inside it, so that it
 *   holds the characters it shows and no delimiter of theirs.
 * * The objects (a formula, a block of code, a table) keep their own rules: an offset inside one is
 *   left as it is.
 */
internal object CaretStops {

    /**
     * [newValue] with its selection on offsets where the caret can stand, moved by a move from
     * [oldValue] as above, shown by [mapping] (that of the text of both); `null` when the text
     * changed, the selection did not, or it needs nothing. [pointed] tells that a pointer gave the
     * selection, from where it is on the screen, and no key moved it.
     */
    fun moved(oldValue: TextFieldValue, newValue: TextFieldValue, mapping: SourceMapping, pointed: Boolean = false): TextFieldValue? {
        if (oldValue.text != newValue.text || oldValue.selection == newValue.selection) return null
        if (mapping.sourceLength != newValue.text.length) return null
        val old = oldValue.selection
        val new = newValue.selection
        val selection = if (new.collapsed) {
            // A caret: moved from where it was, or put
            val from = if (old.collapsed) old.start else if (new.start < old.min) old.min else old.max
            TextRange(caret(mapping, if (pointed) new.start else settle(mapping, from, new.start)))
        } else {
            // The end that moved goes on; the selection holds what it shows
            val start = if (pointed || new.start == old.start) new.start else settle(mapping, old.start, new.start)
            val end = if (pointed || new.end == old.end) new.end else settle(mapping, old.end, new.end)
            val forward = start <= end
            val first = inside(mapping, min(start, end), towardsEnd = true)
            val last = inside(mapping, max(start, end), towardsEnd = false)
            if (first >= last) TextRange(caret(mapping, min(start, end))) else if (forward) TextRange(first, last) else TextRange(last, first)
        }
        return if (selection == new) null else newValue.copy(selection = selection)
    }

    /** [to], reached from [from]: on the next place shown in the direction of the move when shown where [from] is. */
    private fun settle(mapping: SourceMapping, from: Int, to: Int): Int {
        // Into an object, or out of one (its source is in the strip): a move of its own
        if (mapping.insideObject(to) || mapping.insideObject(from)) return to
        val direction = (to - from).sign
        val at = mapping.originalToTransformed(from)
        var offset = to
        if (direction != 0) {
            while (mapping.originalToTransformed(offset) == at && offset + direction in 0..mapping.sourceLength && !mapping.insideObject(offset + direction)) {
                offset += direction
            }
        }
        return offset
    }

    /** The caret at [offset]: the first offset shown at its place where the caret may stand. */
    private fun caret(mapping: SourceMapping, offset: Int): Int = inside(mapping, offset, towardsEnd = false)

    /**
     * Among the offsets shown where [offset] is, the first where the caret may stand ([towardsEnd]
     * `false`: inside the format on the left, the caret and the end of a selection) or the last
     * ([towardsEnd]: after the delimiters, the start of a selection).
     */
    private fun inside(mapping: SourceMapping, offset: Int, towardsEnd: Boolean): Int {
        if (mapping.insideObject(offset)) return offset
        val at = mapping.originalToTransformed(offset)
        var first = offset
        while (first > 0 && mapping.originalToTransformed(first - 1) == at && !mapping.insideObject(first - 1)) first--
        var last = offset
        while (last < mapping.sourceLength && mapping.originalToTransformed(last + 1) == at && !mapping.insideObject(last + 1)) last++
        val candidates = if (towardsEnd) (last downTo first) else (first..last)
        return candidates.firstOrNull { mapping.caretMayStand(it) } ?: offset
    }
}
