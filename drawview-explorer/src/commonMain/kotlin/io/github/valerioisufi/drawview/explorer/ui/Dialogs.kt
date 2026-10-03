package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.valerioisufi.drawview.data.FolderSummary
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_back
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_confirm
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_root_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_cancel
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_move_here
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_move_empty_state
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Asks the name of a new item (or the new name of an item). [isAllowedInput] returns the error
 * of a name, `null` when it is valid; [error] is an error found after the confirmation.
 */
@Composable
fun RequestNameDialog(
    title: String = "",
    labelTextField: String = "",
    textConfirmButton: String = "",
    onDismissRequest: () -> Unit,
    onConfirm: (inputText: String) -> Unit,
    isAllowedInput: (inputText: String) -> StringResource? = { null },
    initialText: String = "",
    error: StringResource? = null
) {
    var inputError by remember { mutableStateOf<StringResource?>(null) }
    var text by remember { mutableStateOf(initialText) }
    val shownError = inputError ?: error

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
        title = { Text(text = title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        inputError = isAllowedInput(it)
                    },
                    label = { Text(labelTextField) },
                    singleLine = true,
                    isError = shownError != null,
                    supportingText = {
                        shownError?.let { resource ->
                            Text(
                                text = stringResource(resource),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank() && inputError == null
            ) {
                Text(text = textConfirmButton)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(stringResource(Res.string.explorer_action_cancel))
            }
        },
        shape = MaterialTheme.shapes.extraLarge
    )
}

@Composable
fun ConfirmActionDialog(
    title: String = "",
    textDescription: String = "",
    textConfirmButton: String = stringResource(Res.string.explorer_action_confirm),
    textCancelButton: String = stringResource(Res.string.explorer_action_cancel),
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = title) },
        text = { Text(text = textDescription) },
        confirmButton = {
            TextButton(
                onClick = onConfirm
            ) {
                Text(text = textConfirmButton, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(text = textCancelButton)
            }
        },
        shape = MaterialTheme.shapes.extraLarge
    )
}

/**
 * Chooses the folder where items are moved, navigating the folder tree from the root.
 * The folders being moved cannot be entered ([isFolderSelected]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveFileDialog(
    getSubFolders: (Int?) -> List<FolderSummary>,
    isFolderSelected: (folderId: Int) -> Boolean,
    isValidMove: (targetFolderId: Int?) -> Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (targetFolderId: Int?) -> Unit
) {
    val rootName = stringResource(Res.string.explorer_root_folder)
    // Navigation state inside the dialog: the folders from the root (null) to the current one
    var breadcrumbs by remember { mutableStateOf(listOf<Pair<String?, Int?>>(null to null)) }
    val currentFolderId = breadcrumbs.last().second
    val subFolders = getSubFolders(currentFolderId)

    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Header with navigation
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    if (currentFolderId != null) {
                        IconButton(onClick = { breadcrumbs = breadcrumbs.dropLast(1) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.explorer_action_back)
                            )
                        }
                    }

                    Text(
                        text = breadcrumbs.last().first ?: rootName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f)
                    )
                }

                HorizontalDivider()

                // Content
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                ) {
                    items(items = subFolders, key = { it.id }) { folder ->
                        val isEnabled = !isFolderSelected(folder.id)

                        ListItem(
                            headlineContent = { Text(folder.name) },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = if (isEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .clickable(enabled = isEnabled) {
                                    breadcrumbs = breadcrumbs + (folder.name to folder.id)
                                }
                        )
                    }

                    if (subFolders.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(Res.string.explorer_move_empty_state),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(Res.string.explorer_action_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(currentFolderId) },
                        enabled = isValidMove(currentFolderId)
                    ) {
                        Text(stringResource(Res.string.explorer_move_here))
                    }
                }
            }
        }
    }
}
