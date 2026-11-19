package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileItem
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_details
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_details_created
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_details_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_details_modified
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_details_notes
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_details_pages
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_details_title
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_label_last_modified
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_more_actions
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_open_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_open_note
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_root_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_selection_hint
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_selection_summary
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandMenu
import io.github.valerioisufi.drawview.ui.components.DesignIconButton
import io.github.valerioisufi.drawview.ui.components.IconButtonStyle
import io.github.valerioisufi.drawview.ui.components.LabelBadge
import io.github.valerioisufi.drawview.ui.components.OutlineButton
import io.github.valerioisufi.drawview.ui.components.TonalButton
import io.github.valerioisufi.drawview.ui.generated.resources.Res as DrawViewRes
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_close
import io.github.valerioisufi.drawview.ui.generated.resources.date_months_short
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.util.formatDate
import io.github.valerioisufi.drawview.ui.util.formatDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** The commands of an item that open it ([LibraryCommands.forItem]). */
private const val OpenCommand = "open"

/**
 * The pane of the details of the selection of a wide library: an item alone (its preview, its
 * tags, its dates, its actions), or how many are selected and what can be done with them.
 */
@Composable
internal fun LibraryDetailsPane(actions: ItemActions, commands: LibraryCommands, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val selection = actions.viewModel.targetItems
    Column(
        modifier = modifier
            .width(LibraryLayout.DetailsWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(24.dp)
    ) {
        val item = selection.singleOrNull()
        if (item != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(Res.string.explorer_details_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                DesignIconButton(DrawViewIcons.Close, stringResource(DrawViewRes.string.common_action_close), onClose)
            }
            Spacer(modifier = Modifier.height(18.dp))
            val itemCommands = remember(commands, item) { commands.forItem(item) }
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                ItemDetails(item, actions, previewHeight = 290)
            }
            Spacer(modifier = Modifier.height(18.dp))
            PaneActions(item, itemCommands)
        } else {
            SelectionSummary(actions, commands)
        }
    }
}

/**
 * The details of [item]: its preview, its name, its tags and its dates, its pages (a document) or
 * its notes (a folder), the folder holding it.
 */
@Composable
internal fun ColumnScope.ItemDetails(item: FileItem, actions: ItemActions, previewHeight: Int) {
    val viewModel = actions.viewModel
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(previewHeight.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .padding(vertical = 24.dp)
    ) {
        if (item.type == FileType.FILE) DocumentThumbnail(item, actions.thumbnails) else FolderTile(item.id, size = 88.dp, shared = item.isShared)
    }
    Spacer(modifier = Modifier.height(18.dp))
    Text(item.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    val tags = viewModel.tagsOf(item)
    if (tags.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (tag in tags) LabelBadge(tag.label(), tag.tone, icon = tag.icon)
        }
    }
    Spacer(modifier = Modifier.height(18.dp))
    val monthNames = stringArrayResource(DrawViewRes.array.date_months_short)
    DetailRow(stringResource(Res.string.explorer_details_modified), remember(item.modifiedAt, monthNames) { formatDateTime(item.modifiedAt, monthNames).trimStart('0') })
    DetailRow(stringResource(Res.string.explorer_details_created), remember(item.createdAt, monthNames) { formatDate(item.createdAt, monthNames) })
    if (item.type == FileType.FILE) {
        DetailRow(stringResource(Res.string.explorer_details_pages), item.pageCount.toString())
    } else {
        DetailRow(stringResource(Res.string.explorer_details_notes), item.noteCount.toString())
    }
    DetailRow(stringResource(Res.string.explorer_details_folder), viewModel.folderName(item.folderId) ?: stringResource(Res.string.explorer_root_folder))
}

/** A property of an item: its name and its value, a line under them. */
@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The actions of the item of the pane: opening it, the actions of the application as tonal
 * buttons (e.g. Export), the others (rename, move, delete) in a menu.
 */
@Composable
private fun PaneActions(item: FileItem, itemCommands: List<Command>) {
    val open = itemCommands.firstOrNull { it.id == OpenCommand }
    val applicationActions = itemCommands.filter { it.id.startsWith(ItemsActionPrefix) }
    val others = itemCommands.filter { it.id != OpenCommand && !it.id.startsWith(ItemsActionPrefix) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (open != null) {
            Button(
                onClick = { open.performIfEnabled() },
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(if (item.type == FileType.FILE) DrawViewIcons.Pencil else DrawViewIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    stringResource(if (item.type == FileType.FILE) Res.string.explorer_open_note else Res.string.explorer_open_folder),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (command in applicationActions) {
                TonalButton(onClick = { command.performIfEnabled() }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    command.icon?.let { Icon(it(), contentDescription = null, modifier = Modifier.size(18.dp)) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(command.label().removeSuffix("…"), maxLines = 1)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            MoreCommandsButton(others)
        }
    }
}

/** A tonal button whose menu holds [commands]. */
@Composable
internal fun MoreCommandsButton(commands: List<Command>) {
    if (commands.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        DesignIconButton(DrawViewIcons.MoreHorizontal, stringResource(Res.string.explorer_more_actions), { expanded = true }, style = IconButtonStyle.Tonal)
        CommandMenu(commands = commands, expanded = expanded, onDismissRequest = { expanded = false })
    }
}

/** How many items are selected (their first pages in a pile), how to select more and what can be done with them. */
@Composable
private fun SelectionSummary(actions: ItemActions, commands: LibraryCommands) {
    val selection = actions.viewModel.selectedItems
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.width(170.dp).height(170.dp)) {
            val pile = selection.takeLast(3)
            pile.forEachIndexed { index, item ->
                val turn = (index - pile.size + 1) * 6f
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(150.dp)
                        .offset(x = ((index - pile.size + 1) * 14).dp)
                        .rotate(turn)
                ) {
                    if (item.type == FileType.FILE) DocumentThumbnail(item, actions.thumbnails) else Box(contentAlignment = Alignment.Center) { FolderTile(item.id, size = 72.dp, shared = item.isShared) }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            pluralStringResource(Res.plurals.explorer_selection_summary, selection.size, selection.size),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        val modifierKey = if (currentAdaptiveInfo().conventions.shortcutModifier == ShortcutModifier.COMMAND) "⌘" else "Ctrl"
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            stringResource(Res.string.explorer_selection_hint, modifierKey),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Column(modifier = Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The actions of the application (e.g. "Export…") on all the items selected
            for (command in commands.selectionActionCommands) {
                TonalButton(onClick = { command.performIfEnabled() }, modifier = Modifier.fillMaxWidth()) {
                    command.icon?.let { Icon(it(), contentDescription = null, modifier = Modifier.size(18.dp)) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(command.label())
                }
            }
            TonalButton(onClick = { commands.move.performIfEnabled() }, modifier = Modifier.fillMaxWidth()) {
                Icon(DrawViewIcons.FolderInput, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(commands.move.label())
            }
            OutlineButton(
                onClick = { commands.delete.performIfEnabled() },
                contentColor = MaterialTheme.colorScheme.error,
                borderColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(DrawViewIcons.Trash, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(commands.delete.label())
            }
        }
    }
}

/**
 * The sheet of an item of a narrow library: its name and the date of its last change, then its
 * commands (open, its details, rename, move, the actions of the application, delete); "Info"
 * shows its details in the same sheet.
 */
@Composable
internal fun ItemSheet(item: FileItem, itemCommands: List<Command>, actions: ItemActions, onDismiss: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    AdaptiveSheet(onDismissRequest = onDismiss) {
        if (details) {
            DetailsSheetContent(item, itemCommands, actions, onCommand = onDismiss)
            return@AdaptiveSheet
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(36.dp).height(48.dp)) {
                if (item.type == FileType.FILE) DocumentThumbnail(item, actions.thumbnails) else FolderTile(item.id, size = 36.dp, shared = item.isShared)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val monthNames = stringArrayResource(DrawViewRes.array.date_months_short)
                Text(
                    stringResource(Res.string.explorer_label_last_modified, remember(item.modifiedAt, monthNames) { formatDateTime(item.modifiedAt, monthNames).trimStart('0') }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            for (command in itemCommands) {
                SheetCommand(command.icon?.invoke(), command.label(), command.destructive) {
                    onDismiss()
                    command.performIfEnabled()
                }
                if (command.id == OpenCommand) {
                    SheetCommand(rememberVectorPainter(DrawViewIcons.Info), stringResource(Res.string.explorer_action_details), false) { details = true }
                }
            }
        }
    }
}

/** The details of an item in a sheet: its preview and its properties, opening it and the actions of the application. */
@Composable
private fun ColumnScope.DetailsSheetContent(item: FileItem, itemCommands: List<Command>, actions: ItemActions, onCommand: () -> Unit) {
    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        ItemDetails(item, actions, previewHeight = 250)
    }
    val open = itemCommands.firstOrNull { it.id == OpenCommand }
    val applicationActions = itemCommands.filter { it.id.startsWith(ItemsActionPrefix) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (open != null) {
            Button(
                onClick = {
                    onCommand()
                    open.performIfEnabled()
                },
                modifier = Modifier.weight(1f).height(52.dp)
            ) {
                Icon(DrawViewIcons.Pencil, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    stringResource(if (item.type == FileType.FILE) Res.string.explorer_open_note else Res.string.explorer_open_folder),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        for (command in applicationActions) {
            val icon = command.icon ?: continue
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        onCommand()
                        command.performIfEnabled()
                    }
            ) {
                Icon(icon(), contentDescription = command.label(), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** A command of a sheet: its icon and its label, in the color of the errors when it deletes. */
@Composable
private fun SheetCommand(icon: androidx.compose.ui.graphics.painter.Painter?, label: String, destructive: Boolean, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (destructive) color else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** The prefix of the identifiers of the commands of the actions of the application on the items. */
internal const val ItemsActionPrefix = "items-action-"
