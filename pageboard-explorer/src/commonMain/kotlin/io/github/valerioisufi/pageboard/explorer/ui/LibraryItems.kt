package io.github.valerioisufi.pageboard.explorer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.explorer.DocumentOpener
import io.github.valerioisufi.pageboard.explorer.DocumentThumbnails
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel.FileItem
import io.github.valerioisufi.pageboard.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.pageboard.explorer.LibrarySearchMatch
import io.github.valerioisufi.pageboard.explorer.generated.resources.Res
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_action_details
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_notes_count
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_root_folder
import io.github.valerioisufi.pageboard.explorer.generated.resources.explorer_tag_shared
import io.github.valerioisufi.pageboard.ui.command.Command
import io.github.valerioisufi.pageboard.ui.components.DesignIconButton
import io.github.valerioisufi.pageboard.ui.components.LabelBadge
import io.github.valerioisufi.pageboard.ui.generated.resources.Res as DrawViewRes
import io.github.valerioisufi.pageboard.ui.generated.resources.date_months_short
import io.github.valerioisufi.pageboard.ui.theme.DrawViewElevation
import io.github.valerioisufi.pageboard.ui.theme.DrawViewIcons
import io.github.valerioisufi.pageboard.ui.theme.elevation
import io.github.valerioisufi.pageboard.ui.util.formatShortDateTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/** Thumbnails drawn at widths multiple of this (in pixels), so that close sizes share them. */
private const val ThumbnailWidthStep = 120

/** The widest thumbnail drawn (in pixels). */
private const val ThumbnailMaxWidth = 600

/** The shape of a sheet of paper (a thumbnail). */
private val PaperShape = RoundedCornerShape(4.dp)

/**
 * What a card or a row of the library does with the pointer, the selection and the menus: the
 * commands of the item, its context menu where it was clicked with the secondary button, its
 * details.
 */
internal class ItemActions(
    val viewModel: FileExplorerViewModel,
    val onOpenDocument: (String) -> Unit,
    val thumbnails: DocumentThumbnails?,
    val commands: LibraryCommands?,
    /** Shows the details of an item beside the library; `null`: in a sheet. */
    val onShowDetails: ((FileItem) -> Unit)?,
    val onOpenDocumentInNewWindow: ((String) -> Unit)? = null,
    val onOpenDocumentWithPlace: DocumentOpener = DocumentOpener { id, _ -> onOpenDocument(id) }
)

/**
 * A document as a card of the grid: its first page in a well, its first tag, its name, the date of
 * its last change and the button of its details; ringed in the primary color while selected.
 */
@Composable
internal fun NoteCard(item: FileItem, actions: ItemActions, modifier: Modifier = Modifier) {
    val viewModel = actions.viewModel
    val selected = viewModel.isSelected(item)
    val itemCommands = remember(actions.commands, item) { actions.commands?.forItem(item).orEmpty() }
    var contextMenu by remember { mutableStateOf<DpOffset?>(null) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) colors.surfaceContainerLowest else colors.surfaceContainerLow)
                .then(if (selected) Modifier.border(2.dp, colors.primary, shape) else Modifier)
                .draggableItem(item, viewModel)
                .fileItemInteraction(item, viewModel, actions.onOpenDocument, actions.onOpenDocumentInNewWindow) { position ->
                    if (itemCommands.isNotEmpty()) contextMenu = position
                }
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(184f / 216f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) colors.primaryContainer else colors.surfaceContainer)
                    .padding(horizontal = 17.dp, vertical = 8.dp)
            ) {
                DocumentThumbnail(item, actions.thumbnails)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 6.dp)
            ) {
                ItemText(item, viewModel, modifier = Modifier.weight(1f))
                if (!viewModel.selectionMode) ItemMoreButton(item, itemCommands, actions)
            }
            val matches = viewModel.searchMatchesOf(item)
            if (matches.isNotEmpty()) {
                SearchMatchesList(
                    matches = matches,
                    onOpenDefault = { actions.onOpenDocument(item.id) },
                    openDocument = actions.onOpenDocumentWithPlace,
                    onSelect = { viewModel.toggleSelection(item) }.takeIf { viewModel.selectionMode },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        viewModel.tagsOf(item).firstOrNull()?.let { tag ->
            LabelBadge(tag.label(), tag.tone, icon = tag.icon, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
        }
        if (selected) SelectedCheck(modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp))
        FileContextMenu(itemCommands, contextMenu, onDismiss = { contextMenu = null })
    }
}

/**
 * A folder as a card: the tile of the folder in its tone, its name and how many notes it holds;
 * dropping dragged items onto it moves them there.
 */
@Composable
internal fun FolderCard(item: FileItem, actions: ItemActions, modifier: Modifier = Modifier) {
    val viewModel = actions.viewModel
    val selected = viewModel.isSelected(item)
    val itemCommands = remember(actions.commands, item) { actions.commands?.forItem(item).orEmpty() }
    var contextMenu by remember { mutableStateOf<DpOffset?>(null) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) colors.primaryContainer else colors.surfaceContainerLow)
                .then(if (selected) Modifier.border(2.dp, colors.primary, shape) else Modifier)
                .draggableItem(item, viewModel)
                .dropTarget(viewModel, item.id)
                .fileItemInteraction(item, viewModel, actions.onOpenDocument, actions.onOpenDocumentInNewWindow) { position ->
                    if (itemCommands.isNotEmpty()) contextMenu = position
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FolderTile(item.id, shared = item.isShared)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    itemLocation(item, viewModel) ?: pluralStringResource(Res.plurals.explorer_notes_count, item.noteCount, item.noteCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) SelectedCheck(modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
        FileContextMenu(itemCommands, contextMenu, onDismiss = { contextMenu = null })
    }
}

/**
 * The tile of a folder: its icon on the container of one of the three tones, the same for a folder
 * every time; [shared]: a folder shared with other devices, its badge in a corner.
 */
@Composable
internal fun FolderTile(folderId: String, size: Dp = 44.dp, shared: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (folderId.hashCode().mod(3)) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .background(container, RoundedCornerShape(size * 14 / 44))
    ) {
        Icon(DrawViewIcons.Folder, contentDescription = null, tint = content, modifier = Modifier.size(size / 2))
        if (shared) {
            val badge = size * 9 / 22
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = badge / 4, y = badge / 4)
                    .size(badge)
                    .background(colors.primary, CircleShape)
                    .border(1.5.dp, colors.surface, CircleShape)
            ) {
                Icon(DrawViewIcons.Users, contentDescription = stringResource(Res.string.explorer_tag_shared), tint = colors.onPrimary, modifier = Modifier.size(badge * 3 / 5))
            }
        }
    }
}

/**
 * A document as a row of a list (a phone, the list of a wider window): its first page, its name,
 * the date of its last change with its first tag, the button of its details.
 */
@Composable
internal fun NoteRow(item: FileItem, actions: ItemActions, modifier: Modifier = Modifier) {
    val viewModel = actions.viewModel
    val selected = viewModel.isSelected(item)
    val itemCommands = remember(actions.commands, item) { actions.commands?.forItem(item).orEmpty() }
    var contextMenu by remember { mutableStateOf<DpOffset?>(null) }
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) colors.primaryContainer else Color.Transparent)
                .draggableItem(item, viewModel)
                .then(if (item.type == FileType.FOLDER) Modifier.dropTarget(viewModel, item.id) else Modifier)
                .fileItemInteraction(item, viewModel, actions.onOpenDocument, actions.onOpenDocumentInNewWindow) { position ->
                    if (itemCommands.isNotEmpty()) contextMenu = position
                }
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(48.dp).height(64.dp)) {
                if (item.type == FileType.FILE) DocumentThumbnail(item, actions.thumbnails) else FolderTile(item.id, size = 44.dp, shared = item.isShared)
                if (selected) SelectedCheck(modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        itemLocation(item, viewModel) ?: itemSubtitle(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    viewModel.tagsOf(item).firstOrNull()?.let { tag -> LabelBadge(tag.label(), tag.tone, icon = tag.icon) }
                }
                val matches = viewModel.searchMatchesOf(item)
                if (matches.isNotEmpty()) {
                    SearchMatchesList(
                        matches = matches,
                        onOpenDefault = { actions.onOpenDocument(item.id) },
                        openDocument = actions.onOpenDocumentWithPlace,
                        onSelect = { viewModel.toggleSelection(item) }.takeIf { viewModel.selectionMode },
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (!viewModel.selectionMode) ItemMoreButton(item, itemCommands, actions)
        }
        FileContextMenu(itemCommands, contextMenu, onDismiss = { contextMenu = null })
    }
}

/** Where a result of a search is ("Analysis › Limits", the root), `null` outside a search. */
@Composable
private fun itemLocation(item: FileItem, viewModel: FileExplorerViewModel): String? =
    if (viewModel.isSearching) viewModel.folderPath(item.folderId) ?: stringResource(Res.string.explorer_root_folder) else null

/** The name of an item and the date of its last change (a document) or its notes (a folder); in a search, where it is. */
@Composable
private fun ItemText(item: FileItem, viewModel: FileExplorerViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(item.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            itemLocation(item, viewModel) ?: itemSubtitle(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Matching snippets of a document shown under its name when searching the library; while items
 * are selected ([onSelect]) they select it, as the rest of the item.
 */
@Composable
private fun SearchMatchesList(
    matches: List<LibrarySearchMatch>,
    onOpenDefault: () -> Unit,
    openDocument: DocumentOpener,
    onSelect: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (match in matches) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainerHighest.copy(alpha = 0.55f))
                    .clickable {
                        val customOpen = match.onOpen
                        when {
                            onSelect != null -> onSelect()
                            customOpen != null -> customOpen(openDocument)
                            else -> onOpenDefault()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                match.icon?.let { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (match.location.isNotEmpty()) {
                    Text(
                        text = match.location,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                        maxLines = 1
                    )
                }
                Text(
                    text = match.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** The date of the last change of a document ("14 Sep · 09:30"), the notes of a folder. */
@Composable
internal fun itemSubtitle(item: FileItem): String {
    if (item.type == FileType.FOLDER) return pluralStringResource(Res.plurals.explorer_notes_count, item.noteCount, item.noteCount)
    val monthNames = stringArrayResource(DrawViewRes.array.date_months_short)
    return remember(item.modifiedAt, monthNames) { formatShortDateTime(item.modifiedAt, monthNames) }
}

/** The check of a selected item: a circle of the primary color. */
@Composable
internal fun SelectedCheck(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape)
    ) {
        Icon(DrawViewIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
    }
}

/**
 * The button of the details of an item: beside the library where there is room
 * ([ItemActions.onShowDetails]), else its sheet of commands.
 */
@Composable
internal fun ItemMoreButton(item: FileItem, itemCommands: List<Command>, actions: ItemActions) {
    var sheet by remember { mutableStateOf(false) }
    DesignIconButton(
        icon = DrawViewIcons.MoreHorizontal,
        label = stringResource(Res.string.explorer_action_details),
        onClick = { actions.onShowDetails?.invoke(item) ?: run { sheet = true } },
        iconSize = 20.dp
    )
    if (sheet) ItemSheet(item, itemCommands, actions, onDismiss = { sheet = false })
}

/**
 * The first page of the document [item], drawn by [thumbnails] as large as there is room, with the
 * shadow of a sheet of paper; a blank sheet until it is drawn.
 */
@Composable
internal fun DocumentThumbnail(item: FileItem, thumbnails: DocumentThumbnails?, modifier: Modifier = Modifier) {
    BoxWithConstraints(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            .let { ((it + ThumbnailWidthStep - 1) / ThumbnailWidthStep) * ThumbnailWidthStep }
            .coerceIn(ThumbnailWidthStep, ThumbnailMaxWidth)
        val image by produceState<ImageBitmap?>(null, item.id, item.modifiedAt, widthPx, thumbnails) {
            value = thumbnails?.of(item.id, item.modifiedAt, widthPx)
        }
        val page = image
        val ratio = if (page != null) page.width.toFloat() / page.height else 210f / 297f
        // The page as large as the box lets it, its own proportions kept
        val fitted = if (maxWidth / maxHeight > ratio) Modifier.height(maxHeight).aspectRatio(ratio) else Modifier.width(maxWidth).aspectRatio(ratio)
        Box(
            modifier = fitted
                .elevation(DrawViewElevation.Level1, PaperShape)
                .clip(PaperShape)
                .background(Color.White)
        ) {
            if (page != null) {
                Image(bitmap = page, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** A surface of the selected items while dragged (see [LibraryDragShadow]). */
@Composable
internal fun DragLabel(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
