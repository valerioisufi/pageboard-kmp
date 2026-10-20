package io.github.valerioisufi.drawview.ui.extension

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * An action contributed by the application to a folder of the library (or the home), shown
 * in the context menu and action sheet of a folder, in the bar of the selection when a single
 * folder is selected, and in the header of the library.
 *
 * @param icon icon of its button.
 * @param label description of its button.
 * @param content shown once the action is chosen (e.g. a dialog) for the folder [folderId] (`null`: the home)
 *   named [folderName], until it calls [onDone].
 */
public class FolderAction(
    public val icon: ImageVector,
    public val label: @Composable () -> String,
    public val content: @Composable (folderId: String?, folderName: String, onDone: () -> Unit) -> Unit
)
