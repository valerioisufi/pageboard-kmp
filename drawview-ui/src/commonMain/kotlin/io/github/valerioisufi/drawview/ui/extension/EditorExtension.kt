package io.github.valerioisufi.drawview.ui.extension

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import io.github.valerioisufi.drawview.ui.command.Command

/**
 * Content contributed to the editor by the application (e.g. an assistant), composed with the
 * editor of every open document; each contribution receives the [EditorContext] of the document.
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
public class EditorExtension(
    public val toolbarActions: @Composable RowScope.(context: EditorContext) -> Unit = {},
    public val canvasOverlay: @Composable BoxScope.(context: EditorContext) -> Unit = {},
    public val commands: (context: EditorContext) -> List<Command> = { emptyList() },
    public val panels: (context: EditorContext) -> List<EditorPanel> = { emptyList() },
    public val readOnlyNote: @Composable (context: EditorContext) -> String? = { null }
) {
    /**
     * The contributions of this extension followed by those of [other]: toolbar actions, commands
     * and panels in this order, [other]'s overlay drawn over this one, the note of the read only
     * documents of this one if it has one.
     */
    public operator fun plus(other: EditorExtension): EditorExtension {
        val first = this
        return EditorExtension(
            toolbarActions = { context ->
                first.toolbarActions(this, context)
                other.toolbarActions(this, context)
            },
            canvasOverlay = { context ->
                first.canvasOverlay(this, context)
                other.canvasOverlay(this, context)
            },
            commands = { context -> first.commands(context) + other.commands(context) },
            panels = { context -> first.panels(context) + other.panels(context) },
            readOnlyNote = { context -> first.readOnlyNote(context) ?: other.readOnlyNote(context) }
        )
    }
}
