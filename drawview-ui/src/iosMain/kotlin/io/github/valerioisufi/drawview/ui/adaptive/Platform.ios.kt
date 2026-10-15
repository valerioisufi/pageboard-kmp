package io.github.valerioisufi.drawview.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import platform.UIKit.UIPencilInteraction

/**
 * A touch screen; an Apple Pencil when the user chose to draw only with it (Settings › Apple
 * Pencil). A keyboard and a trackpad are learned from their events.
 */
@Composable
internal actual fun platformInputHints(): InputHints = remember {
    InputHints(touch = true, stylus = UIPencilInteraction.prefersPencilOnlyDrawing)
}

/** The soft keyboard types through the text input, not through key events. */
internal actual fun KeyEvent.isFromHardwareKeyboard(): Boolean = true

public actual val platformConventions: PlatformConventions = PlatformConventions(
    shortcutModifier = ShortcutModifier.COMMAND
)
