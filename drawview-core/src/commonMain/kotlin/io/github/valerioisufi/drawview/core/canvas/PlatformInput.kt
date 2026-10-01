package io.github.valerioisufi.drawview.core.canvas

import androidx.compose.ui.Modifier

/**
 * Requests the platform to deliver stylus input without batching it to the display refresh
 * rate (Android `View.requestUnbufferedDispatch`), lowering the inking latency.
 * No-op on the other platforms.
 */
expect fun Modifier.unbufferedStylusInput(): Modifier
