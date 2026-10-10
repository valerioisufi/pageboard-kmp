package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileItem
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.SortOption
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_back
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_delete
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_move_to
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_open
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_rename
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_select_all
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_command_search
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_command_sort_by
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_dialog_new_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_dialog_new_note
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_date_created
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_folders_on_top
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_invert
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_last_modified
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_last_opened
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_name
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandGroup
import io.github.valerioisufi.drawview.ui.command.Shortcut
import io.github.valerioisufi.drawview.ui.generated.resources.about_dialog_title
import io.github.valerioisufi.drawview.ui.session.DocumentAction
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import io.github.valerioisufi.drawview.ui.generated.resources.Res as DrawViewRes

/** A dialog of the library, opened by its commands (one at a time). */
internal sealed interface LibraryDialog {
    class NewItem(val type: FileType) : LibraryDialog
    class Rename(val item: FileItem) : LibraryDialog
    class Move(val items: List<FileItem>) : LibraryDialog
    class Delete(val items: List<FileItem>) : LibraryDialog
    data object About : LibraryDialog
}

/**
 * The commands of the library: of the folder shown (create, select, sort, go up), of the selected
 * items and of an item ([forItem], its context menu and its sheet). The view model is the expert
 * of the items; the screen shows the dialogs ([LibraryDialog]) and runs the actions of the
 * application on a document ([DocumentAction]).
 */
@Stable
class LibraryCommands internal constructor(
    private val viewModel: FileExplorerViewModel,
    private val openDocument: (String) -> Unit,
    private val documentActions: List<DocumentAction>,
    private val showDialog: (LibraryDialog) -> Unit,
    private val chooseAction: (DocumentAction, FileItem) -> Unit,
    private val focusSearch: () -> Unit
) {
    private val selection: List<FileItem> get() = viewModel.selectedItems.toList()

    // ----- File -----

    val newNote = Command(
        "new-note", label(Res.string.explorer_dialog_new_note), CommandGroup.FILE,
        icon = { rememberVectorPainter(DrawViewIcons.FilePlus) },
        shortcuts = listOf(Shortcut(Key.N, primary = true))
    ) { showDialog(LibraryDialog.NewItem(FileType.FILE)) }

    val newFolder = Command(
        "new-folder", label(Res.string.explorer_dialog_new_folder), CommandGroup.FILE,
        icon = { rememberVectorPainter(DrawViewIcons.FolderPlus) },
        shortcuts = listOf(Shortcut(Key.N, primary = true, shift = true))
    ) { showDialog(LibraryDialog.NewItem(FileType.FOLDER)) }

    /** Moves the focus to the search of the library. */
    val search = Command(
        "search", label(Res.string.explorer_command_search), CommandGroup.EDIT, section = 2,
        icon = { rememberVectorPainter(DrawViewIcons.Search) },
        shortcuts = listOf(Shortcut(Key.K, primary = true), Shortcut(Key.F, primary = true))
    ) { focusSearch() }

    /** The actions of the application on the document selected alone (e.g. "Export…"). */
    val documentActionCommands: List<Command> = documentActions.mapIndexed { index, action ->
        Command(
            DocumentActionPrefix + index, action.label, CommandGroup.FILE, section = 1,
            icon = { rememberVectorPainter(action.icon) },
            isEnabled = { selectedDocument != null }
        ) { selectedDocument?.let { chooseAction(action, it) } }
    }

    private val selectedDocument: FileItem? get() = selection.singleOrNull()?.takeIf { it.type == FileType.FILE }

    // ----- Edit -----

    val selectAll = Command(
        "select-all", label(Res.string.explorer_action_select_all), CommandGroup.EDIT,
        shortcuts = listOf(Shortcut(Key.A, primary = true)),
        isEnabled = { viewModel.currentDirectoryFiles.isNotEmpty() }
    ) { viewModel.selectAll() }

    val rename = Command(
        "rename", label(Res.string.explorer_action_rename), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(DrawViewIcons.Pencil) },
        isEnabled = { selection.size == 1 }
    ) { selection.singleOrNull()?.let { showDialog(LibraryDialog.Rename(it)) } }

    val move = Command(
        "move", label(Res.string.explorer_action_move_to), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(DrawViewIcons.FolderInput) },
        isEnabled = { selection.isNotEmpty() }
    ) { showDialog(LibraryDialog.Move(selection)) }

    val delete = Command(
        "delete", label(Res.string.explorer_action_delete), CommandGroup.EDIT, section = 1,
        icon = { rememberVectorPainter(DrawViewIcons.Trash) },
        shortcuts = listOf(Shortcut(Key.Backspace), Shortcut(Key.Delete)),
        isEnabled = { selection.isNotEmpty() },
        destructive = true
    ) { showDialog(LibraryDialog.Delete(selection)) }

    // ----- View -----

    val enclosingFolder = Command(
        "enclosing-folder", label(Res.string.explorer_action_back), CommandGroup.VIEW,
        shortcuts = listOf(Shortcut(Key.DirectionUp, primary = true)),
        isEnabled = { viewModel.currentFolder != null }
    ) { viewModel.backFolder() }

    val sortBy: List<Command> = listOf(
        SortOption.NAME to Res.string.explorer_sort_name,
        SortOption.DATE_CREATED to Res.string.explorer_sort_date_created,
        SortOption.DATE_MODIFIED to Res.string.explorer_sort_last_modified,
        SortOption.LAST_OPENED to Res.string.explorer_sort_last_opened
    ).map { (option, name) ->
        Command(
            "sort-${option.name.lowercase()}",
            { stringResource(Res.string.explorer_command_sort_by, stringResource(name)) },
            CommandGroup.VIEW, section = 1,
            isChecked = { viewModel.sortOption == option }
        ) { viewModel.selectSortOption(option) }
    }

    val invertOrder = Command(
        "invert-order", label(Res.string.explorer_sort_invert), CommandGroup.VIEW, section = 2,
        isChecked = { !viewModel.sortAscending }
    ) { viewModel.toggleSortDirection() }

    val foldersOnTop = Command(
        "folders-on-top", label(Res.string.explorer_sort_folders_on_top), CommandGroup.VIEW, section = 2,
        isChecked = { viewModel.keepFoldersOnTop }
    ) { viewModel.toggleKeepFoldersOnTop() }

    // ----- Help -----

    val about = Command("about", label(DrawViewRes.string.about_dialog_title), CommandGroup.HELP) {
        showDialog(LibraryDialog.About)
    }

    /** The commands of the folder shown and of the selection, in the order of the menus. */
    val all: List<Command> = listOf(newNote, newFolder) + documentActionCommands +
        listOf(selectAll, rename, move, delete, search, enclosingFolder) + sortBy + listOf(invertOrder, foldersOnTop, about)

    /**
     * The commands of [item] (its context menu, its sheet): open, rename, move, the actions of the
     * application, delete.
     */
    fun forItem(item: FileItem): List<Command> = listOf(
        Command(
            "open", label(Res.string.explorer_action_open), CommandGroup.FILE,
            icon = { rememberVectorPainter(if (item.type == FileType.FILE) DrawViewIcons.FileText else DrawViewIcons.FolderOpen) }
        ) {
            when (item.type) {
                FileType.FILE -> openDocument(item.id)
                FileType.FOLDER -> viewModel.enterFolder(item)
            }
        },
        Command("rename", rename.label, CommandGroup.EDIT, section = 1, icon = rename.icon) {
            showDialog(LibraryDialog.Rename(item))
        },
        Command("move", move.label, CommandGroup.EDIT, section = 1, icon = move.icon) {
            showDialog(LibraryDialog.Move(listOf(item)))
        }
    ) + (if (item.type == FileType.FILE) {
        documentActions.mapIndexed { index, action ->
            Command(DocumentActionPrefix + index, action.label, CommandGroup.FILE, section = 2, icon = { rememberVectorPainter(action.icon) }) {
                chooseAction(action, item)
            }
        }
    } else {
        emptyList()
    }) + Command("delete", delete.label, CommandGroup.EDIT, section = 3, icon = delete.icon, destructive = true) {
        showDialog(LibraryDialog.Delete(listOf(item)))
    }
}

private fun label(resource: StringResource): @Composable () -> String = { stringResource(resource) }

/** The commands of the library of [viewModel], running the last actions given (the screen recomposing). */
@Composable
internal fun rememberLibraryCommands(
    viewModel: FileExplorerViewModel,
    documentActions: List<DocumentAction>,
    onOpenDocument: (String) -> Unit,
    onShowDialog: (LibraryDialog) -> Unit,
    onChooseAction: (DocumentAction, FileItem) -> Unit,
    onFocusSearch: () -> Unit
): LibraryCommands {
    val open by rememberUpdatedState(onOpenDocument)
    val show by rememberUpdatedState(onShowDialog)
    val choose by rememberUpdatedState(onChooseAction)
    val focus by rememberUpdatedState(onFocusSearch)
    return remember(viewModel, documentActions) {
        LibraryCommands(
            viewModel = viewModel,
            openDocument = { open(it) },
            documentActions = documentActions,
            showDialog = { show(it) },
            chooseAction = { action, item -> choose(action, item) },
            focusSearch = { focus() }
        )
    }
}
