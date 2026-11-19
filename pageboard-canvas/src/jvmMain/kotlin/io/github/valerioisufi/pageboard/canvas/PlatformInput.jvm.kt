package io.github.valerioisufi.pageboard.canvas

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

// AWT reports the scroll of a macOS trackpad in tenths of points: 10 points per unit, like the
// scrollable lists of Compose, keep the canvas under the fingers
internal actual fun scrollUnitPx(density: Float): Float =
    if (MacTrackpad.isMacOS) 10f * density else DEFAULT_SCROLL_UNIT_PX

/** Position of the pointer over the canvas, `null` when it is outside. */
private class PointerPosition {
    var value: Offset? = null
}

internal actual fun Modifier.trackpadInput(
    onPinch: (phase: TrackpadPinchPhase, factor: Float, focus: Offset) -> Unit,
    onScrollPhase: (phase: TrackpadScrollPhase, timeMillis: Long) -> Unit
): Modifier = composed {
    val currentOnPinch by rememberUpdatedState(onPinch)
    val currentOnScrollPhase by rememberUpdatedState(onScrollPhase)
    val pointer = remember { PointerPosition() }
    DisposableEffect(Unit) {
        // Installs the native event monitor (macOS); the tests may also dispatch the gestures
        MacTrackpad.isAvailable
        val removeScrollPhaseListener = MacTrackpad.addScrollPhaseListener { phase, timeMillis ->
            currentOnScrollPhase(phase, timeMillis)
        }
        // The pinch is reported to the whole application: the canvas zooms around the pointer
        // (which does not move during a pinch) when the pinch starts over it
        var focus: Offset? = null
        val removePinchListener = MacTrackpad.addPinchListener { phase, factor ->
            if (phase == TrackpadPinchPhase.START) focus = pointer.value
            val pinchFocus = focus ?: return@addPinchListener
            currentOnPinch(phase, factor, pinchFocus)
            if (phase == TrackpadPinchPhase.END) focus = null
        }
        onDispose {
            removeScrollPhaseListener()
            removePinchListener()
        }
    }
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val position = event.changes.firstOrNull()?.position
                // Moving over what is drawn on the canvas (the text being edited) keeps the
                // pointer on it: only leaving its bounds does not
                val left = position == null || position.x < 0f || position.y < 0f ||
                        position.x > size.width || position.y > size.height
                pointer.value = if (event.type == PointerEventType.Exit && left) null else position
            }
        }
    }
}

/** Compose gives the pointer events the time of their AWT events (`InputEvent.getWhen`), wall-clock time. */
internal actual fun pointerClockMillis(): Long = System.currentTimeMillis()
