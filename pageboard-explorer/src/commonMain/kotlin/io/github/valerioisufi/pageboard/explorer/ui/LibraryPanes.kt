package io.github.valerioisufi.pageboard.explorer.ui

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.ui.window.LocalWindowDecorInsets
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel.Section
import io.github.valerioisufi.pageboard.explorer.generated.resources.Res
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_new_folder
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_root_folder
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_section_folders
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_section_recent
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_section_shared
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_section_trash
import io.github.valerioisufi.pageboard.ui.components.NavigationItem
import io.github.valerioisufi.pageboard.ui.components.TonalButton
import io.github.valerioisufi.pageboard.ui.extension.LibraryAction
import io.github.valerioisufi.pageboard.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.stringResource

/** How the library shows a folder, by the width it has. */
internal enum class LibraryLayout {
    /** A list (a phone). */
    LIST,

    /** A grid of cards with the first pages (a tablet in portrait, a narrow desktop window). */
    GRID,

    /** The pane of the folders and the recent notes, the grid, the details of the item selected. */
    PANES;

    companion object {
        /** The width of a grid. */
        val GridMinWidth: Dp = 600.dp

        /** The width of the panes: the navigation, a grid of two cards at least, the details. */
        val PanesMinWidth: Dp = 1000.dp

        /** The width of the pane of the folders. */
        val NavigationWidth: Dp = 264.dp

        /** The width of the pane of the details. */
        val DetailsWidth: Dp = 320.dp

        /** How a library [width] wide shows a folder. */
        fun of(width: Dp): LibraryLayout = when {
            width < GridMinWidth -> LIST
            width < PanesMinWidth -> GRID
            else -> PANES
        }
    }
}

/**
 * The pane of navigation of a wide library: the brand of the application, the sections (the
 * folders from the root, the recent notes, the shared ones) with their notes, the folders of the
 * root (the one holding the folder shown chosen), making a folder, the actions of the application
 * (the prominent ones as wide buttons).
 */
@Composable
internal fun LibraryNavigationPane(
    viewModel: FileExplorerViewModel,
    commands: LibraryCommands,
    brand: @Composable () -> Unit,
    actions: List<LibraryAction>,
    onAction: (LibraryAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val rootFolder = viewModel.currentRootFolderId
    // A search of the whole library is not in any of them
    val section = viewModel.section.takeUnless { viewModel.searchCoversLibrary }
    // The pane is under the traffic lights of macOS: it starts below the title bar
    val decorTopPad = LocalWindowDecorInsets.current.calculateTopPadding()
    Column(
        modifier = modifier
            .width(LibraryLayout.NavigationWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = decorTopPad.coerceAtLeast(20.dp),
                bottom = 16.dp
            )
    ) {
        Box(modifier = Modifier.padding(horizontal = 8.dp).height(40.dp), contentAlignment = Alignment.CenterStart) { brand() }
        Spacer(modifier = Modifier.height(22.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "home") {
                NavigationItem(
                    icon = DrawViewIcons.Home,
                    label = stringResource(Res.string.explorer_root_folder),
                    selected = section == Section.HOME && rootFolder == null,
                    onClick = { viewModel.showFolder(null) },
                    count = viewModel.homeNoteCount,
                    modifier = Modifier.dropTarget(viewModel, folderId = null)
                )
            }
            item(key = "recent") {
                NavigationItem(
                    icon = DrawViewIcons.Clock,
                    label = stringResource(Res.string.explorer_section_recent),
                    selected = section == Section.RECENT,
                    onClick = { viewModel.showSection(Section.RECENT) },
                    count = viewModel.recentNoteCount
                )
            }
            item(key = "shared") {
                NavigationItem(
                    icon = DrawViewIcons.Users,
                    label = stringResource(Res.string.explorer_section_shared),
                    selected = section == Section.SHARED,
                    onClick = { viewModel.showSection(Section.SHARED) },
                    count = viewModel.sharedNoteCount
                )
            }
            item(key = "trash") {
                NavigationItem(
                    icon = DrawViewIcons.Trash,
                    label = stringResource(Res.string.explorer_section_trash),
                    selected = section == Section.TRASH,
                    onClick = { viewModel.showSection(Section.TRASH) },
                    count = viewModel.trash.size
                )
            }
            item(key = "folders-label") {
                Text(
                    text = stringResource(Res.string.explorer_section_folders).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp)
                )
            }
            items(viewModel.rootFolders, key = { it.key }) { folder ->
                NavigationItem(
                    icon = DrawViewIcons.Folder,
                    label = folder.name,
                    selected = section == Section.HOME && rootFolder == folder.id,
                    onClick = { viewModel.showFolder(folder.id) },
                    count = folder.noteCount,
                    modifier = Modifier.dropTarget(viewModel, folderId = folder.id)
                )
            }
            item(key = "new-folder") {
                TextButton(onClick = { commands.newFolder.performIfEnabled() }, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(DrawViewIcons.FolderPlus, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.explorer_new_folder), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        val (prominent, others) = actions.partition { it.prominent }
        for (action in prominent) {
            TonalButton(onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(action.label(), maxLines = 1)
            }
        }
        // The others named, not only drawn: two actions may share an icon, and a finger has no tooltip
        if (others.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                for (action in others) PaneAction(action, onClick = { onAction(action) })
            }
        }
        // Room for what the application draws over the bottom of the library (a recording)
        Spacer(modifier = Modifier.height(64.dp))
    }
}

/** An action of the application at the bottom of the pane: its icon and its name, 40 dp high. */
@Composable
private fun PaneAction(action: LibraryAction, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(action.icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(action.label(), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
