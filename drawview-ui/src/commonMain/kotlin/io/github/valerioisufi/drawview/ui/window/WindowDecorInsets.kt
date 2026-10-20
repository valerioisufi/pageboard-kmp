package io.github.valerioisufi.drawview.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Insets required for platform window controls, such as macOS traffic lights (close,
 * minimize, zoom) when unified full window content is enabled.
 */
public val LocalWindowDecorInsets: ProvidableCompositionLocal<PaddingValues> =
    staticCompositionLocalOf { PaddingValues(0.dp) }

/**
 * Composition local providing a draggable container for window chrome.
 * On desktop JVM, this delegates to WindowDraggableArea. On touch platforms, this renders
 * as a standard Box.
 */
public val LocalWindowDraggableArea: ProvidableCompositionLocal<@Composable (Modifier, @Composable () -> Unit) -> Unit> =
    staticCompositionLocalOf { { modifier, content -> Box(modifier) { content() } } }

/**
 * Wraps [content] in an area that can be dragged to move the application window on desktop platforms.
 */
@Composable
public fun WindowDraggable(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    LocalWindowDraggableArea.current(modifier, content)
}
