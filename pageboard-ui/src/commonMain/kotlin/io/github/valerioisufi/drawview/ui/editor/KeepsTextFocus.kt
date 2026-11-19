package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties

/**
 * A control of a bar of the texts that never takes the focus from the text being written: pressed
 * with a mouse, a focusable button would, and the field losing the focus drops its selection
 * (keeping only its end) before the button acts on it. Put before the `clickable` of the control.
 * The commands keep their shortcuts.
 */
internal fun Modifier.keepsTextFocus(): Modifier = focusProperties { canFocus = false }
