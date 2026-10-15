package io.github.valerioisufi.drawview.ui.extension

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * An action contributed by the application to the library (e.g. joining a document shared by
 * another device, its information). The library places it by the space it has: a button of its
 * bar, or of its navigation pane when [prominent].
 *
 * @param icon icon of its button.
 * @param label what it does, its tooltip and the text of a wide button.
 * @param prominent a wide button at the bottom of the navigation pane, where there is one.
 * @param content shown once the action is chosen (e.g. a dialog), until it calls `onDone`.
 */
class LibraryAction(
    val icon: ImageVector,
    val label: @Composable () -> String,
    val prominent: Boolean = false,
    val content: @Composable (onDone: () -> Unit) -> Unit
)
