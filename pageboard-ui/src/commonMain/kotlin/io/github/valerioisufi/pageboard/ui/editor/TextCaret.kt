package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The caret of the text being written, drawn by the editor: the field is drawn scaled with the
 * zoom (see [pageScale]), and its own caret would be as thin as the zoom makes it. The caret is
 * [CARET_WIDTH] wide on the screen at every zoom, and blinks as the caret of a field does.
 */
internal object TextCaret {

    /** The width of the caret on the screen. */
    val CARET_WIDTH = 2.dp

    /** How long the caret stays shown, then hidden, while it blinks. */
    private const val BLINK_MILLIS = 500L

    /**
     * Whether the caret is shown now: never without the focus ([focused]); shown for a while after
     * any of [keys] changed (the caret moved, the text changed), then blinking.
     */
    @Composable
    fun rememberShown(focused: Boolean, vararg keys: Any?): State<Boolean> {
        val shown = remember { mutableStateOf(true) }
        LaunchedEffect(focused, *keys) {
            shown.value = focused
            if (!focused) return@LaunchedEffect
            while (true) {
                delay(BLINK_MILLIS)
                shown.value = !shown.value
            }
        }
        return shown
    }

    /**
     * The caret where [rect] is (its left, its top and its height, from a layout), [widthPx] wide
     * in [color], kept inside what is drawn as the caret of a field is.
     */
    fun DrawScope.drawCaret(rect: Rect, widthPx: Float, color: Color) {
        val x = (rect.left + widthPx / 2f).coerceAtMost(size.width - widthPx / 2f)
        drawLine(color, Offset(x, rect.top), Offset(x, rect.bottom), strokeWidth = widthPx)
    }
}
