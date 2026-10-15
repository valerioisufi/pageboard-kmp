package io.github.valerioisufi.drawview.ui.adaptive

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The windows the adaptive interface is designed and verified for (screenshot tests, previews):
 * one per kind of device and orientation, with the input it usually has.
 */
public enum class ReferenceWindow(public val width: Dp, public val height: Dp, public val input: InputHints) {
    PHONE(411.dp, 891.dp, InputHints.Touch),
    PHONE_LANDSCAPE(891.dp, 411.dp, InputHints.Touch),
    TABLET_PORTRAIT(800.dp, 1280.dp, InputHints.Stylus),
    TABLET_SPLIT_SCREEN(640.dp, 800.dp, InputHints.Stylus),
    TABLET_LANDSCAPE(1280.dp, 800.dp, InputHints.Stylus),
    DESKTOP_NARROW(720.dp, 800.dp, InputHints.PointerAndKeyboard),
    DESKTOP(1440.dp, 900.dp, InputHints.PointerAndKeyboard);

    /** The size classes of this window. */
    public val layout: WindowLayout get() = WindowLayout.of(width, height)
}
