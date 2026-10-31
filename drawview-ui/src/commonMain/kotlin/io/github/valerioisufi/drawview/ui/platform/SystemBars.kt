package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable

/**
 * Gives the room of the system bars to the editor while it is shown, where it gains some: the
 * navigation bar (Android) and the home indicator (iOS) are hidden, the status bar only where no
 * notch, punch hole or island keeps its room taken anyway. They come back for a while with a
 * swipe, and are hidden again when the editor gets the focus back (after a menu or a dialog).
 */
@Composable
internal expect fun ImmersiveSystemBarsEffect()
