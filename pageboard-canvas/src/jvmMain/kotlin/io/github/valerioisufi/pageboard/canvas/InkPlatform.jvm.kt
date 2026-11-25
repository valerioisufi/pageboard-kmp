package io.github.valerioisufi.pageboard.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import java.awt.DisplayMode
import java.awt.GraphicsEnvironment

/**
 * The ink on the desktop: AWT gives no samples between the events and no prediction, so the Kalman
 * filter predicts as far as a frame of the main display (60 Hz without a display, in the tests).
 */
@Composable
internal actual fun rememberInkPlatform(controller: PageboardController): InkPlatform =
    remember(controller) { CommonInkPlatform(controller, mainDisplayRefreshRate()) }

internal actual fun Modifier.inkInput(platform: InkPlatform, fingerDraws: () -> Boolean): Modifier = this

/** The refresh rate of the main display in Hz, 0 when it is not known (no display, a rate AWT does not know). */
private fun mainDisplayRefreshRate(): Float = runCatching {
    if (GraphicsEnvironment.isHeadless()) return@runCatching 0f
    val rate = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate
    if (rate == DisplayMode.REFRESH_RATE_UNKNOWN) 0f else rate.toFloat()
}.getOrDefault(0f)
