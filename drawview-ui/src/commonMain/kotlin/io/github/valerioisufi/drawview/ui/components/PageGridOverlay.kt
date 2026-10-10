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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.valerioisufi.drawview.core.canvas.observeChanges
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.command.DesignMenu
import io.github.valerioisufi.drawview.ui.command.MenuDivider
import io.github.valerioisufi.drawview.ui.command.MenuItem
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.theme.DrawViewElevation
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.elevation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen grid of the pages of the document: thumbnails, jump to a page, page actions
 * (add after, delete, background) and reordering by drag & drop after a long press.
 *
 * @param onEditPageBackground opens the template configurator of the page at the given index.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageGridOverlay(
    controller: DrawViewController,
    modifier: Modifier = Modifier,
    onEditPageBackground: (Int) -> Unit = {}
) {
    val document = controller.document
    // The pages, their formats and their content as they change
    document.observeChanges()
    val pageManager = controller.pageManager
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
        // --- Top bar of the grid: close, the title with the document and its pages, adding a page ---
        Row(
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
            if (!controller.isReadOnly) {
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
        // The page looked at when the grid was opened
        val currentPage = remember { controller.mostVisiblePageIndex() ?: 0 }
        var draggedIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }

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
                                draggedIndex = item.index
                                pageManager.startPageReorderMode()
                            }
                        },
                        onDragEnd = {
                            if (draggedIndex != null) {
                                pageManager.finishPageReorderMode()
                                draggedIndex = null
                                dragOffset = Offset.Zero
                            }
                        },
                        onDragCancel = {
                            if (draggedIndex != null) {
                                pageManager.finishPageReorderMode()
                                draggedIndex = null
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

                                if (targetItem != null && targetItem.index != draggedIdx) {
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
            items(
                count = document.pages.size,
                key = { index -> document.pages[index].id }
            ) { index ->
                val page = document.pages[index]
                var menuExpanded by remember { mutableStateOf(false) }
                val isDragged = index == draggedIndex

                // Exact proportions of the sheet
                val aspectRatio = if (page.heightMm > 0f) page.widthMm / page.heightMm else (1f / 1.414f)

                val current = index == currentPage
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
                            .then(if (current) Modifier.border(2.dp, colors.primary, wellShape) else Modifier)
                            .clickable(onClickLabel = stringResource(Res.string.page_grid_content_desc_page, index + 1)) { pageManager.jumpToPage(index) }
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
                                    contentDescription = stringResource(Res.string.page_grid_content_desc_page, index + 1),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
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
                                    MenuItem(
                                        text = stringResource(Res.string.context_menu_action_edit_background),
                                        icon = rememberVectorPainter(DrawViewIcons.Grid),
                                        onClick = {
                                            menuExpanded = false
                                            pageManager.contextMenuTargetPageIndex = index
                                            // The grid is closed to show the configurator
                                            controller.togglePageGrid()
                                            onEditPageBackground(index)
                                        }
                                    )
                                    if (document.pages.size > 1) {
                                        MenuDivider()
                                        MenuItem(
                                            text = stringResource(Res.string.context_menu_action_delete_page),
                                            icon = rememberVectorPainter(DrawViewIcons.Trash),
                                            destructive = true,
                                            onClick = {
                                                menuExpanded = false
                                                pageManager.contextMenuTargetPageIndex = index
                                                pageManager.deletePage(index)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
fun PageThumbnailBackground(
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
