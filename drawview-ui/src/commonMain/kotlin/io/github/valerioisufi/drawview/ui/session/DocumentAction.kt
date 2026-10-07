package io.github.valerioisufi.drawview.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * An action contributed by the application to the documents of the library (e.g. their export),
 * shown in the bar of the selection when a single document is selected.
 *
 * @param icon icon of its button.
 * @param label description of its button.
 * @param content shown once the action is chosen (e.g. a dialog) for the document `documentId`
 *   named `title`, until it calls `onDone`.
 */
class DocumentAction(
    val icon: ImageVector,
    val label: @Composable () -> String,
    val content: @Composable (documentId: String, title: String, onDone: () -> Unit) -> Unit
)
