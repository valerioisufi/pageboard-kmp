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
internal actual fun Modifier.unbufferedStylusInput(): Modifier = composed {
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

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.canvasGestureExclusion(): Modifier = composed {
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    androidx.compose.runtime.DisposableEffect(view) {
        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                view.systemGestureExclusionRects = emptyList()
            }
        }
    }
    motionEventSpy { event ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val edgeThresholdPx = 36f * density
                    val x = event.x
                    val y = event.y
                    val viewWidth = view.width
                    val viewHeight = view.height
                    if (viewWidth > 0 && viewHeight > 0) {
                        val nearLeft = x <= edgeThresholdPx
                        val nearRight = x >= viewWidth - edgeThresholdPx
                        if (nearLeft || nearRight) {
                            // 200dp maximum exclusion window height centered on stroke location
                            val halfHeight = 100f * density
                            val top = (y - halfHeight).toInt().coerceAtLeast(0)
                            val bottom = (y + halfHeight).toInt().coerceAtMost(viewHeight)
                            val rect = if (nearLeft) {
                                android.graphics.Rect(0, top, edgeThresholdPx.toInt(), bottom)
                            } else {
                                android.graphics.Rect((viewWidth - edgeThresholdPx).toInt(), top, viewWidth, bottom)
                            }
                            view.systemGestureExclusionRects = listOf(rect)
                        } else if (view.systemGestureExclusionRects.isNotEmpty()) {
                            view.systemGestureExclusionRects = emptyList()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (view.systemGestureExclusionRects.isNotEmpty()) {
                        view.systemGestureExclusionRects = emptyList()
                    }
                }
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
