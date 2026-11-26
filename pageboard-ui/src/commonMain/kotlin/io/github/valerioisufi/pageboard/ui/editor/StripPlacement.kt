package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset

/**
 * Where the strip of the source ([SourceStrip]) and the completions by it ([CompletionPopup]) stand
 * in the window (see `docs/plans/text-fidelity-more.md`), never out of it:
 *
 * * on a phone, the formatting bar docked at the bottom, over the keyboard: the strip stands on the
 *   bar, so that it covers no line of the text;
 * * elsewhere, by what it belongs to: above it, below it when there is no room above or the bar is
 *   there, else where it is in the window.
 */
internal object StripPlacement {

    /**
     * The top-left of a strip [width] x [height] in a window [windowWidth] x [windowHeight], by
     * [anchor] (what it belongs to), clear of the formatting [bar], [gap] from the edges.
     */
    fun strip(width: Int, height: Int, windowWidth: Int, windowHeight: Int, anchor: Rect, bar: Rect?, docked: Boolean, gap: Int): IntOffset {
        val x = anchor.left.toInt().coerceIn(gap, (windowWidth - width - gap).coerceAtLeast(gap))
        if (docked && bar != null && bar.top > windowHeight / 2f) {
            return IntOffset(gap, (bar.top.toInt() - gap - height).coerceIn(gap, (windowHeight - height - gap).coerceAtLeast(gap)))
        }
        val above = anchor.top.toInt() - gap - height
        val below = anchor.bottom.toInt() + gap
        val y = firstFitting(listOf(above, below), x, width, height, windowHeight, bar, gap)
            ?: above.coerceIn(gap, (windowHeight - height - gap).coerceAtLeast(gap))
        return IntOffset(x, y)
    }

    /**
     * The top-left of a popup [width] x [height] by the strip at [strip]: below it, above it when
     * there is no room below or the [bar] is there, else where it fits in the window.
     */
    fun byStrip(width: Int, height: Int, windowWidth: Int, windowHeight: Int, strip: Rect, bar: Rect?, gap: Int): IntOffset {
        val x = strip.left.toInt().coerceIn(gap, (windowWidth - width - gap).coerceAtLeast(gap))
        val below = strip.bottom.toInt() + gap
        val above = strip.top.toInt() - gap - height
        val y = firstFitting(listOf(below, above), x, width, height, windowHeight, bar, gap)
            ?: if (below + height <= windowHeight - gap) below else above.coerceAtLeast(gap)
        return IntOffset(x, y)
    }

    /** The first of [tops] where a box [width] x [height] at [x] is in the window and clear of [bar]. */
    private fun firstFitting(tops: List<Int>, x: Int, width: Int, height: Int, windowHeight: Int, bar: Rect?, gap: Int): Int? = tops.firstOrNull { y ->
        y >= gap && y + height <= windowHeight - gap &&
            (bar == null || !Rect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat()).overlaps(bar))
    }
}
