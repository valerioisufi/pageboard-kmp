package io.github.valerioisufi.drawview.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController

@Composable
internal actual fun rememberLiveInkLayer(controller: DrawViewController): LiveInkLayer =
    remember(controller) { ComposeLiveInkLayer() }
