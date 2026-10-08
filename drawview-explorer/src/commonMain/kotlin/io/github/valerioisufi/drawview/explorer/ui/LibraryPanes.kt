package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileItem
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_details
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_recents_title
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_root_folder
import io.github.valerioisufi.drawview.ui.command.HoverTooltip
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_close
import org.jetbrains.compose.resources.stringResource
import io.github.valerioisufi.drawview.ui.generated.resources.Res as DrawViewRes

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
        val NavigationWidth: Dp = 280.dp

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
 * The pane of the folders of a wide library: the root, its folders (the one holding the folder
 * shown chosen) and the notes opened last.
 */
@Composable
internal fun LibraryNavigationPane(
    viewModel: FileExplorerViewModel,
    onOpenDocument: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rootFolder = viewModel.currentRootFolderId
    LazyColumn(
        modifier = modifier
            .width(LibraryLayout.NavigationWidth)
            .fillMaxHeight()
            .padding(horizontal = 12.dp)
    ) {
        item(key = "home") {
            NavigationDrawerItem(
                label = { Text(stringResource(Res.string.explorer_root_folder)) },
                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                selected = rootFolder == null,
                onClick = { viewModel.showFolder(null) },
                modifier = Modifier.dropTarget(viewModel, folderId = null)
            )
        }
        items(viewModel.rootFolders, key = { it.key }) { folder ->
            NavigationDrawerItem(
                label = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                selected = rootFolder == folder.id,
                onClick = { viewModel.showFolder(folder.id) },
                modifier = Modifier.dropTarget(viewModel, folderId = folder.id)
            )
        }
        // The notes opened since they were made (a note is opened when it is made)
        val recent = viewModel.recentFiles.filter { it.lastOpenedAt != null && it.lastOpenedAt > it.createdAt }
        if (recent.isNotEmpty()) {
            item(key = "recents") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(Res.string.explorer_recents_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(recent, key = { "recent:" + it.key }) { document ->
                NavigationDrawerItem(
                    label = { Text(document.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) },
                    selected = false,
                    onClick = { onOpenDocument(document.id) }
                )
            }
        }
    }
}

/** The pane of the details of [item] (the item selected alone in a wide library) and its actions. */
@Composable
internal fun LibraryDetailsPane(item: FileItem, commands: LibraryCommands, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val itemCommands = remember(commands, item) { commands.forItem(item) }
    Column(
        modifier = modifier
            .width(LibraryLayout.DetailsWidth)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 4.dp, top = 4.dp)
        ) {
            Text(
                text = stringResource(Res.string.explorer_action_details),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            val close = stringResource(DrawViewRes.string.common_action_close)
            HoverTooltip(close) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = close)
                }
            }
        }
        FileDetails(item, itemCommands)
    }
}
