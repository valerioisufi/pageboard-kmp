package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.South
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ui.generated.resources.date_months_short
import io.github.valerioisufi.drawview.ui.util.formatDateTime
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileItem
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.SortOption
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_confirm
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_details
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_dialog_delete_description
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_label_last_modified
import io.github.valerioisufi.drawview.explorer.generated.resources.icon_document
import io.github.valerioisufi.drawview.ui.generated.resources.img_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_date_created
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_delete
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_folders_on_top
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_last_modified
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_last_opened
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_move_to
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_name
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_rename
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_sort_invert
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_input_name
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import io.github.valerioisufi.drawview.ui.generated.resources.Res as DrawViewRes

/**
 * Content of a folder: the sort options and the list of its folders and documents.
 */
@Composable
fun FileList(
    directoryFiles: List<FileItem>,
    fileExplorerViewModel: FileExplorerViewModel,
    onOpenDocument: (Int) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    val selectionMode = fileExplorerViewModel.selectionMode

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp, 0.dp),
    ) {
        if (!selectionMode) {
            SortHeader(fileExplorerViewModel)
        } else {
            Spacer(modifier = Modifier.height(12.dp))
        }

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(directoryFiles, key = { it.key }) { file ->
                FileListItem(
                    modifier = Modifier.animateItem(),
                    dataFile = file,
                    fileExplorerViewModel = fileExplorerViewModel,
                    onOpenDocument = onOpenDocument
                )
            }
            item {
                Spacer(modifier = Modifier.height(64.dp))
            }
        }
    }
}

@Composable
private fun SortHeader(fileExplorerViewModel: FileExplorerViewModel) {
    Row(
        modifier = Modifier.height(48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        var expanded by remember { mutableStateOf(false) }

        val sortLabel = when (fileExplorerViewModel.sortOption) {
            SortOption.NAME -> stringResource(Res.string.explorer_sort_name)
            SortOption.DATE_CREATED -> stringResource(Res.string.explorer_sort_date_created)
            SortOption.DATE_MODIFIED -> stringResource(Res.string.explorer_sort_last_modified)
            SortOption.LAST_OPENED -> stringResource(Res.string.explorer_sort_last_opened)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = true }) {
                Text(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    text = sortLabel,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Inverts the order (ascending / descending)
            IconButton(onClick = { fileExplorerViewModel.toggleSortDirection() }) {
                Icon(
                    imageVector = if (fileExplorerViewModel.sortAscending) Icons.Default.North else Icons.Default.South,
                    contentDescription = stringResource(Res.string.explorer_sort_invert),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            listOf(
                SortOption.DATE_CREATED to Res.string.explorer_sort_date_created,
                SortOption.DATE_MODIFIED to Res.string.explorer_sort_last_modified,
                SortOption.LAST_OPENED to Res.string.explorer_sort_last_opened,
                SortOption.NAME to Res.string.explorer_sort_name
            ).forEach { (option, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        fileExplorerViewModel.selectSortOption(option)
                        expanded = false
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.explorer_sort_folders_on_top)) },
                onClick = {
                    fileExplorerViewModel.toggleKeepFoldersOnTop()
                    expanded = false
                },
                leadingIcon = {
                    Checkbox(
                        checked = fileExplorerViewModel.keepFoldersOnTop,
                        onCheckedChange = null
                    )
                }
            )
        }
    }
}

/**
 * A folder or a document: a tap opens it (or selects it in selection mode), a long press
 * selects it, the trailing button shows its details and actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    dataFile: FileItem,
    fileExplorerViewModel: FileExplorerViewModel,
    onOpenDocument: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectionMode = fileExplorerViewModel.selectionMode
    val isSelected = fileExplorerViewModel.isSelected(dataFile)

    // Visuals for selection
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth(1f)
            .background(
                backgroundColor, shape = RoundedCornerShape(8.dp)
            )
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        fileExplorerViewModel.toggleSelection(dataFile)
                    } else when (dataFile.type) {
                        FileType.FILE -> onOpenDocument(dataFile.id)
                        FileType.FOLDER -> fileExplorerViewModel.enterFolder(dataFile)
                    }
                },
                onLongClick = {
                    fileExplorerViewModel.toggleSelection(dataFile)
                }
            )
            .padding(0.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection checkbox or icon
        if (selectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { fileExplorerViewModel.toggleSelection(dataFile) },
                modifier = Modifier
                    .padding(start = 16.dp)
                    .size(24.dp)
            )
        } else {
            FileIcon(
                type = dataFile.type,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .size(32.dp)
            )
        }

        FileDescription(
            dataFile = dataFile,
            contentColor = contentColor,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        )

        if (!selectionMode) {
            FileDetailsSheet(dataFile, fileExplorerViewModel)
        }
    }
}

@Composable
private fun FileIcon(type: FileType, modifier: Modifier = Modifier) {
    Image(
        modifier = modifier,
        painter = painterResource(if (type == FileType.FILE) Res.drawable.icon_document else DrawViewRes.drawable.img_folder),
        contentDescription = null
    )
}

/** Name and date of the last change of an item. */
@Composable
private fun FileDescription(
    dataFile: FileItem,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier) {
        Text(
            text = dataFile.name,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor
        )
        val monthNames = stringArrayResource(DrawViewRes.array.date_months_short)
        val date = remember(dataFile.modifiedAt, monthNames) { formatDateTime(dataFile.modifiedAt, monthNames) }
        Text(
            text = stringResource(Res.string.explorer_label_last_modified, date),
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.7f)
        )
    }
}

/** The button that shows the details of an item and its actions (rename, move, delete). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileDetailsSheet(
    dataFile: FileItem,
    fileExplorerViewModel: FileExplorerViewModel
) {
    var isSheetOpen by rememberSaveable {
        mutableStateOf(false)
    }

    IconButton(onClick = {
        isSheetOpen = true
    }) {
        Icon(imageVector = Icons.Default.MoreHoriz, contentDescription = stringResource(Res.string.explorer_action_details))
    }

    if (isSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { isSheetOpen = false },
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(1f)
                        .padding(16.dp, 0.dp)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FileIcon(type = dataFile.type, modifier = Modifier.size(32.dp))
                    FileDescription(
                        dataFile = dataFile,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp)
                    )
                }

                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )

                var showRenameDialog by remember { mutableStateOf(false) }
                if (showRenameDialog) {
                    RequestNameDialog(
                        title = stringResource(Res.string.explorer_action_rename),
                        labelTextField = stringResource(Res.string.explorer_input_name),
                        textConfirmButton = stringResource(Res.string.explorer_action_confirm),
                        initialText = dataFile.name,
                        onDismissRequest = { showRenameDialog = false },
                        isAllowedInput = { text ->
                            fileExplorerViewModel.validateFileName(text, renamedFile = dataFile)
                        },
                        onConfirm = { text ->
                            fileExplorerViewModel.renameFile(dataFile, text)
                            showRenameDialog = false
                        }
                    )
                }

                var showDeleteConfirmDialog by remember { mutableStateOf(false) }
                if (showDeleteConfirmDialog) {
                    ConfirmActionDialog(
                        title = stringResource(Res.string.explorer_action_delete),
                        textDescription = stringResource(Res.string.explorer_dialog_delete_description),
                        textConfirmButton = stringResource(Res.string.explorer_action_confirm),
                        onDismissRequest = { showDeleteConfirmDialog = false },
                        onConfirm = {
                            fileExplorerViewModel.deleteFile(dataFile)
                            showDeleteConfirmDialog = false
                            isSheetOpen = false
                        }
                    )
                }

                var showMoveDialog by remember { mutableStateOf(false) }
                if (showMoveDialog) {
                    MoveFileDialog(
                        onDismissRequest = { showMoveDialog = false },
                        getSubFolders = { folderId -> fileExplorerViewModel.getSubFolders(folderId) },
                        isFolderSelected = { folderId ->
                            dataFile.type == FileType.FOLDER && dataFile.id == folderId
                        },
                        isValidMove = { targetId ->
                            fileExplorerViewModel.isValidMove(targetId, listOf(dataFile))
                        },
                        onConfirm = { targetFolderId ->
                            fileExplorerViewModel.moveFile(dataFile, targetFolderId)
                            showMoveDialog = false
                            isSheetOpen = false
                        }
                    )
                }

                OptionItem(
                    icon = Icons.Filled.DriveFileRenameOutline,
                    text = stringResource(Res.string.explorer_action_rename),
                    onClick = { showRenameDialog = true }
                )
                OptionItem(
                    icon = Icons.AutoMirrored.Filled.DriveFileMove,
                    text = stringResource(Res.string.explorer_action_move_to),
                    onClick = { showMoveDialog = true }
                )
                OptionItem(
                    icon = Icons.Filled.Delete,
                    text = stringResource(Res.string.explorer_action_delete),
                    onClick = { showDeleteConfirmDialog = true }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun OptionItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(16.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}
