package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The caret of the text being written and the blocks of code, which stay blocks while their code is
 * written (see docs/plans/text-fidelity.md): the caret is drawn in the code of a block, its fences are not
 * shown, the line of its opening fence is written in the strip.
 *
 * * ← and → go through the code by characters and step over the fences: from the line before a
 *   block into its first character, from its last one onto the line after it, and back.
 * * ↑ and ↓ go through the lines of the code as the block lays them out, into a block from the line
 *   above or below it and out of it, keeping the place of the caret along the line.
 * * Out of a block that ends the text (or starts it) the arrows add the line that is not there.
 * * Home and End go to the ends of the line of the code.
 * * The caret on the opening fence (by a tap on the label of the block, or Backspace at the start of
 *   its code) moves in it as in any text: it is written in the strip.
 */
internal object CodeBlockCaret {

    /**
     * [newValue], a move of the caret from [oldValue], off the fences of the blocks of [shown]; `null`
     * when it is no such move.
     */
    fun moved(oldValue: TextFieldValue, newValue: TextFieldValue, shown: PresentedText<*>): TextFieldValue? {
        if (oldValue.text != newValue.text || !newValue.selection.collapsed || oldValue.selection == newValue.selection) return null
        val length = newValue.text.length
        val from = if (oldValue.selection.collapsed) oldValue.selection.start else oldValue.selection.end
        val to = newValue.selection.start
        for (block in shown.codeBlocks) {
            val onOpening = to in block.start until block.codeStart
            val onClosing = block.end > block.codeEnd && to in (block.codeEnd + 1)..block.end
            when {
                // Writing the fence, in the strip
                onOpening && from in block.start until block.codeStart -> return null
                onOpening -> {
                    val backwards = to < from
                    val caret = if (backwards && block.start > 0) block.start - 1 else block.codeStart
                    return newValue.copy(selection = TextRange(caret))
                }
                onClosing -> {
                    val forwards = to > from
                    val caret = if (forwards && block.end < length) block.end + 1 else block.codeEnd
                    return newValue.copy(selection = TextRange(caret))
                }
            }
        }
        return null
    }

    /**
     * The caret moved up ([up]) or down a line from [caret], when a block of code is where it goes
     * or where it is: through the lines of its code, into it, out of it at the place of the caret
     * along the line ([offsetAt]: the offset under a point of [layout]); `null` when the field moves
     * it itself.
     */
    fun vertical(layout: TextLayoutResult, shown: PresentedText<*>, caret: Int, up: Boolean, offsetAt: (Offset) -> Int?): Int? {
        val block = shown.codeBlocks.firstOrNull { it.holds(caret) }
        if (block != null) {
            val origin = layout.codeBlockOrigin(block)
            val code = caret - block.codeStart
            val line = block.layout.lineOf(code)
            val x = block.layout.cursorRect(code).left
            val target = if (up) line - 1 else line + 1
            if (target in 0 until block.layout.lineCount) return block.codeStart + block.layout.offsetOnLine(target, x)
            // Out of the block, onto the line above or below it
            val y = if (up) origin.y - 1f else origin.y + block.layout.heightPx + 1f
            if (y < 0f || y > layout.size.height) return null
            return offsetAt(Offset(origin.x + x, y))
        }
        val at = shown.mapping.originalToTransformed(caret).coerceIn(0, layout.layoutInput.text.length)
        val line = layout.getLineForOffset(at)
        val target = if (up) line - 1 else line + 1
        if (target !in 0 until layout.lineCount) return null
        val into = shown.codeBlocks.firstOrNull { layout.getLineForOffset(it.offset) == target } ?: return null
        val x = layout.getCursorRect(at).left - layout.codeBlockOrigin(into).x
        return into.codeStart + into.layout.offsetOnLine(if (up) into.layout.lineCount - 1 else 0, x)
    }

    /**
     * The caret leaving a block of code where no line is, from [value] as [shown] shows it: going on
     * ([forward]: → at the end of its code, ↓ on its last line when [vertical]) out of a block that
     * ends the text, or back (← at the start of its code, ↑ on its first line) out of one that starts
     * it. A line is added there, the caret on it, so that a text can be written after or before the
     * block; `null` when the caret is not leaving such a block (an unclosed block takes the text after it).
     */
    fun leftAtTheEnds(value: TextFieldValue, shown: PresentedText<*>, forward: Boolean, vertical: Boolean): TextFieldValue? {
        if (!value.selection.collapsed) return null
        val caret = value.selection.start
        val block = shown.codeBlocks.firstOrNull { it.holds(caret) } ?: return null
        val line = block.layout.lineOf(caret - block.codeStart)
        if (forward) {
            if (block.end != value.text.length || block.end <= block.codeEnd) return null
            val leaving = if (vertical) line == block.layout.lineCount - 1 else caret == block.codeEnd
            return if (leaving) TextFieldValue(value.text + "\n", TextRange(value.text.length + 1)) else null
        }
        if (block.start != 0) return null
        val leaving = if (vertical) line == 0 else caret == block.codeStart
        return if (leaving) TextFieldValue("\n" + value.text, TextRange(0)) else null
    }

    /**
     * The end of a selection moved by a character from [focus] ([forward] or back) by Shift and an
     * arrow, when a block of code is where it goes or where it is: through its code by characters,
     * over its fences as the caret goes (see `docs/plans/text-fidelity-more.md`); `null` when the
     * field moves it itself. [length] is the length of the source.
     */
    fun extended(shown: PresentedText<*>, focus: Int, forward: Boolean, length: Int): Int? {
        val next = if (forward) focus + 1 else focus - 1
        if (next !in 0..length) return null
        if (shown.codeBlocks.none { focus in it.start..it.end || next in it.start..it.end }) return null
        for (block in shown.codeBlocks) {
            if (next in block.start until block.codeStart) return if (forward) block.codeStart else (block.start - 1).coerceAtLeast(0)
            if (block.end > block.codeEnd && next in (block.codeEnd + 1)..block.end) return if (forward) minOf(block.end + 1, length) else block.codeEnd
        }
        return next
    }

    /**
     * A selection drawn by a mouse from [anchor] (where it was pressed) to [focus] (where it is),
     * when one of them is in the code of a block: the field would give the block whole; `null` when
     * neither is.
     */
    fun drawn(shown: PresentedText<*>, anchor: Int?, focus: Int?): TextRange? {
        if (anchor == null || focus == null) return null
        val inCode = shown.codeBlocks.any { anchor in it.codeStart..it.codeEnd || focus in it.codeStart..it.codeEnd }
        return if (inCode) TextRange(anchor, focus) else null
    }

    /** The caret at the start ([start]) or at the end of its line of the code of a block, `null` out of the blocks. */
    fun lineEnd(shown: PresentedText<*>, caret: Int, start: Boolean): Int? {
        val block = shown.codeBlocks.firstOrNull { it.holds(caret) } ?: return null
        val line = block.layout.lineOf(caret - block.codeStart)
        return block.codeStart + block.layout.offsetOnLine(line, if (start) 0f else Float.MAX_VALUE / 2f)
    }
}
