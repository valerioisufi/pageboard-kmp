package io.github.valerioisufi.drawview.explorer.platform

import androidx.compose.runtime.Composable

/**
 * Hides the system bars while [hidden] (they reappear temporarily with a swipe), where the
 * platform has them.
 */
@Composable
internal expect fun SystemBarsHidden(hidden: Boolean)
