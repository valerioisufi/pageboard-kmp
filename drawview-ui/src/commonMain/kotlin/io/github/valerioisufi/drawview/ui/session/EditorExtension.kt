package io.github.valerioisufi.drawview.ui.session

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import io.github.valerioisufi.drawview.core.controller.DrawViewController

/**
 * Content contributed to the editor by the application (e.g. an assistant), composed with the
 * editor of every open document.
 *
 * @param toolbarActions actions added to the top bar of the editor, before the drawing mode toggle.
 * @param sidePanel panel shown at the end of the canvas; composed even when it draws nothing, so
 *   that it can follow the lifecycle of the editor.
 * @param canvasOverlay content drawn over the canvas (e.g. hints); the canvas keeps receiving the
 *   pointer events outside of the interactive elements of the overlay.
 */
class EditorExtension(
    val toolbarActions: @Composable RowScope.(session: EditorSession, controller: DrawViewController) -> Unit = { _, _ -> },
    val sidePanel: @Composable (session: EditorSession, controller: DrawViewController) -> Unit = { _, _ -> },
    val canvasOverlay: @Composable BoxScope.(session: EditorSession, controller: DrawViewController) -> Unit = { _, _ -> }
)
