package io.github.valerioisufi.pageboard.ui.platform

import androidx.compose.runtime.Composable

@Composable
internal actual fun immersiveSystemBars(): HiddenSystemBars = HiddenSystemBars.NONE

@Composable
internal actual fun PopupSystemBarsEffect() {
}

@Composable
internal actual fun popupTakesFocus(): Boolean = true
