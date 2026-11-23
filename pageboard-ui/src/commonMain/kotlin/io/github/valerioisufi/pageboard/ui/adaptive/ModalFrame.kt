package io.github.valerioisufi.pageboard.ui.adaptive

import androidx.compose.runtime.Composable

/**
 * How a sheet of the application ([AdaptiveSheet]) opens: chosen from the window and from what
 * the user has at hand, never from the platform (docs/plans/small-screen-modals.md).
 */
public enum class ModalFrame {
    /** Rising from the bottom, dragged down to close: a narrow window touched with a finger (a phone). */
    BottomSheet,

    /** A dialog in the middle of the window, bounded by it: a wider window (a tablet, a desktop, a phone in landscape). */
    Dialog,

    /**
     * A dialog taking the window less a small margin: a narrow window reached with a mouse (a
     * desktop window made narrow), where a sheet to drag would be out of place.
     */
    FullWindowDialog;

    public companion object {
        /** The frame for [info]: its window and its input. */
        public fun of(info: AdaptiveInfo): ModalFrame = of(info.window, touch = info.input.hasTouch || info.input.hasStylus)

        /** The frame for [window], [touch] when a finger or a stylus can reach the screen. */
        internal fun of(window: WindowLayout, touch: Boolean): ModalFrame = when {
            !window.isCompact -> Dialog
            touch -> BottomSheet
            else -> FullWindowDialog
        }
    }
}

/** The [ModalFrame] of the composition. */
@Composable
public fun currentModalFrame(): ModalFrame = ModalFrame.of(currentAdaptiveInfo())
