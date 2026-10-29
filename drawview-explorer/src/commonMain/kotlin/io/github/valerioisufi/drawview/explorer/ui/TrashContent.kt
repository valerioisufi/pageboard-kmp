package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel
import io.github.valerioisufi.drawview.explorer.LocalLibraryClock
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_notes_count
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_root_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_days_left
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_delete_forever
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_empty
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_empty_state
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_empty_state_body
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_from
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_restore
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_retention
import io.github.valerioisufi.drawview.model.TrashRetention
import io.github.valerioisufi.drawview.store.TrashedItem
import io.github.valerioisufi.drawview.ui.command.DesignMenu
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.components.DesignIconButton
import io.github.valerioisufi.drawview.ui.components.IconButtonStyle
import io.github.valerioisufi.drawview.ui.components.OutlineButton
import io.github.valerioisufi.drawview.ui.components.TonalButton
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A day, in milliseconds. */
private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * The days [item] has left in the trash before it is deleted for good, at [now]: at least one
 * until it is.
 */
internal fun daysLeft(item: TrashedItem, now: Long): Int {
    val left = item.trashedAt + TrashRetention.MILLIS - now
    return ((left + DAY_MILLIS - 1) / DAY_MILLIS).toInt().coerceAtLeast(1)
}

/**
 * The Trash of the library ([FileExplorerViewModel.Section.TRASH]): how long it keeps what is
 * deleted and Empty Trash, then a row for each item with Restore and Delete for good (asked
 * first, see [LibraryCommands.deleteForGood]); why there is nothing when it is empty.
 */
internal fun LazyGridScope.trashContent(viewModel: FileExplorerViewModel, commands: LibraryCommands?, compact: Boolean, margin: Dp) {
    val items = viewModel.trash
    item(key = "trash-info", span = { GridItemSpan(maxLineSpan) }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = margin, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(Res.string.explorer_trash_retention),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (items.isNotEmpty() && commands != null) {
                OutlineButton(onClick = { commands.emptyTrash() }) {
                    Text(stringResource(Res.string.explorer_trash_empty))
                }
            }
        }
    }
    if (items.isEmpty()) {
        item(key = "trash-empty", span = { GridItemSpan(maxLineSpan) }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 64.dp, start = margin, end = margin),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(DrawViewIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                Text(stringResource(Res.string.explorer_trash_empty_state), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(Res.string.explorer_trash_empty_state_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    items(items, key = { "trash:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { item ->
        TrashRow(item, viewModel, commands, compact, margin, modifier = Modifier.animateItem())
    }
}

/** An item of the trash: its icon, its name, where it was and the days it has left, its actions. */
@Composable
private fun TrashRow(item: TrashedItem, viewModel: FileExplorerViewModel, commands: LibraryCommands?, compact: Boolean, margin: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().padding(start = margin, end = (margin - 8.dp).coerceAtLeast(0.dp), top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.width(48.dp).height(56.dp)) {
            when (item.kind) {
                TrashedItem.Kind.FOLDER -> FolderTile(item.id, size = 44.dp)
                TrashedItem.Kind.DOCUMENT -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp, 52.dp).clip(RoundedCornerShape(6.dp)).background(colors.surfaceContainerHigh)
                ) {
                    Icon(DrawViewIcons.FileText, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val days = daysLeft(item, LocalLibraryClock.current())
            val parts = buildList {
                add(stringResource(Res.string.explorer_trash_from, item.folderName ?: stringResource(Res.string.explorer_root_folder)))
                if (item.kind == TrashedItem.Kind.FOLDER) add(pluralStringResource(Res.plurals.explorer_notes_count, item.documentIds.size, item.documentIds.size))
                add(pluralStringResource(Res.plurals.explorer_trash_days_left, days, days))
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // A phone has room for the icon only
        if (compact) {
            DesignIconButton(DrawViewIcons.RotateCcw, stringResource(Res.string.explorer_trash_restore), onClick = { viewModel.restore(item) }, style = IconButtonStyle.Tonal)
        } else {
            TonalButton(onClick = { viewModel.restore(item) }) {
                Icon(DrawViewIcons.RotateCcw, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.explorer_trash_restore), maxLines = 1)
            }
        }
        if (commands != null) {
            Box {
                DesignIconButton(DrawViewIcons.MoreHorizontal, stringResource(Res.string.explorer_trash_delete_forever), onClick = { menu = true })
                DesignMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(
                        text = stringResource(Res.string.explorer_trash_delete_forever),
                        icon = rememberVectorPainter(DrawViewIcons.Trash),
                        destructive = true,
                        onClick = {
                            menu = false
                            commands.deleteForGood(item)
                        }
                    )
                }
            }
        }
    }
}
