package io.github.valerioisufi.drawview.core.canvas

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

internal actual fun scrollUnitPx(density: Float): Float = DEFAULT_SCROLL_UNIT_PX

internal actual fun Modifier.trackpadInput(
    onPinch: (phase: TrackpadPinchPhase, factor: Float, focus: Offset) -> Unit,
    onScrollPhase: (phase: TrackpadScrollPhase, timeMillis: Long) -> Unit
): Modifier = this
