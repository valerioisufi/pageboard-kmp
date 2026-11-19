package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

internal actual fun scrollUnitPx(density: Float): Float = DEFAULT_SCROLL_UNIT_PX

internal actual fun Modifier.trackpadInput(
    onPinch: (phase: TrackpadPinchPhase, factor: Float, focus: Offset) -> Unit,
    onScrollPhase: (phase: TrackpadScrollPhase, timeMillis: Long) -> Unit
): Modifier = this

/** Compose gives the pointer events the `timestamp` of their touches, the uptime of the system. */
internal actual fun pointerClockMillis(): Long = (platform.Foundation.NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()
