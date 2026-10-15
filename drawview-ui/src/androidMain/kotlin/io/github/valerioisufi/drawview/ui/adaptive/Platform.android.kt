package io.github.valerioisufi.drawview.ui.adaptive

import android.content.res.Configuration
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalConfiguration

/**
 * The touch screen and the hardware keyboard of the configuration (which changes when a keyboard
 * is plugged in), the styluses and mice among the input devices.
 */
@Composable
internal actual fun platformInputHints(): InputHints {
    val configuration = LocalConfiguration.current
    return remember(configuration.touchscreen, configuration.keyboard, configuration.hardKeyboardHidden) {
        val devices = InputDevice.getDeviceIds().asList().mapNotNull { InputDevice.getDevice(it) }.filterNot { it.isVirtual }
        InputHints(
            touch = configuration.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH,
            stylus = devices.any { it.supportsSource(InputDevice.SOURCE_STYLUS) },
            precisePointer = devices.any { it.supportsSource(InputDevice.SOURCE_MOUSE) },
            keyboard = configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
                configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        )
    }
}

/** The soft keyboards send some keys too (delete, enter) from a virtual device. */
internal actual fun KeyEvent.isFromHardwareKeyboard(): Boolean {
    val device = nativeKeyEvent.device ?: return false
    return !device.isVirtual && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
}

public actual val platformConventions: PlatformConventions = PlatformConventions(
    shortcutModifier = ShortcutModifier.CONTROL
)
