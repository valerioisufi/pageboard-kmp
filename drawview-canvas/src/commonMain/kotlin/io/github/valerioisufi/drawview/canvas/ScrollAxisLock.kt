package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/**
 * The axis of one scroll gesture of a wheel or of a trackpad (`docs/camera-bounds.md`): a scroll
 * started along an axis stays on it until it ends, as `NSScrollView` scrolls on its predominant
 * axis, so that the small sideways parts of a vertical scroll do not add up. A scroll started
 * clearly diagonal stays free, as with the directional lock of `UIScrollView`.
 *
 * The deltas of the start of a gesture are held back until their length passes [slop] (in pixels,
 * read when they are), then given at once on the axis chosen. The axis is chosen from the deltas
 * added up, not from single events: AWT reports the two axes of a macOS trackpad in separate
 * events.
 *
 * An axis the content cannot move on is left out of the deltas: its rubber band would only show
 * the sideways parts of the fingers. With one axis left, there is nothing to choose.
 */
internal class ScrollAxisLock(private val slop: () -> Float) {
    private enum class Axis { UNDECIDED, HORIZONTAL, VERTICAL, FREE }

    private var axis = Axis.UNDECIDED
    private var held = Offset.Zero

    /** Starts a new gesture: its axis is chosen again. */
    fun begin() {
        axis = Axis.UNDECIDED
        held = Offset.Zero
    }

    /**
     * The part of [delta] the canvas follows, the content moving horizontally when [canMoveX] and
     * vertically when [canMoveY]: zero while the axis is being chosen, then what was held back
     * with it.
     */
    fun filter(delta: Offset, canMoveX: Boolean, canMoveY: Boolean): Offset {
        if (axis == Axis.UNDECIDED) {
            held += delta
            if (canMoveX && canMoveY) {
                if (held.getDistance() <= slop()) return Offset.Zero
                val x = abs(held.x)
                val y = abs(held.y)
                axis = when {
                    x <= y * MAX_CROSS_RATIO -> Axis.VERTICAL
                    y <= x * MAX_CROSS_RATIO -> Axis.HORIZONTAL
                    else -> Axis.FREE
                }
            } else {
                axis = Axis.FREE
            }
            return onAxis(held.also { held = Offset.Zero }, canMoveX, canMoveY)
        }
        return onAxis(delta, canMoveX, canMoveY)
    }

    /**
     * Ends the gesture, giving what was held back when its axis was never chosen: a scroll shorter
     * than the slop still moves the canvas.
     */
    fun end(): Offset {
        val rest = held
        begin()
        return rest
    }

    private fun onAxis(delta: Offset, canMoveX: Boolean, canMoveY: Boolean) = Offset(
        if (canMoveX && axis != Axis.VERTICAL) delta.x else 0f,
        if (canMoveY && axis != Axis.HORIZONTAL) delta.y else 0f
    )

    internal companion object {
        /**
         * A gesture is locked on an axis when the part across it is at most this ratio of the
         * part along it (within about 27° of the axis).
         */
        const val MAX_CROSS_RATIO = 0.5f

        /**
         * The length of the start of a trackpad gesture held back to choose its axis, in dp: the
         * touch slop of Android (the 18 dp of the desktop would hold back the start of a scroll).
         */
        const val SLOP_DP = 8f

        /** A pause of a wheel without phases longer than this starts a new gesture. */
        const val WHEEL_GESTURE_PAUSE_MS = 300L
    }
}
