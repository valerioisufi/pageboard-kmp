package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable

/** No gesture of the stylus itself here (its button erases, see the canvas). */
@Composable
actual fun PencilInteractionEffect(onAction: (PencilAction) -> Unit) = Unit
