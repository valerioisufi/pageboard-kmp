package io.github.valerioisufi.pageboard.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController

@Composable
internal actual fun rememberLiveInkLayer(controller: PageboardController): LiveInkLayer =
    remember(controller) { ComposeLiveInkLayer() }
