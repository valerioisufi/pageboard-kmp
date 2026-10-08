package io.github.valerioisufi.drawview.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent

/** A computer: a mouse or a trackpad and a keyboard (AWT does not report the touch screens). */
@Composable
actual fun platformInputHints(): InputHints = InputHints.PointerAndKeyboard

internal actual fun KeyEvent.isFromHardwareKeyboard(): Boolean = true

actual val platformConventions: PlatformConventions = PlatformConventions(
    shortcutModifier = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) {
        ShortcutModifier.COMMAND
    } else {
        ShortcutModifier.CONTROL
    }
)
