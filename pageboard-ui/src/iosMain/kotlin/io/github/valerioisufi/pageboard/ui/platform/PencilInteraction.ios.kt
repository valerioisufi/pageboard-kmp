package io.github.valerioisufi.pageboard.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import platform.UIKit.UIPencilInteraction
import platform.UIKit.UIPencilInteractionDelegateProtocol
import platform.UIKit.UIPencilInteractionPhase
import platform.UIKit.UIPencilInteractionSqueeze
import platform.UIKit.UIPencilPreferredAction
import platform.UIKit.UIPencilPreferredActionShowColorPalette
import platform.UIKit.UIPencilPreferredActionShowContextualPalette
import platform.UIKit.UIPencilPreferredActionShowInkAttributes
import platform.UIKit.UIPencilPreferredActionSwitchEraser
import platform.UIKit.UIPencilPreferredActionSwitchPrevious
import platform.UIKit.addInteraction
import platform.UIKit.removeInteraction
import platform.darwin.NSObject

/** The double tap and the squeeze of the Apple Pencil on the view of the composition. */
@Composable
internal actual fun PencilInteractionEffect(onAction: (PencilAction) -> Unit) {
    val viewController = LocalUIViewController.current
    val current by rememberUpdatedState(onAction)
    DisposableEffect(viewController) {
        val delegate = PencilDelegate { current(it) }
        val interaction = UIPencilInteraction()
        interaction.delegate = delegate
        viewController.view.addInteraction(interaction)
        onDispose {
            viewController.view.removeInteraction(interaction)
            interaction.delegate = null
        }
    }
}

/** Gives the preferred actions of the system for the gestures of the Pencil. */
private class PencilDelegate(private val onAction: (PencilAction) -> Unit) : NSObject(), UIPencilInteractionDelegateProtocol {

    /** The double tap (every Pencil with it). */
    override fun pencilInteractionDidTap(interaction: UIPencilInteraction) {
        onAction(UIPencilInteraction.preferredTapAction.toPencilAction())
    }

    /** The squeeze of the Apple Pencil Pro, once it ends. */
    override fun pencilInteraction(interaction: UIPencilInteraction, didReceiveSqueeze: UIPencilInteractionSqueeze) {
        if (didReceiveSqueeze.phase == UIPencilInteractionPhase.UIPencilInteractionPhaseEnded) {
            onAction(UIPencilInteraction.preferredSqueezeAction.toPencilAction())
        }
    }
}

private fun UIPencilPreferredAction.toPencilAction(): PencilAction = when (this) {
    UIPencilPreferredActionSwitchEraser -> PencilAction.SWITCH_ERASER
    UIPencilPreferredActionSwitchPrevious -> PencilAction.SWITCH_PREVIOUS
    UIPencilPreferredActionShowColorPalette, UIPencilPreferredActionShowInkAttributes, UIPencilPreferredActionShowContextualPalette -> PencilAction.SHOW_PALETTE
    else -> PencilAction.NONE
}
