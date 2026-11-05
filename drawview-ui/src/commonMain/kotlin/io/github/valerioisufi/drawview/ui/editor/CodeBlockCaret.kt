package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The caret of the text being written and the blocks of code, which stay blocks while their code is
 * written (see docs/text-fidelity.md): the caret is drawn in the code of a block, its fences are not
 * shown, the line of its opening fence is written in the strip.
 *
 * * ← and → go through the code by characters and step over the fences: from the line before a
 *   block into its first character, from its last one onto the line after it, and back.
 * * ↑ and ↓ go through the lines of the code as the block lays them out, into a block from the line
 *   above or below it and out of it, keeping the place of the caret along the line.
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

    /** The caret at the start ([start]) or at the end of its line of the code of a block, `null` out of the blocks. */
    fun lineEnd(shown: PresentedText<*>, caret: Int, start: Boolean): Int? {
        val block = shown.codeBlocks.firstOrNull { it.holds(caret) } ?: return null
        val line = block.layout.lineOf(caret - block.codeStart)
        return block.codeStart + block.layout.offsetOnLine(line, if (start) 0f else Float.MAX_VALUE / 2f)
    }
}
