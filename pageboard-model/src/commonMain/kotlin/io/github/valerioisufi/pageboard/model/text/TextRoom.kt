package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.TextAlignment
import kotlin.math.max
import kotlin.math.min

/**
 * The room of a text whose width follows it (`DrawText.autoWidth`): its lines may go from the side
 * of its alignment to the margin of its page, and its box is the part they take, with a little
 * room for the caret. The document keeps the room; the box is laid out (docs/plans/text-heights.md).
 */
public object TextRoom {
    /** Room left between a text following its width and the edges of its page. */
    public const val PAGE_MARGIN_MM: Float = 5f

    /** Room for the caret after the longest line of a box following its text. */
    public const val CARET_MM: Float = 1f

    /** The least width of a box following its text. */
    public const val MIN_BOX_WIDTH_MM: Float = 5f

    /** The least width of a room (of a box set by hand too). */
    public const val MIN_WIDTH_MM: Float = 20f

    /**
     * The room of a box from [left] to [right], aligned at [alignment], on a page [pageWidth] wide,
     * as its left edge and its width: from the left edge to the margin for a text aligned at the
     * start, from the margin to the right edge for one aligned at the end, as wide on both sides
     * of its middle as the nearer margin allows for a centered one; never narrower than the box.
     */
    public fun of(left: Float, right: Float, alignment: TextAlignment, pageWidth: Float): Pair<Float, Float> {
        val middle = (left + right) / 2f
        val most = when (alignment) {
            TextAlignment.START -> pageWidth - PAGE_MARGIN_MM - left
            TextAlignment.CENTER -> 2f * min(middle - PAGE_MARGIN_MM, pageWidth - PAGE_MARGIN_MM - middle)
            TextAlignment.END -> right - PAGE_MARGIN_MM
        }
        val width = max(most.coerceAtLeast(MIN_WIDTH_MM), right - left)
        val start = when (alignment) {
            TextAlignment.START -> left
            TextAlignment.CENTER -> middle - width / 2f
            TextAlignment.END -> right - width
        }
        return start to width
    }

    /** The width of the box of lines whose longest is [natural] wide, in a room [room] wide. */
    public fun boxWidth(natural: Float, room: Float): Float = (natural + CARET_MM).coerceIn(min(MIN_BOX_WIDTH_MM, room), room)

    /** Where a box [boxWidth] wide is in a room [room] wide for lines aligned at [alignment]. */
    public fun boxLeft(boxWidth: Float, room: Float, alignment: TextAlignment): Float = when (alignment) {
        TextAlignment.START -> 0f
        TextAlignment.CENTER -> (room - boxWidth) / 2f
        TextAlignment.END -> room - boxWidth
    }
}
