package io.github.valerioisufi.drawview.ui.extension

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.valerioisufi.drawview.ui.command.Shortcut

/** The items of the library an action runs on. */
public sealed interface LibrarySelection {
    /**
     * The documents [documentIds] and the folders [folderIds] selected (or the item of a menu);
     * [thumbnail] gives the image of the first page of the first document, if there is one.
     */
    public class Items(
        public val documentIds: List<String>,
        public val folderIds: List<String>,
        public val thumbnail: (suspend () -> ImageBitmap?)? = null
    ) : LibrarySelection

    /** The folder shown, [folderId] (`null`: the Home), when nothing is selected. */
    public class Folder(public val folderId: String?) : LibrarySelection
}

/**
 * An action contributed by the application to the items of the library (e.g. their export): on
 * the items selected (the bar of the selection, the pane of the details), on an item (its context
 * menu and its sheet), and on the folder shown (the menu of its title).
 *
 * @param icon icon of its buttons.
 * @param label description of its buttons on items.
 * @param folderLabel description of its button on the folder shown, given whether it is the Home.
 * @param shortcut its shortcut in the library: on the items selected, else on the folder shown.
 * @param content shown once the action is chosen (e.g. a sheet) for the `selection`, until it
 *   calls `onDone`.
 */
public class LibraryItemsAction(
    public val icon: ImageVector,
    public val label: @Composable () -> String,
    public val folderLabel: @Composable (home: Boolean) -> String = { label() },
    public val shortcut: Shortcut? = null,
    public val content: @Composable (selection: LibrarySelection, onDone: () -> Unit) -> Unit
)
