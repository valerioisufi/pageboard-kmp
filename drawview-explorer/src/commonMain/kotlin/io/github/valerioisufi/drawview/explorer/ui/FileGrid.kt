package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.explorer.DocumentThumbnails
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileItem
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.icon_document
import io.github.valerioisufi.drawview.ui.generated.resources.img_folder
import org.jetbrains.compose.resources.painterResource
import io.github.valerioisufi.drawview.ui.generated.resources.Res as DrawViewRes

/** The narrowest card of the grid. */
private val CardMinWidth = 168.dp

/** Thumbnails drawn at widths multiple of this (in pixels), so that close sizes share them. */
private const val ThumbnailWidthStep = 120

/** The widest thumbnail drawn (in pixels). */
private const val ThumbnailMaxWidth = 600

/**
 * Content of a folder in a grid (a tablet, a desktop): the sort options and a card for each
 * folder and document, the documents showing their first page.
 *
 * @param onShowDetails shows the details of an item elsewhere (beside, in a wide library); `null`:
 *   in a sheet.
 */
@Composable
internal fun FileGrid(
    directoryFiles: List<FileItem>,
    fileExplorerViewModel: FileExplorerViewModel,
    onOpenDocument: (String) -> Unit,
    thumbnails: DocumentThumbnails,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    commands: LibraryCommands? = null,
    onShowDetails: ((FileItem) -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        if (!fileExplorerViewModel.selectionMode) {
            SortHeader(fileExplorerViewModel)
        } else {
            Spacer(modifier = Modifier.height(12.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(CardMinWidth),
            state = gridState,
            contentPadding = PaddingValues(bottom = 88.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(directoryFiles, key = { it.key }) { file ->
                FileGridItem(
                    dataFile = file,
                    fileExplorerViewModel = fileExplorerViewModel,
                    onOpenDocument = onOpenDocument,
                    thumbnails = thumbnails,
                    commands = commands,
                    onShowDetails = onShowDetails,
                    modifier = Modifier.animateItem()
                )
            }
            // Room for the floating button
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(modifier = Modifier.height(1.dp)) }
        }
    }
}

/**
 * A folder or a document as a card: the first page of a document (its thumbnail) or the icon of a
 * folder, the name, the date of the last change and the button of its details.
 */
@Composable
internal fun FileGridItem(
    dataFile: FileItem,
    fileExplorerViewModel: FileExplorerViewModel,
    onOpenDocument: (String) -> Unit,
    thumbnails: DocumentThumbnails,
    modifier: Modifier = Modifier,
    commands: LibraryCommands? = null,
    onShowDetails: ((FileItem) -> Unit)? = null
) {
    val isSelected = fileExplorerViewModel.isSelected(dataFile)
    val itemCommands = remember(commands, dataFile) { commands?.forItem(dataFile).orEmpty() }
    var contextMenu by remember { mutableStateOf<DpOffset?>(null) }
    val shape = RoundedCornerShape(16.dp)

    Box(modifier = modifier) {
        Surface(
            shape = shape,
            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                // Dragged with a mouse onto a folder; a folder takes the items dropped onto it
                .draggableItem(dataFile, fileExplorerViewModel)
                .then(if (dataFile.type == FileType.FOLDER) Modifier.dropTarget(fileExplorerViewModel, dataFile.id) else Modifier)
                .fileItemInteraction(dataFile, fileExplorerViewModel, onOpenDocument) { position ->
                    if (itemCommands.isNotEmpty()) contextMenu = position
                }
        ) {
            Column {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.85f)
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                ) {
                    if (dataFile.type == FileType.FILE) {
                        DocumentThumbnail(dataFile, thumbnails)
                    } else {
                        Image(
                            painter = painterResource(DrawViewRes.drawable.img_folder),
                            contentDescription = null,
                            modifier = Modifier.size(88.dp)
                        )
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    FileDescription(
                        dataFile = dataFile,
                        contentColor = MaterialTheme.colorScheme.onSurface.takeUnless { isSelected } ?: MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                    if (!fileExplorerViewModel.selectionMode) {
                        FileDetailsButton(dataFile, itemCommands, onShowDetails)
                    }
                }
            }
        }
        // Opened where the pointer is, from the corner of the item
        FileContextMenu(itemCommands, contextMenu, onDismiss = { contextMenu = null })
    }
}

/**
 * The first page of the document [dataFile], drawn by [thumbnails] as large as the card (the icon
 * of the documents until it is drawn), with the shadow of a sheet of paper.
 */
@Composable
private fun DocumentThumbnail(dataFile: FileItem, thumbnails: DocumentThumbnails) {
    BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            .let { ((it + ThumbnailWidthStep - 1) / ThumbnailWidthStep) * ThumbnailWidthStep }
            .coerceIn(ThumbnailWidthStep, ThumbnailMaxWidth)
        val image by produceState<ImageBitmap?>(null, dataFile.id, dataFile.modifiedAt, widthPx) {
            value = thumbnails.of(dataFile.id, dataFile.modifiedAt, widthPx)
        }
        val page = image
        if (page != null) {
            Image(
                bitmap = page,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .aspectRatio(page.width.toFloat() / page.height)
                    .shadow(2.dp, RoundedCornerShape(2.dp))
            )
        } else {
            Image(
                painter = painterResource(Res.drawable.icon_document),
                contentDescription = null,
                modifier = Modifier.size(64.dp)
            )
        }
    }
}
