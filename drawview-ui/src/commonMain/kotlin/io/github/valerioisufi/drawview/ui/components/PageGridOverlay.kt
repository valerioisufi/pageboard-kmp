package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.PageSelection
import io.github.valerioisufi.drawview.canvas.observeChanges
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.TrashRetention
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import io.github.valerioisufi.drawview.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.DesignMenu
import io.github.valerioisufi.drawview.ui.command.HoverTooltip
import io.github.valerioisufi.drawview.ui.command.MenuDivider
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.generated.resources.page_grid_action_restore
import io.github.valerioisufi.drawview.ui.generated.resources.page_grid_days_left
import io.github.valerioisufi.drawview.ui.generated.resources.page_grid_deleted_pages
import io.github.valerioisufi.drawview.ui.generated.resources.page_grid_deleted_pages_hint
import io.github.valerioisufi.drawview.ui.screen.EditorCommands
import io.github.valerioisufi.drawview.ui.theme.DrawViewElevation
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.elevation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen grid of the pages of the document: thumbnails, jump to a page, page actions
 * (add after, copy, paste after, delete, background) and reordering by drag & drop after a long
 * press. Several pages are selected (see `docs/page-selection.md`) with Select and a tap, or with
 * ⌘/Ctrl+click and Shift+click; the bar of the selection and the menu of a page selected act on
 * all of them.
 *
 * @param commands the commands of the editor, copying and pasting the pages (none: no copy).
 * @param onEditPageBackground opens the template configurator of the page at the given index.
 * @param onEditPagesBackground opens the template configurator of several pages (their
 *   identifiers, the grid closed then).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageGridOverlay(
    controller: DrawViewController,
    modifier: Modifier = Modifier,
    commands: EditorCommands? = null,
    extensionActions: List<Command> = emptyList(),
    onEditPageBackground: (Int) -> Unit = {},
    onEditPagesBackground: (List<String>) -> Unit = {}
) {
    val document = controller.document
    // The pages, their formats and their content as they change
    document.observeChanges()
    val pageManager = controller.pageManager
    val selection = pageManager.selection
    val writable = !controller.isReadOnly
    /** The background of the pages [ids]: one page as before, several with their own configurator. */
    val editBackground: (List<String>) -> Unit = { ids ->
        val single = ids.singleOrNull()?.let { id -> document.pages.indexOfFirst { it.id == id } }
        if (single != null) pageManager.contextMenuTargetPageIndex = single
        // The grid is closed to show the configurator
        controller.togglePageGrid()
        if (single != null) onEditPageBackground(single) else onEditPagesBackground(ids)
    }
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Absorbs the taps that fall between the cards
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {}
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // --- Top bar of the grid: close, the title with the document and its pages, selecting, adding a page ---
        if (selection.isActive) {
            PageSelectionBar(
                selection = selection,
                // Counted here, which follows the document: a page selected may be removed
                count = selection.count,
                writable = writable,
                canPaste = commands?.canPastePages() == true,
                canDelete = pageManager.canDeletePages(selection.pageIds),
                onCopy = commands?.let { { it.copyPages(selection.pageIds) } },
                onPaste = { commands?.pastePagesAfter(selection.pageIds) },
                onEditBackground = { editBackground(selection.pageIds) },
                onDelete = { pageManager.deletePages(selection.pageIds) },
                extensionActions = extensionActions
            )
        } else Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DesignIconButton(DrawViewIcons.Close, stringResource(Res.string.page_grid_action_close), { controller.togglePageGrid() }, tint = MaterialTheme.colorScheme.onSurface)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.page_grid_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = document.name + " · " + pluralStringResource(Res.plurals.editor_pages_count, document.pages.size, document.pages.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DesignIconButton(DrawViewIcons.SquareCheckBig, stringResource(Res.string.page_grid_action_select), { selection.start() }, tint = MaterialTheme.colorScheme.onSurface)
            if (writable) {
                val addPage: () -> Unit = { pageManager.addNewPageAfter(document.pages.lastIndex) }
                if (currentAdaptiveInfo().window.isCompact) {
                    DesignIconButton(DrawViewIcons.FilePlus2, stringResource(Res.string.context_menu_action_add_page), addPage, selected = true)
                } else {
                    TonalButton(onClick = addPage) {
                        Icon(DrawViewIcons.FilePlus2, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(Res.string.context_menu_action_add_page))
                    }
                }
            }
        }

        // --- Drag & drop state ---
        val gridState = rememberLazyGridState()
        // The page looked at when the grid was opened, by its identifier: it follows the page
        val currentPageId = remember { document.pages.getOrNull(controller.viewport.mostVisiblePageIndex() ?: 0)?.id }
        var draggedIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }
        // The pages selected dragged with the card (a card of a selection of several), in their order
        var draggedBlock by remember { mutableStateOf<List<String>?>(null) }
        // The deleted pages shown after the others
        var deletedExpanded by remember { mutableStateOf(false) }
        // The pages and the deleted ones as they are now (see `docs/page-grid-updates.md`): the
        // model is not Compose state, so the content of the grid is built from what this
        // composition read, another lambda after every change, counted again by the grid
        val pages = document.pages
        val now = currentTimeMillis()
        val deleted = if (writable) pageManager.deletedPagesAt(now) else emptyList()

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                offset.x.toInt() in item.offset.x..(item.offset.x + item.size.width) &&
                                    offset.y.toInt() in item.offset.y..(item.offset.y + item.size.height)
                            }?.let { item ->
                                // The pages only (not the deleted ones after them)
                                val page = document.pages.getOrNull(item.index) ?: return@let
                                pageManager.startPageReorderMode()
                                val block = selection.pageIds.takeIf { page.id in it && it.size > 1 }
                                draggedBlock = block
                                draggedIndex = item.index
                                if (block != null) {
                                    // Gathered around the card dragged: the pages before it in the block go just before it
                                    val before = document.pages.take(item.index).count { it.id !in block }
                                    pageManager.moveBlockDuringReorder(block, before)
                                    draggedIndex = document.pages.indexOfFirst { it.id == page.id }
                                }
                            }
                        },
                        onDragEnd = {
                            if (draggedIndex != null) {
                                pageManager.finishPageReorderMode()
                                draggedIndex = null
                                draggedBlock = null
                                dragOffset = Offset.Zero
                            }
                        },
                        onDragCancel = {
                            if (draggedIndex != null) {
                                pageManager.finishPageReorderMode()
                                draggedIndex = null
                                draggedBlock = null
                                dragOffset = Offset.Zero
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount

                            val draggedIdx = draggedIndex ?: return@detectDragGesturesAfterLongPress
                            val draggedItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggedIdx }

                            if (draggedItem != null) {
                                val currentCenterX = draggedItem.offset.x + (draggedItem.size.width / 2f) + dragOffset.x
                                val currentCenterY = draggedItem.offset.y + (draggedItem.size.height / 2f) + dragOffset.y

                                val targetItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                    currentCenterX.toInt() in item.offset.x..(item.offset.x + item.size.width) &&
                                        currentCenterY.toInt() in item.offset.y..(item.offset.y + item.size.height)
                                }

                                val block = draggedBlock
                                val target = targetItem?.let { document.pages.getOrNull(it.index) }
                                if (targetItem != null && target != null && block != null && target.id !in block) {
                                    // The block over another page: before it when going up, after it going down
                                    val dragged = document.pages[draggedIdx]
                                    val others = document.pages.filter { it.id !in block }
                                    val toIndex = others.indexOf(target) + if (targetItem.index > draggedIdx) 1 else 0
                                    pageManager.moveBlockDuringReorder(block, toIndex)
                                    val newIndex = document.pages.indexOf(dragged)
                                    // The dragged card keeps following the finger, where it goes now
                                    gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == newIndex }?.let { landing ->
                                        dragOffset += Offset(
                                            (draggedItem.offset.x - landing.offset.x).toFloat(),
                                            (draggedItem.offset.y - landing.offset.y).toFloat()
                                        )
                                    }
                                    draggedIndex = newIndex
                                } else if (targetItem != null && block == null && targetItem.index != draggedIdx && targetItem.index < document.pages.size) {
                                    // The dragged card keeps following the finger after the swap
                                    val offsetXFix = draggedItem.offset.x - targetItem.offset.x
                                    val offsetYFix = draggedItem.offset.y - targetItem.offset.y
                                    dragOffset = Offset(
                                        x = dragOffset.x + offsetXFix,
                                        y = dragOffset.y + offsetYFix
                                    )

                                    pageManager.movePageDuringReorder(draggedIdx, targetItem.index)
                                    draggedIndex = targetItem.index
                                }
                            }
                        }
                    )
                }
        ) {
            // The pages as this composition shows them: the document may change before its items are
            // composed (pages removed), and an item reads the list it was counted from
            items(
                count = pages.size,
                key = { index -> pages[index].id }
            ) { index ->
                val page = pages[index]
                var menuExpanded by remember { mutableStateOf(false) }
                val isDragged = index == draggedIndex

                // Exact proportions of the sheet
                val aspectRatio = if (page.heightMm > 0f) page.widthMm / page.heightMm else (1f / 1.414f)

                val current = page.id == currentPageId
                val selected = selection.isSelected(page.id)
                // What the menu of the card acts on: the selection when the page is in it
                val targets = if (selected) selection.pageIds else listOf(page.id)
                val colors = MaterialTheme.colorScheme
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            if (isDragged) {
                                translationX = dragOffset.x
                                translationY = dragOffset.y
                                scaleX = 1.05f
                                scaleY = 1.05f
                                rotationZ = -4f
                            }
                        }
                        .then(if (isDragged) Modifier else Modifier.animateItem())
                ) {
                    // The page in its well, ringed when it is the one looked at
                    val wellShape = RoundedCornerShape(20.dp)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.8f)
                            .then(if (isDragged) Modifier.elevation(DrawViewElevation.Level3, wellShape) else Modifier)
                            .clip(wellShape)
                            .background(if (isDragged) colors.surfaceContainerLowest else colors.surfaceContainer)
                            .then(
                                when {
                                    selected -> Modifier.border(3.dp, colors.primary, wellShape)
                                    current -> Modifier.border(2.dp, colors.primary, wellShape)
                                    else -> Modifier
                                }
                            )
                            .pageClicks(
                                label = stringResource(Res.string.page_grid_content_desc_page, index + 1),
                                selected = selected,
                                onToggle = { selection.toggle(page.id) },
                                onRange = { selection.selectRange(page.id) },
                                onClick = { if (selection.isActive) selection.toggle(page.id) else pageManager.jumpToPage(index) }
                            )
                            .padding(16.dp)
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .aspectRatio(aspectRatio)
                                .elevation(DrawViewElevation.Level1, RoundedCornerShape(4.dp))
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceIn(1, MAX_THUMBNAIL_WIDTH_PX)
                            val thumbnail = rememberPageThumbnail(controller, page, widthPx)

                            // Background of the sheet (shown while the thumbnail is rendered)
                            PageThumbnailBackground(page = page)

                            if (thumbnail != null) {
                                Image(
                                    bitmap = thumbnail,
                                    // Described by its card
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                        if (selected) {
                            Icon(
                                DrawViewIcons.CheckCircle,
                                contentDescription = null,
                                tint = colors.onPrimary,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-8).dp)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(colors.primary)
                                    .padding(2.dp)
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                        Text(
                            text = stringResource(Res.string.page_grid_content_desc_page, index + 1),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (current) colors.primary else colors.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (!controller.isReadOnly) {
                            Box {
                                DesignIconButton(
                                    icon = DrawViewIcons.MoreVertical,
                                    label = stringResource(Res.string.page_grid_content_desc_options),
                                    onClick = { menuExpanded = true },
                                    iconSize = 20.dp
                                )
                                DesignMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                    MenuItem(
                                        text = stringResource(Res.string.context_menu_action_add_page_after),
                                        icon = rememberVectorPainter(DrawViewIcons.FilePlus2),
                                        onClick = {
                                            menuExpanded = false
                                            pageManager.contextMenuTargetPageIndex = index
                                            pageManager.addNewPageAfter(index)
                                        }
                                    )
                                    if (commands != null) {
                                        MenuItem(
                                            text = stringResource(Res.string.context_menu_action_copy_page),
                                            icon = rememberVectorPainter(DrawViewIcons.Copy),
                                            onClick = {
                                                menuExpanded = false
                                                pageManager.contextMenuTargetPageIndex = index
                                                commands.copyPages(targets)
                                            }
                                        )
                                        if (commands.canPastePages()) {
                                            MenuItem(
                                                text = stringResource(Res.string.context_menu_action_paste_page),
                                                icon = rememberVectorPainter(DrawViewIcons.Clipboard),
                                                onClick = {
                                                    menuExpanded = false
                                                    pageManager.contextMenuTargetPageIndex = index
                                                    commands.pastePagesAfter(targets)
                                                }
                                            )
                                        }
                                    }
                                    MenuItem(
                                        text = stringResource(Res.string.context_menu_action_edit_background),
                                        icon = rememberVectorPainter(DrawViewIcons.Grid),
                                        onClick = {
                                            menuExpanded = false
                                            editBackground(targets)
                                        }
                                    )
                                    if (pageManager.canDeletePages(targets)) {
                                        MenuDivider()
                                        MenuItem(
                                            text = stringResource(Res.string.context_menu_action_delete_page),
                                            icon = rememberVectorPainter(DrawViewIcons.Trash),
                                            destructive = true,
                                            onClick = {
                                                menuExpanded = false
                                                pageManager.contextMenuTargetPageIndex = index
                                                pageManager.deletePages(targets)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            deletedPages(controller, deleted, now, deletedExpanded, onToggle = { deletedExpanded = !deletedExpanded })
        }
    }
}

/**
 * The pages removed in the last 30 days at [now] ([deleted]), after the others (see
 * `docs/trash.md`): a row telling how many, which shows them when [expanded], each faded with the
 * days it has left and Restore.
 */
private fun LazyGridScope.deletedPages(controller: DrawViewController, deleted: List<DrawPage>, now: Long, expanded: Boolean, onToggle: () -> Unit) {
    if (deleted.isEmpty()) return
    item(key = "deleted-pages", span = { GridItemSpan(maxLineSpan) }) {
        val colors = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(DrawViewIcons.Trash, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("${stringResource(Res.string.page_grid_deleted_pages)} (${deleted.size})", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Text(stringResource(Res.string.page_grid_deleted_pages_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(if (expanded) DrawViewIcons.ChevronUp else DrawViewIcons.ChevronDown, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
    if (!expanded) return
    items(deleted.size, key = { "deleted:${deleted[it].id}" }) { index ->
        val page = deleted[index]
        val colors = MaterialTheme.colorScheme
        val left = ((page.removed?.time ?: now) + TrashRetention.MILLIS - now)
        val days = ((left + DAY_MILLIS - 1) / DAY_MILLIS).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().animateItem()) {
            val aspectRatio = if (page.heightMm > 0f) page.widthMm / page.heightMm else (1f / 1.414f)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.8f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceContainer)
                    .padding(16.dp)
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .aspectRatio(aspectRatio)
                        .alpha(0.55f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceIn(1, MAX_THUMBNAIL_WIDTH_PX)
                    val thumbnail = rememberPageThumbnail(controller, page, widthPx)
                    PageThumbnailBackground(page = page)
                    if (thumbnail != null) {
                        Image(bitmap = thumbnail, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
                Text(
                    pluralStringResource(Res.plurals.page_grid_days_left, days, days),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                DesignIconButton(
                    icon = DrawViewIcons.RotateCcw,
                    label = stringResource(Res.string.page_grid_action_restore),
                    onClick = { controller.pageManager.restorePages(listOf(page.id)) },
                    iconSize = 20.dp
                )
            }
        }
    }
}

/** A day, in milliseconds. */
private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * Renders (off the main thread) and remembers the thumbnail of [page], refreshed when its
 * content changes.
 */
@Composable
private fun rememberPageThumbnail(controller: DrawViewController, page: DrawPage, widthPx: Int): ImageBitmap? {
    controller.document.observeChanges()
    var thumbnail by remember(page.id) { mutableStateOf<ImageBitmap?>(null) }
    val contentVersion = page.contentVersion
    val format = page.widthMm to page.heightMm
    val background = page.background
    LaunchedEffect(page.id, contentVersion, widthPx, format, background) {
        thumbnail = withContext(Dispatchers.Default) {
            try {
                controller.tileManager.renderThumbnail(controller.document, page, widthPx)
            } catch (_: Throwable) {
                null
            }
        }
    }
    return thumbnail
}

/**
 * Paper and pattern of the page background, scaled for the thumbnails.
 */
@Composable
internal fun PageThumbnailBackground(
    page: DrawPage,
    modifier: Modifier = Modifier
) {
    val bg = page.background
    val pageWidthMm = page.widthMm

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1. Paper color
        drawRect(color = Color(bg.backgroundColor))

        if (bg is PageBackground.Solid) return@Canvas

        // 2. Pattern scaled for the thumbnail
        val pixelsPerMm = w / pageWidthMm.coerceAtLeast(1f)

        val (lineColor, spacingMm, thicknessMm) = when (bg) {
            is PageBackground.Ruled -> Triple(Color(bg.lineColor), bg.spacingMm, bg.thicknessMm)
            is PageBackground.Grid -> Triple(Color(bg.lineColor), bg.spacingMm, bg.thicknessMm)
            is PageBackground.Dotted -> Triple(Color(bg.dotColor), bg.spacingMm, bg.dotRadiusMm)
            is PageBackground.Solid -> Triple(Color.Transparent, 1f, 1f)
        }

        val paintColor = lineColor.copy(alpha = 0.4f)

        // The pattern must not become an unreadable smudge on small thumbnails
        val spacingPx = (spacingMm * pixelsPerMm).coerceAtLeast(6f)
        val thicknessPx = (thicknessMm * pixelsPerMm).coerceAtLeast(1f)

        when (bg) {
            is PageBackground.Ruled -> {
                var y = spacingPx
                while (y < h) {
                    drawLine(paintColor, Offset(0f, y), Offset(w, y), thicknessPx)
                    y += spacingPx
                }
            }
            is PageBackground.Grid -> {
                var y = spacingPx
                while (y < h) {
                    drawLine(paintColor, Offset(0f, y), Offset(w, y), thicknessPx)
                    y += spacingPx
                }
                var x = spacingPx
                while (x < w) {
                    drawLine(paintColor, Offset(x, 0f), Offset(x, h), thicknessPx)
                    x += spacingPx
                }
            }
            is PageBackground.Dotted -> {
                var x = spacingPx
                while (x < w) {
                    var y = spacingPx
                    while (y < h) {
                        drawCircle(paintColor, thicknessPx, Offset(x, y))
                        y += spacingPx
                    }
                    x += spacingPx
                }
            }
            is PageBackground.Solid -> {}
        }
    }
}

private const val MAX_THUMBNAIL_WIDTH_PX = 600

/** The keys held at the last press of a pointer on a page (read by its click). */
private class HeldKeys {
    var value: PointerKeyboardModifiers? = null
}

/**
 * The clicks on a page of the grid: with the modifier of the shortcuts (⌘ or Ctrl) the page is
 * added to the selection or taken out of it ([onToggle]), with Shift the selection extends to it
 * ([onRange]), as in the files of the systems; else [onClick].
 */
@Composable
private fun Modifier.pageClicks(
    label: String,
    selected: Boolean,
    onToggle: () -> Unit,
    onRange: () -> Unit,
    onClick: () -> Unit
): Modifier {
    val held = remember { HeldKeys() }
    val apple = currentAdaptiveInfo().conventions.shortcutModifier == ShortcutModifier.COMMAND
    return this
        .pointerInput(held) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) held.value = event.keyboardModifiers
                }
            }
        }
        .semantics {
            contentDescription = label
            this.selected = selected
        }
        .clickable(onClickLabel = label) {
            val keys = held.value
            held.value = null
            when {
                keys != null && keys.isShiftPressed -> onRange()
                keys != null && (if (apple) keys.isMetaPressed else keys.isCtrlPressed) -> onToggle()
                else -> onClick()
            }
        }
}

/**
 * The top bar of the grid while selecting: Done, how many pages are selected ([count], the pages
 * selected still in the document), Select all, and the actions on all of them (in a read-only
 * document, copying only).
 */
@Composable
private fun PageSelectionBar(
    selection: PageSelection,
    count: Int,
    writable: Boolean,
    canPaste: Boolean,
    canDelete: Boolean,
    onCopy: (() -> Unit)?,
    onPaste: () -> Unit,
    onEditBackground: () -> Unit,
    onDelete: () -> Unit,
    extensionActions: List<Command> = emptyList()
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DesignIconButton(DrawViewIcons.Check, stringResource(Res.string.page_grid_action_done), { selection.clear() }, tint = colors.onSurface)
        Text(
            text = pluralStringResource(Res.plurals.page_grid_selected_count, count, count),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        val some = count > 0
        // In a compact window, Select all, Paste after and Background in a menu
        val compact = currentAdaptiveInfo().window.isCompact
        if (!compact) {
            DesignIconButton(DrawViewIcons.CheckCheck, stringResource(Res.string.page_grid_action_select_all), { selection.selectAll() }, tint = colors.onSurface)
        }
        if (onCopy != null) {
            DesignIconButton(DrawViewIcons.Copy, stringResource(Res.string.common_action_copy), onCopy, enabled = some, tint = colors.onSurface)
        }
        // The actions of the extensions (e.g. "Export…"), in read-only documents too
        for (action in extensionActions) {
            val label = action.label()
            HoverTooltip(label) {
                IconButton(onClick = { action.performIfEnabled() }, enabled = action.isEnabled()) {
                    action.icon?.let { Icon(it(), contentDescription = label, tint = colors.onSurface, modifier = Modifier.size(22.dp)) }
                }
            }
        }
        if (writable && !compact) {
            if (canPaste) {
                DesignIconButton(DrawViewIcons.Clipboard, stringResource(Res.string.context_menu_action_paste_page), onPaste, enabled = some, tint = colors.onSurface)
            }
            DesignIconButton(DrawViewIcons.Grid, stringResource(Res.string.context_menu_action_edit_background), onEditBackground, enabled = some, tint = colors.onSurface)
        }
        if (writable) {
            DesignIconButton(DrawViewIcons.Trash, stringResource(Res.string.common_action_delete), onDelete, enabled = canDelete, tint = colors.error)
        }
        if (compact) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                DesignIconButton(DrawViewIcons.MoreVertical, stringResource(Res.string.common_action_more_options), { expanded = true }, tint = colors.onSurface)
                DesignMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    MenuItem(
                        text = stringResource(Res.string.page_grid_action_select_all),
                        icon = rememberVectorPainter(DrawViewIcons.CheckCheck),
                        onClick = { expanded = false; selection.selectAll() }
                    )
                    if (writable && some) {
                        if (canPaste) {
                            MenuItem(
                                text = stringResource(Res.string.context_menu_action_paste_page),
                                icon = rememberVectorPainter(DrawViewIcons.Clipboard),
                                onClick = { expanded = false; onPaste() }
                            )
                        }
                        MenuItem(
                            text = stringResource(Res.string.context_menu_action_edit_background),
                            icon = rememberVectorPainter(DrawViewIcons.Grid),
                            onClick = { expanded = false; onEditBackground() }
                        )
                    }
                }
            }
        }
    }
}
