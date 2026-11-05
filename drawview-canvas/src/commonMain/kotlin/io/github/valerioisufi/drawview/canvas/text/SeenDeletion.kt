package io.github.valerioisufi.drawview.canvas.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind

/**
 * What a deletion takes from the text being written: what is seen. The delimiters of the emphasis,
 * of the code in the line and of the escapes are not shown (see docs/text-fidelity.md), so a key
 * or a selection taking one would break a format the user cannot see.
 *
 * * Backspace or Delete next to delimiters takes the character seen beyond them.
 * * A selection deleted, or typed over, takes the characters it shows: the delimiters in it stay,
 *   the part of a format left keeping it (`**bo|ld** pla|in` → `**bo**in`), but those of a span
 *   whose whole content goes go with it.
 *
 * The links keep their own rules while their address is shown with the caret in them.
 */
internal object SeenDeletion {

    private val KINDS = setOf(Kind.BOLD, Kind.ITALIC, Kind.STRIKE, Kind.CODE, Kind.ESCAPE)

    /**
     * [newValue], a deletion or a replacement made in [oldValue], as it takes what is seen; `null`
     * when it is neither, or takes what is seen already.
     */
    fun of(oldValue: TextFieldValue, newValue: TextFieldValue): TextFieldValue? {
        val old = oldValue.text
        val new = newValue.text
        val selection = oldValue.selection
        var from: Int
        var to: Int
        var backwards = false
        val inserted: String
        if (selection.collapsed) {
            val caret = selection.start
            val removed = old.length - new.length
            if (removed <= 0) return null
            when {
                caret - removed >= 0 && newValue.selection == TextRange(caret - removed) && new == old.removeRange(caret - removed, caret) -> {
                    from = caret - removed
                    to = caret
                    backwards = true
                }
                caret + removed <= old.length && newValue.selection == TextRange(caret) && new == old.removeRange(caret, caret + removed) -> {
                    from = caret
                    to = caret + removed
                }
                else -> return null
            }
            inserted = ""
        } else {
            from = selection.min
            to = selection.max
            val rest = old.substring(to)
            if (new.length < from + rest.length || !new.startsWith(old.substring(0, from)) || !new.endsWith(rest)) return null
            inserted = new.substring(from, new.length - rest.length)
            if (newValue.selection != TextRange(from + inserted.length)) return null
        }
        // Inner spans first: a span emptied leaves its outer span emptied too
        val spans = RichTextParser.parse(old).filter { it.kind in KINDS }.sortedBy { it.end - it.start }
        if (spans.isEmpty()) return null
        val delimiter = BooleanArray(old.length)
        for (span in spans) for (range in span.delimiters) for (i in range) delimiter[i] = true

        // A key taking only delimiters takes the character seen beyond them, or nothing
        if (selection.collapsed && (from until to).all { delimiter[it] }) {
            if (backwards) {
                var k = from - 1
                while (k >= 0 && delimiter[k]) k--
                if (k < 0) return oldValue
                from = k
                to = k + 1
            } else {
                var k = to
                while (k < old.length && delimiter[k]) k++
                if (k >= old.length) return oldValue
                from = k
                to = k + 1
            }
        }
        val taken = BooleanArray(old.length)
        for (i in from until to) taken[i] = !delimiter[i]
        for (span in spans) {
            val content = (span.contentStart until span.contentEnd)
            if (!content.isEmpty() && content.all { taken[it] }) {
                for (range in span.delimiters) for (i in range) taken[i] = true
            }
        }

        // The caret after what was typed, or where it was for Delete
        val anchor = if (selection.collapsed && !backwards) selection.start else from
        val out = StringBuilder(old.length + inserted.length)
        var caret = -1
        for (i in 0..old.length) {
            if (i == from) out.append(inserted)
            if (i == anchor) caret = out.length
            if (i < old.length && !taken[i]) out.append(old[i])
        }
        val result = TextFieldValue(out.toString(), TextRange(caret))
        return result.takeIf { it != newValue }
    }
}
