package io.github.valerioisufi.pageboard.ui.adaptive

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bounds the height of a popup or a dialog to the window less [margin] above and below. Its
 * content scrolling (the `verticalScroll` of the `DropdownMenu` of Material, the content of an
 * [AdaptiveSheet]) is then measured with a bounded height, which the popups and the dialogs of iOS
 * do not give it in landscape: a menu taller than the window stopped the application, and a dialog
 * taller than it was cut instead of scrolling (see docs/plans/ios-stability.md).
 */
@Composable
internal fun Modifier.windowHeightLimit(margin: Dp = WindowMargin): Modifier {
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    return heightIn(max = (height - margin * 2).coerceAtLeast(MinHeight))
}

/** The room a popup leaves to the window above and below it. */
private val WindowMargin: Dp = 16.dp

/** The lowest a popup is bounded to, in a window too low for its margins. */
private val MinHeight: Dp = 48.dp
