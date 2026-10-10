package io.github.valerioisufi.drawview.canvas

import android.view.MotionEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.platform.LocalView

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.unbufferedStylusInput(): Modifier = composed {
    val view = LocalView.current
    motionEventSpy { event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val tool = event.getToolType(0)
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                view.requestUnbufferedDispatch(event)
            }
        }
    }
}

internal actual fun scrollUnitPx(density: Float): Float = DEFAULT_SCROLL_UNIT_PX

internal actual fun Modifier.trackpadInput(
    onPinch: (phase: TrackpadPinchPhase, factor: Float, focus: Offset) -> Unit,
    onScrollPhase: (phase: TrackpadScrollPhase, timeMillis: Long) -> Unit
): Modifier = this

/** Compose reports the first button of a stylus as the primary one, the second as the secondary one. */
internal actual fun PointerEvent.isStylusButtonPressed(): Boolean = buttons.isPrimaryPressed || buttons.isSecondaryPressed
