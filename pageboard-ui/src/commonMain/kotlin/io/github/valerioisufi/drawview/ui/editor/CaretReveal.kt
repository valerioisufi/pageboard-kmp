package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange

/** The caret of the field (its moving end): the [text] and [selection] it was read for, its [rect] in pixels from the top-left corner of the box. */
internal class CaretPlace(val text: String, val selection: TextRange, val rect: Rect)

/** How far the canvas moves so that the caret of the text being written is in sight. */
internal object CaretReveal {

    /**
     * The movement of the canvas bringing [caret] into [visible] by [marginX] and [marginY] from its
     * edges, the least one: nothing when it is there already. A [visible] too small for it shows its
     * start.
     */
    fun panFor(caret: Rect, visible: Rect, marginX: Float, marginY: Float): Offset = Offset(
        panAlong(caret.left, caret.right, visible.left + marginX, visible.right - marginX),
        panAlong(caret.top, caret.bottom, visible.top + marginY, visible.bottom - marginY)
    )

    private fun panAlong(start: Float, end: Float, from: Float, to: Float): Float = when {
        start < from || end - start > to - from -> from - start
        end > to -> to - end
        else -> 0f
    }
}
