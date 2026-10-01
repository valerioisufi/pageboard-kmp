package io.github.valerioisufi.drawview.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.ui.generated.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val pageManager = controller.pageManager
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // Absorbs the taps that fall between the cards
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {}
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // --- Top bar of the grid ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(Res.string.page_grid_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = { controller.togglePageGrid() }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.page_grid_action_close),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        HorizontalDivider()

        // --- Drag & drop state ---
        val gridState = rememberLazyGridState()
        var draggedIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(16.dp),
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
                key = { index -> document.pages[index].uid }
            ) { index ->
                val page = document.pages[index]
                var menuExpanded by remember { mutableStateOf(false) }
                val isDragged = index == draggedIndex

                // Exact proportions of the sheet
                val aspectRatio = if (page.heightMm > 0f) page.widthMm / page.heightMm else (1f / 1.414f)

                ElevatedCard(
                    onClick = { pageManager.jumpToPage(index) },
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isDragged) 16.dp else 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            if (isDragged) {
                                translationX = dragOffset.x
                                translationY = dragOffset.y
                                scaleX = 1.05f
                                scaleY = 1.05f
                                alpha = 0.9f
                            }
                        }
                        .then(if (isDragged) Modifier else Modifier.animateItem())
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspectRatio)
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
                        } else {
                            Text(stringResource(Res.string.common_state_loading), color = Color.Gray)
                        }

                        // Page number badge
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Page options
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                        ) {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(Res.string.page_grid_content_desc_options),
                                    tint = Color.White
                                )
                            }

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.context_menu_action_add_page_after)) },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        pageManager.contextMenuTargetPageIndex = index
                                        pageManager.addNewPageAfter(index)
                                    }
                                )

                                if (document.pages.size > 1) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.context_menu_action_delete_page), color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            pageManager.contextMenuTargetPageIndex = index
                                            pageManager.deletePage(index)
                                        }
                                    )
                                }

                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.context_menu_action_edit_background)) },
                                    leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        pageManager.contextMenuTargetPageIndex = index
                                        // The grid is closed to show the configurator
                                        controller.togglePageGrid()
                                        onEditPageBackground(index)
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

/**
 * Renders (off the main thread) and remembers the thumbnail of [page], refreshed when its
 * content changes.
 */
@Composable
private fun rememberPageThumbnail(controller: DrawViewController, page: DrawPage, widthPx: Int): ImageBitmap? {
    var thumbnail by remember(page.uid) { mutableStateOf<ImageBitmap?>(null) }
    val contentVersion = page.contentVersion
    val format = page.widthMm to page.heightMm
    val background = page.background
    LaunchedEffect(page.uid, contentVersion, widthPx, format, background) {
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
