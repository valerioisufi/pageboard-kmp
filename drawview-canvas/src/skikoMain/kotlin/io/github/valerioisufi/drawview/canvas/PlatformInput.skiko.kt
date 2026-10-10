package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent

actual fun Modifier.unbufferedStylusInput(): Modifier = this

internal actual fun PointerEvent.isStylusButtonPressed(): Boolean = false
