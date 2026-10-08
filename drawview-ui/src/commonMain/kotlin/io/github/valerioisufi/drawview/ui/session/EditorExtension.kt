package io.github.valerioisufi.drawview.ui.session

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.ui.command.Command

/**
 * Content contributed to the editor by the application (e.g. an assistant), composed with the
 * editor of every open document.
 *
 * @param toolbarActions actions added to the top bar of the editor, before the drawing mode toggle.
 * @param sidePanel panel shown at the end of the canvas; composed even when it draws nothing, so
 *   that it can follow the lifecycle of the editor.
 * @param canvasOverlay content drawn over the canvas (e.g. hints), and the dialogs of the
 *   extension (always composed with the editor); the canvas keeps receiving the pointer events
 *   outside of the interactive elements of the overlay.
 * @param commands the actions of the extension in the menus and the shortcuts of the editor (e.g.
 *   "Export…"): the same as its toolbar buttons, which can show them (the `command` of a `ToolButton`).
 *
 * Several extensions are combined with `+` (e.g. an assistant and the collaboration).
 */
class EditorExtension(
    val toolbarActions: @Composable RowScope.(session: EditorSession, controller: DrawViewController) -> Unit = { _, _ -> },
    val sidePanel: @Composable (session: EditorSession, controller: DrawViewController) -> Unit = { _, _ -> },
    val canvasOverlay: @Composable BoxScope.(session: EditorSession, controller: DrawViewController) -> Unit = { _, _ -> },
    val commands: (session: EditorSession, controller: DrawViewController) -> List<Command> = { _, _ -> emptyList() }
) {
    /**
     * The contributions of this extension followed by those of [other]: toolbar actions and
     * commands in this order, side panels side by side, [other]'s overlay drawn over this one.
     */
    operator fun plus(other: EditorExtension): EditorExtension {
        val first = this
        return EditorExtension(
            toolbarActions = { session, controller ->
                first.toolbarActions(this, session, controller)
                other.toolbarActions(this, session, controller)
            },
            sidePanel = { session, controller ->
                first.sidePanel(session, controller)
                other.sidePanel(session, controller)
            },
            canvasOverlay = { session, controller ->
                first.canvasOverlay(this, session, controller)
                other.canvasOverlay(this, session, controller)
            },
            commands = { session, controller -> first.commands(session, controller) + other.commands(session, controller) }
        )
    }
}
