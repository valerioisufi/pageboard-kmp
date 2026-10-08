package io.github.valerioisufi.drawview.core.canvas

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.Modifier

actual fun Modifier.unbufferedStylusInput(): Modifier = this

internal actual fun PointerEvent.isStylusButtonPressed(): Boolean = false
