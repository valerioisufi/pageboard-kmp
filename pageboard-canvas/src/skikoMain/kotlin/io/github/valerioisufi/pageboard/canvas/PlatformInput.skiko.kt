package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent

internal actual fun Modifier.canvasGestureExclusion(): Modifier = this

internal actual fun PointerEvent.isStylusButtonPressed(): Boolean = false

