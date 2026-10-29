package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.Section
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.SortOption
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.ViewMode
import io.github.valerioisufi.drawview.explorer.LibraryFilter
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_back
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_clear_selection
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_select_all
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_empty_library_subtitle
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_filter_all
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_filter_notes
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_folders_count
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_more_actions
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_notes_count
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_root_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_search_clear
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_search_placeholder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_section_recent
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_section_shared
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_selected_count
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_by
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_date_created
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_folders_on_top
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_invert
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_last_modified
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_last_opened
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_name
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sorted_by_date_created
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sorted_by_last_modified
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sorted_by_last_opened
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sorted_by_name
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_section_trash
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_open
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_items_count
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_view_grid
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_view_list
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.command.CommandMenu
import io.github.valerioisufi.drawview.ui.command.DesignMenu
import io.github.valerioisufi.drawview.ui.command.MenuDivider
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.command.MenuLabel
import io.github.valerioisufi.drawview.ui.components.ChipRow
import io.github.valerioisufi.drawview.ui.components.DesignFilterChip
import io.github.valerioisufi.drawview.ui.components.DesignIconButton
import io.github.valerioisufi.drawview.ui.components.IconButtonStyle
import io.github.valerioisufi.drawview.ui.components.SearchField
import io.github.valerioisufi.drawview.ui.extension.LibraryAction
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The bar of a narrow library: the brand of the application ([brand]), the search where there is
 * room for it ([search]), the Trash ([onOpenTrash], where there is no navigation pane holding it),
 * the actions of the application as icons.
 */
@Composable
internal fun LibraryAppBar(
    brand: @Composable () -> Unit,
    actions: List<LibraryAction>,
    onAction: (LibraryAction) -> Unit,
    modifier: Modifier = Modifier,
    onOpenTrash: (() -> Unit)? = null,
    search: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) { brand() }
        search?.invoke(this)
        if (onOpenTrash != null) DesignIconButton(DrawViewIcons.Trash, stringResource(Res.string.explorer_trash_open), onClick = onOpenTrash)
        for (action in actions) {
            DesignIconButton(action.icon, action.label(), onClick = { onAction(action) })
        }
    }
}

/** The search of the library, with the shortcut of its command where there is a keyboard. */
@Composable
internal fun LibrarySearchField(
    viewModel: FileExplorerViewModel,
    commands: LibraryCommands,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val adaptive = currentAdaptiveInfo()
    SearchField(
        query = viewModel.searchQuery,
        onQueryChange = viewModel::search,
        placeholder = stringResource(Res.string.explorer_search_placeholder),
        shortcut = commands.search.shortcutLabel(adaptive.conventions).takeIf { adaptive.input.hasKeyboard },
        clearLabel = stringResource(Res.string.explorer_search_clear),
        focusRequester = focusRequester,
        modifier = modifier
    )
}

/**
 * The title of what the library shows (the root, a folder, a section) and what it holds: the
 * notes and the folders with their order (`withOrder`), or the path of a folder; inside a folder,
 * the button going up to the one holding it.
 */
@Composable
internal fun LibraryTitle(
    viewModel: FileExplorerViewModel,
    commands: LibraryCommands,
    compact: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val folder = viewModel.currentFolder
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (viewModel.section == Section.TRASH) {
            DesignIconButton(
                icon = DrawViewIcons.ArrowUp,
                label = stringResource(Res.string.explorer_action_back),
                onClick = { viewModel.showFolder(null) },
                style = IconButtonStyle.Tonal
            )
        }
        if (folder != null && viewModel.section == Section.HOME) {
            DesignIconButton(
                icon = DrawViewIcons.ArrowUp,
                label = stringResource(Res.string.explorer_action_back),
                onClick = { commands.enclosingFolder.performIfEnabled() },
                style = IconButtonStyle.Tonal,
                shortcut = commands.enclosingFolder.shortcutLabel(currentAdaptiveInfo().conventions)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = when {
                    viewModel.section == Section.RECENT -> stringResource(Res.string.explorer_section_recent)
                    viewModel.section == Section.SHARED -> stringResource(Res.string.explorer_section_shared)
                    viewModel.section == Section.TRASH -> stringResource(Res.string.explorer_section_trash)
                    folder != null -> folder.name
                    else -> stringResource(Res.string.explorer_root_folder)
                },
                style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = librarySubtitle(viewModel, withOrder = !compact),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing()
    }
}

/** "7 notes · 3 folders · sorted by name", "Home › Analysis · 5 notes", "4 notes". */
@Composable
private fun librarySubtitle(viewModel: FileExplorerViewModel, withOrder: Boolean): String {
    if (viewModel.section == Section.TRASH) return pluralStringResource(Res.plurals.explorer_items_count, viewModel.trash.size, viewModel.trash.size)
    val content = viewModel.currentDirectoryFiles
    val notes = if (viewModel.section == Section.HOME) content.count { it.type == FileType.FILE } else viewModel.shownFiles.size
    val folders = if (viewModel.section == Section.HOME) content.count { it.type == FileType.FOLDER } else 0
    val notesText = pluralStringResource(Res.plurals.explorer_notes_count, notes, notes)
    val path = viewModel.currentPath
    if (viewModel.section == Section.HOME && path.isNotEmpty()) {
        val home = stringResource(Res.string.explorer_root_folder)
        return (listOf(home) + path.map { it.name }).joinToString(" › ") + " · " + notesText
    }
    if (viewModel.section == Section.HOME && notes == 0 && folders == 0) return stringResource(Res.string.explorer_empty_library_subtitle)
    val parts = mutableListOf(notesText)
    if (folders > 0) parts += pluralStringResource(Res.plurals.explorer_folders_count, folders, folders)
    if (withOrder) {
        parts += stringResource(
            when (viewModel.sortOption) {
                SortOption.NAME -> Res.string.explorer_sorted_by_name
                SortOption.DATE_CREATED -> Res.string.explorer_sorted_by_date_created
                SortOption.DATE_MODIFIED -> Res.string.explorer_sorted_by_last_modified
                SortOption.LAST_OPENED -> Res.string.explorer_sorted_by_last_opened
            }
        )
    }
    return parts.joinToString(" · ")
}

/** The views of the notes (cards or rows) and the button of their order, with its menu. */
@Composable
internal fun LibraryViewControls(viewModel: FileExplorerViewModel, commands: LibraryCommands, showViews: Boolean) {
    // The trash has its rows, in their order
    if (viewModel.section == Section.TRASH) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showViews) {
            DesignIconButton(
                icon = DrawViewIcons.PageGrid,
                label = stringResource(Res.string.explorer_view_grid),
                onClick = { viewModel.viewMode = ViewMode.GRID },
                selected = viewModel.viewMode == ViewMode.GRID
            )
            DesignIconButton(
                icon = DrawViewIcons.List,
                label = stringResource(Res.string.explorer_view_list),
                onClick = { viewModel.viewMode = ViewMode.LIST },
                selected = viewModel.viewMode == ViewMode.LIST
            )
        }
        SortButton(viewModel)
        FolderActionsButton(viewModel, commands)
    }
}

/**
 * The menu of the folder shown (or of the Home): the actions of the application on it, e.g.
 * "Export folder…". Nothing when there are none, or outside the folders (recent, shared, trash).
 */
@Composable
internal fun FolderActionsButton(viewModel: FileExplorerViewModel, commands: LibraryCommands) {
    if (viewModel.section != Section.HOME || commands.folderActionCommands.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        DesignIconButton(DrawViewIcons.MoreHorizontal, stringResource(Res.string.explorer_more_actions), { expanded = true }, style = IconButtonStyle.Tonal)
        CommandMenu(commands = commands.folderActionCommands, expanded = expanded, onDismissRequest = { expanded = false })
    }
}

/** The button of the order of the items and its menu: the property, the direction, the folders first. */
@Composable
internal fun SortButton(viewModel: FileExplorerViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val check = rememberVectorPainter(DrawViewIcons.Check)
    Box {
        DesignIconButton(DrawViewIcons.ArrowUpDown, stringResource(Res.string.explorer_sort), { expanded = true }, style = IconButtonStyle.Tonal)
        DesignMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuLabel(stringResource(Res.string.explorer_sort_by))
            listOf(
                SortOption.NAME to Res.string.explorer_sort_name,
                SortOption.DATE_CREATED to Res.string.explorer_sort_date_created,
                SortOption.DATE_MODIFIED to Res.string.explorer_sort_last_modified,
                SortOption.LAST_OPENED to Res.string.explorer_sort_last_opened
            ).forEach { (option, label) ->
                val selected = viewModel.sortOption == option
                MenuItem(
                    text = stringResource(label),
                    onClick = {
                        viewModel.selectSortOption(option)
                        expanded = false
                    },
                    icon = check.takeIf { selected },
                    selected = selected,
                    alignWithIcons = true
                )
            }
            MenuDivider()
            MenuItem(
                text = stringResource(Res.string.explorer_sort_invert),
                onClick = {
                    viewModel.toggleSortDirection()
                    expanded = false
                },
                icon = check.takeIf { !viewModel.sortAscending },
                alignWithIcons = true
            )
            MenuItem(
                text = stringResource(Res.string.explorer_sort_folders_on_top),
                onClick = {
                    viewModel.toggleKeepFoldersOnTop()
                    expanded = false
                },
                icon = check.takeIf { viewModel.keepFoldersOnTop },
                alignWithIcons = true
            )
        }
    }
}

/** The filters of the notes: all, the notes written, the tags of the library (PDFs, lectures, shared). */
@Composable
internal fun LibraryFilterChips(viewModel: FileExplorerViewModel, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    ChipRow(modifier = modifier, contentPadding = contentPadding) {
        DesignFilterChip(stringResource(Res.string.explorer_filter_all), viewModel.filter == LibraryFilter.All, { viewModel.selectFilter(LibraryFilter.All) })
        DesignFilterChip(stringResource(Res.string.explorer_filter_notes), viewModel.filter == LibraryFilter.Notes, { viewModel.selectFilter(LibraryFilter.Notes) })
        for (tag in viewModel.allTags) {
            val filter = LibraryFilter.Tagged(tag.id)
            DesignFilterChip(tag.filterLabel(), viewModel.filter == filter, { viewModel.selectFilter(filter) })
        }
    }
}

/**
 * The bar of the selection: how many items are selected, clearing it, selecting all; where there
 * is no pane of the details, the actions on them (move, delete, those of the application).
 */
@Composable
internal fun SelectionBar(viewModel: FileExplorerViewModel, commands: LibraryCommands, withActions: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DesignIconButton(DrawViewIcons.Close, stringResource(Res.string.explorer_action_clear_selection), { viewModel.clearSelection() })
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            stringResource(Res.string.explorer_selected_count, viewModel.selectedItems.size),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = if (withActions) Modifier.weight(1f) else Modifier
        )
        if (withActions) {
            DesignIconButton(DrawViewIcons.CheckCheck, stringResource(Res.string.explorer_action_select_all), { commands.selectAll.performIfEnabled() })
            DesignIconButton(DrawViewIcons.FolderInput, commands.move.label(), { commands.move.performIfEnabled() }, enabled = commands.move.isEnabled())
            DesignIconButton(
                DrawViewIcons.Trash,
                commands.delete.label(),
                { commands.delete.performIfEnabled() },
                enabled = commands.delete.isEnabled(),
                tint = MaterialTheme.colorScheme.error
            )
            // The actions of the application (e.g. "Export…"), in sight, on all the items selected
            commands.selectionActionCommands.forEachIndexed { index, command ->
                DesignIconButton(
                    icon = commands.itemsActionIcons[index],
                    label = command.label(),
                    onClick = { command.performIfEnabled() },
                    enabled = command.isEnabled()
                )
            }
        } else {
            Spacer(modifier = Modifier.width(16.dp))
            TextButton(onClick = { commands.selectAll.performIfEnabled() }) {
                Icon(DrawViewIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.explorer_action_select_all), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
