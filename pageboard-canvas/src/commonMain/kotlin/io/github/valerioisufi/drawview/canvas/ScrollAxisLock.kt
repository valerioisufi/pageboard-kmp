package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/**
 * The axis of one scroll gesture of a wheel or of a trackpad (`docs/plans/camera-bounds.md`): a scroll
 * goes on the axis of its first move, as `NSScrollView` scrolls on its predominant axis, so that
 * the small sideways parts of a vertical scroll do not add up. A scroll that goes on across its
 * axis breaks free of it until it ends.
 *
 * Nothing is held back: AWT reports the two axes of a macOS trackpad in separate events, so a
 * choice made at the start would rest on one axis only. The gesture breaks free when the move
 * across its axis, among the last [WINDOW_DP] of the gesture, passes [BREAK_DP] and
 * [MAX_CROSS_RATIO] of the move along it. The move across is added with its sign: the sideways
 * shake of the fingers goes both ways, a move across goes one way.
 *
 * An axis the content cannot move on is left out of the deltas: its rubber band would only show
 * the sideways parts of the fingers. With one axis left, there is nothing to choose.
 *
 * @param density pixels per dp, read when they are needed (the density of the canvas may change).
 */
internal class ScrollAxisLock(private val density: () -> Float) {
    private enum class Axis { UNDECIDED, HORIZONTAL, VERTICAL, FREE }

    private var axis = Axis.UNDECIDED

    // The last part of the gesture, along its axis (a length) and across it (with its sign)
    private var along = 0f
    private var across = 0f

    /** Starts a new gesture: its axis is chosen again. */
    fun begin() {
        axis = Axis.UNDECIDED
        along = 0f
        across = 0f
    }

    /**
     * The part of [delta] the canvas follows, the content moving horizontally when [canMoveX] and
     * vertically when [canMoveY].
     */
    fun filter(delta: Offset, canMoveX: Boolean, canMoveY: Boolean): Offset {
        if (!canMoveX || !canMoveY) return Offset(if (canMoveX) delta.x else 0f, if (canMoveY) delta.y else 0f)
        if (axis == Axis.UNDECIDED) {
            if (delta == Offset.Zero) return Offset.Zero
            axis = if (abs(delta.y) >= abs(delta.x)) Axis.VERTICAL else Axis.HORIZONTAL
        }
        if (axis == Axis.FREE) return delta

        val vertical = axis == Axis.VERTICAL
        along += abs(if (vertical) delta.y else delta.x)
        across += if (vertical) delta.x else delta.y
        val window = WINDOW_DP * density()
        val length = along + abs(across)
        if (length > window) {
            along *= window / length
            across *= window / length
        }
        if (abs(across) >= BREAK_DP * density() && abs(across) >= along * MAX_CROSS_RATIO) {
            axis = Axis.FREE
            return delta
        }
        return if (vertical) Offset(0f, delta.y) else Offset(delta.x, 0f)
    }

    internal companion object {
        /**
         * A gesture breaks free of its axis when the move across it is at least this ratio of the
         * move along it (about 22° off the axis).
         */
        const val MAX_CROSS_RATIO = 0.4f

        /** The move across the axis, in dp, past which a gesture can break free of it. */
        const val BREAK_DP = 12f

        /** The length of the last part of a gesture, in dp, its direction is read on. */
        const val WINDOW_DP = 64f

        /** A pause of a wheel without phases longer than this starts a new gesture. */
        const val WHEEL_GESTURE_PAUSE_MS = 300L
    }
}
