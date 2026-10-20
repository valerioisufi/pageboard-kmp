package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent

/**
 * Requests the platform to deliver stylus input without batching it to the display refresh
 * rate (Android `View.requestUnbufferedDispatch`), lowering the inking latency.
 * No-op on the other platforms.
 */
internal expect fun Modifier.unbufferedStylusInput(): Modifier

/**
 * Protects drawing gestures near the display edges from triggering system back navigation gestures
 * on platforms that support system gesture exclusion (Android 10+).
 */
internal expect fun Modifier.canvasGestureExclusion(): Modifier

/** Pixels the canvas moves for one unit of scroll (a notch of a mouse wheel), by default. */
internal const val DEFAULT_SCROLL_UNIT_PX = 48f

/**
 * Pixels the canvas moves for one unit of `PointerInputChange.scrollDelta`, on a screen of
 * [density].
 */
internal expect fun scrollUnitPx(density: Float): Float

/**
 * Phases of the two-finger scroll of a trackpad, which the macOS desktop reports apart from the
 * scroll events (Android and iOS report the two-finger pan as `PointerEventType.Pan*` events).
 */
internal enum class TrackpadScrollPhase {
    /** Fingers put on the trackpad: the scroll events follow them. */
    FINGERS_DOWN,

    /** Fingers lifted. */
    FINGERS_UP,

    /** Start of the inertia of the system: its scroll events come after the fingers are lifted. */
    MOMENTUM_START,

    /** End of the inertia of the system. */
    MOMENTUM_END,

    /** Scroll of a mouse wheel, without phases. */
    WHEEL
}

/** Phases of a pinch on a trackpad. */
internal enum class TrackpadPinchPhase { START, CHANGE, END }

/**
 * Delivers the trackpad input that Compose does not report as pointer events on this platform,
 * on the macOS desktop: the pinch to [onPinch] (phase, scale factor, pointer position) and the
 * phases of the two-finger scroll to [onScrollPhase] (phase, time in milliseconds like the
 * `uptimeMillis` of the pointer events). Android and iOS report the pinch as
 * `PointerEventType.Scale*` events.
 */
internal expect fun Modifier.trackpadInput(
    onPinch: (phase: TrackpadPinchPhase, factor: Float, focus: Offset) -> Unit,
    onScrollPhase: (phase: TrackpadScrollPhase, timeMillis: Long) -> Unit
): Modifier

/**
 * Whether a button of the stylus is held during `event` (the stylus erases while it is held),
 * where the platform reports it: Android; the Apple Pencil has no button, the desktop reports the
 * pens as mice.
 */
internal expect fun PointerEvent.isStylusButtonPressed(): Boolean
