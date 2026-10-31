package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable

@Composable
internal actual fun immersiveSystemBars(): HiddenSystemBars = HiddenSystemBars.NONE

@Composable
internal actual fun PopupSystemBarsEffect() {
}

@Composable
internal actual fun popupTakesFocus(): Boolean = true
