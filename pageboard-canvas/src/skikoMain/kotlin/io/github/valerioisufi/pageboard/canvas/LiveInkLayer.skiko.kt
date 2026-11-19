package io.github.valerioisufi.pageboard.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController

@Composable
internal actual fun rememberLiveInkLayer(controller: DrawViewController): LiveInkLayer =
    remember(controller) { ComposeLiveInkLayer() }
