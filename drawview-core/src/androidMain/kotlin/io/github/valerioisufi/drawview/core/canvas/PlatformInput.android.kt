package io.github.valerioisufi.drawview.core.canvas

import android.view.MotionEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
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
