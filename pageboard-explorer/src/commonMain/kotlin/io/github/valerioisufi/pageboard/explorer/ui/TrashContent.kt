package io.github.valerioisufi.pageboard.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_action_clear_selection
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_action_select_all
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_selected_count
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_trash_preview
import io.github.valerioisufi.pageboard.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.pageboard.ui.adaptive.currentAdaptiveInfo
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
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel
import io.github.valerioisufi.pageboard.explorer.LocalLibraryClock
import io.github.valerioisufi.pageboard.explorer.generated.resources.Res
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_no_results_title
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_notes_count
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_root_folder
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_trash_days_left
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_trash_delete_forever
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_trash_empty
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_trash_empty_state
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_trash_empty_state_body
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_trash_from
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_trash_restore
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_trash_retention
import io.github.valerioisufi.pageboard.model.TrashRetention
import io.github.valerioisufi.pageboard.store.TrashedItem
import io.github.valerioisufi.pageboard.ui.command.DesignMenu
import io.github.valerioisufi.pageboard.ui.command.MenuItem
import io.github.valerioisufi.pageboard.ui.components.DesignIconButton
import io.github.valerioisufi.pageboard.ui.components.IconButtonStyle
import io.github.valerioisufi.pageboard.ui.components.OutlineButton
import io.github.valerioisufi.pageboard.ui.components.TonalButton
import io.github.valerioisufi.pageboard.ui.theme.DrawViewIcons
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
 * first, see [LibraryCommands.deleteForGood]); why there is nothing when it is empty. A tap on a
 * document shows its pages when [previews] (see [TrashPreview]); a long press, or a click with
 * ⌘/Ctrl or Shift, selects, and the bar of the selection takes the place of the line on top.
 */
internal fun LazyGridScope.trashContent(viewModel: FileExplorerViewModel, commands: LibraryCommands?, compact: Boolean, margin: Dp, previews: Boolean = false) {
    val items = viewModel.shownTrash
    item(key = "trash-info", span = { GridItemSpan(maxLineSpan) }) {
        if (viewModel.selectingTrash) {
            TrashSelectionBar(viewModel, commands, compact, margin)
            return@item
        }
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
            if (viewModel.trash.isNotEmpty() && commands != null) {
                OutlineButton(onClick = { commands.emptyTrash() }) {
                    Text(stringResource(Res.string.explorer_trash_empty))
                }
            }
        }
    }
    if (viewModel.trash.isNotEmpty() && items.isEmpty()) {
        item(key = "trash-no-results", span = { GridItemSpan(maxLineSpan) }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 64.dp, start = margin, end = margin),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(DrawViewIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                Text(stringResource(Res.string.explorer_no_results_title, viewModel.searchQuery.trim()), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        return
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
        TrashRow(item, viewModel, commands, compact, margin, previews, modifier = Modifier.animateItem())
    }
}

/**
 * The bar of the items of the trash selected: how many, Select all, Restore, Delete for good (asked
 * first) and Done.
 */
@Composable
private fun TrashSelectionBar(viewModel: FileExplorerViewModel, commands: LibraryCommands?, compact: Boolean, margin: Dp) {
    val colors = MaterialTheme.colorScheme
    val count = viewModel.trashSelection.size
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = (margin - 12.dp).coerceAtLeast(0.dp), end = (margin - 8.dp).coerceAtLeast(0.dp), top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DesignIconButton(DrawViewIcons.Close, stringResource(Res.string.explorer_action_clear_selection), onClick = { viewModel.clearTrashSelection() })
        Text(
            stringResource(Res.string.explorer_selected_count, count),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        DesignIconButton(DrawViewIcons.CheckCheck, stringResource(Res.string.explorer_action_select_all), onClick = { viewModel.selectAllTrash() })
        val restore = { viewModel.restore(viewModel.selectedTrash) }
        if (compact) {
            DesignIconButton(DrawViewIcons.RotateCcw, stringResource(Res.string.explorer_trash_restore), onClick = restore, style = IconButtonStyle.Tonal)
        } else {
            TonalButton(onClick = restore) {
                Icon(DrawViewIcons.RotateCcw, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.explorer_trash_restore), maxLines = 1)
            }
        }
        if (commands != null) {
            DesignIconButton(DrawViewIcons.Trash, stringResource(Res.string.explorer_trash_delete_forever), onClick = { commands.deleteForGood(viewModel.selectedTrash) }, tint = colors.error)
        }
    }
}

/** The keys held at the last press of a pointer on a row (read by its click). */
private class HeldKeys {
    var value: PointerKeyboardModifiers? = null
}

/**
 * An item of the trash: its icon, its name, where it was and the days it has left, its actions. A
 * click shows the pages of a document ([previews]) or, while items are selected, selects it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashRow(item: TrashedItem, viewModel: FileExplorerViewModel, commands: LibraryCommands?, compact: Boolean, margin: Dp, previews: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val selected = item.id in viewModel.trashSelection
    val held = remember { HeldKeys() }
    val apple = currentAdaptiveInfo().conventions.shortcutModifier == ShortcutModifier.COMMAND
    val previewLabel = stringResource(Res.string.explorer_trash_preview)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = (margin - 12.dp).coerceAtLeast(0.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .pointerInput(held) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) held.value = event.keyboardModifiers
                    }
                }
            }
            .combinedClickable(
                onClickLabel = previewLabel.takeIf { previews && item.kind == TrashedItem.Kind.DOCUMENT && !viewModel.selectingTrash },
                onClick = {
                    val keys = held.value
                    when {
                        keys != null && keys.isShiftPressed -> viewModel.selectTrashRange(item)
                        keys != null && (if (apple) keys.isMetaPressed else keys.isCtrlPressed) -> viewModel.toggleTrash(item)
                        viewModel.selectingTrash -> viewModel.toggleTrash(item)
                        previews && item.kind == TrashedItem.Kind.DOCUMENT -> viewModel.previewed = item
                    }
                },
                onLongClick = { viewModel.toggleTrash(item) }
            )
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (viewModel.selectingTrash) {
            if (selected) {
                Icon(DrawViewIcons.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
            } else {
                Box(modifier = Modifier.size(22.dp).padding(1.dp).border(2.dp, colors.outline, CircleShape))
            }
        }
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
        // A phone has room for the icon only; nothing while items are selected (the bar acts on them)
        if (viewModel.selectingTrash) return@Row
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
