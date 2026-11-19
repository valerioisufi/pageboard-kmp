package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable

/** What a gesture of the stylus itself asks for (a double tap, a squeeze of the Apple Pencil). */
internal enum class PencilAction {
    /** The eraser, or back to the tool before it. */
    SWITCH_ERASER,

    /** The tool chosen before. */
    SWITCH_PREVIOUS,

    /** The palette of the tool (its sizes and colors). */
    SHOW_PALETTE,

    /** Nothing (the user chose so). */
    NONE
}

/**
 * Calls [onAction] with the action the user chose in the settings of the system for a gesture of
 * the stylus: the double tap and the squeeze of the Apple Pencil on iOS. Nothing on the other
 * platforms.
 */
@Composable
internal expect fun PencilInteractionEffect(onAction: (PencilAction) -> Unit)
