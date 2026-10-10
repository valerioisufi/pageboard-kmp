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
 * @param canvasOverlay content drawn over the canvas (e.g. hints), the dialogs of the extension
 *   and the effects following the editor: composed with it, always; the canvas keeps receiving the
 *   pointer events outside of the interactive elements of the overlay.
 * @param commands the actions of the extension in the menus and the shortcuts of the editor (e.g.
 *   "Export…"): the same as its toolbar buttons, which can show them (the `command` of a `ToolButton`).
 * @param panels the panels of the extension (e.g. the lecture assistant): the editor places them
 *   beside the canvas or over it, by the space it has.
 * @param readOnlyNote why the document may only be read, when the extension knows (e.g. "Valerio’s
 *   Mac decides who edits"): shown by the editor beside "Read only".
 *
 * Several extensions are combined with `+` (e.g. an assistant and the collaboration).
 */
class EditorExtension(
    val toolbarActions: @Composable RowScope.(session: EditorSession, controller: DrawViewController) -> Unit = { _, _ -> },
    val canvasOverlay: @Composable BoxScope.(session: EditorSession, controller: DrawViewController) -> Unit = { _, _ -> },
    val commands: (session: EditorSession, controller: DrawViewController) -> List<Command> = { _, _ -> emptyList() },
    val panels: (session: EditorSession, controller: DrawViewController) -> List<EditorPanel> = { _, _ -> emptyList() },
    val readOnlyNote: @Composable (session: EditorSession, controller: DrawViewController) -> String? = { _, _ -> null }
) {
    /**
     * The contributions of this extension followed by those of [other]: toolbar actions, commands
     * and panels in this order, [other]'s overlay drawn over this one, the note of the read only
     * documents of this one if it has one.
     */
    operator fun plus(other: EditorExtension): EditorExtension {
        val first = this
        return EditorExtension(
            toolbarActions = { session, controller ->
                first.toolbarActions(this, session, controller)
                other.toolbarActions(this, session, controller)
            },
            canvasOverlay = { session, controller ->
                first.canvasOverlay(this, session, controller)
                other.canvasOverlay(this, session, controller)
            },
            commands = { session, controller -> first.commands(session, controller) + other.commands(session, controller) },
            panels = { session, controller -> first.panels(session, controller) + other.panels(session, controller) },
            readOnlyNote = { session, controller -> first.readOnlyNote(session, controller) ?: other.readOnlyNote(session, controller) }
        )
    }
}
