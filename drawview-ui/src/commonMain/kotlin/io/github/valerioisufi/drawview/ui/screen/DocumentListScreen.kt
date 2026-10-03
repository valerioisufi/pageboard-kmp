package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.data.DocumentSummary
import io.github.valerioisufi.drawview.ui.components.AboutLicensesDialog
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.util.formatDateTime
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

/**
 * List of the documents (most recently modified first): tap to open, swipe to the end to delete,
 * swipe to the start to rename, "+" to create a new document.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentListScreen(
    documents: List<DocumentSummary>,
    onDocumentClick: (Int) -> Unit,
    onCreateNewClick: () -> Unit,
    onDeleteDocument: (DocumentSummary) -> Unit,
    onRenameDocument: (DocumentSummary, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var showAboutDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.document_list_title_main), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(Res.string.about_dialog_title)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateNewClick) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.document_list_action_create))
            }
        }
    ) { innerPadding ->

        if (documents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(Res.string.document_list_state_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // The key lets Compose animate the removed and added documents
                items(items = documents, key = { it.id }) { doc ->
                    var showRenameDialog by remember { mutableStateOf(false) }

                    val dismissState = rememberSwipeToDismissBoxState()

                    LaunchedEffect(dismissState.currentValue) {
                        when (dismissState.currentValue) {
                            SwipeToDismissBoxValue.EndToStart -> {
                                onDeleteDocument(doc)
                            }
                            SwipeToDismissBoxValue.StartToEnd -> {
                                showRenameDialog = true
                                // Back to the settled state (the rename does not remove the item)
                                dismissState.reset()
                            }
                            SwipeToDismissBoxValue.Settled -> {
                            }
                        }
                    }

                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                            val direction = dismissState.dismissDirection

                            val color by animateColorAsState(
                                targetValue = when (dismissState.targetValue) {
                                    SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.background
                                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer
                                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                },
                                label = "color"
                            )

                            val iconTint = when (direction) {
                                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onSecondaryContainer
                                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onBackground
                            }

                            val alignment = when (direction) {
                                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                else -> Alignment.Center
                            }

                            val icon = when (direction) {
                                SwipeToDismissBoxValue.StartToEnd -> Icons.Default.Edit
                                SwipeToDismissBoxValue.EndToStart -> Icons.Default.Delete
                                else -> Icons.Default.Delete
                            }

                            val scale by animateFloatAsState(
                                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) 0.75f else 1f,
                                label = "scale"
                            )

                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(color, shape = CardDefaults.shape)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = alignment
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = stringResource(Res.string.document_list_action_swipe),
                                    modifier = Modifier.scale(scale),
                                    tint = iconTint
                                )
                            }
                        },
                        content = {
                            DocumentCard(document = doc, onClick = { onDocumentClick(doc.id) })
                        }
                    )

                    if (showRenameDialog) {
                        var newName by remember { mutableStateOf(doc.name) }
                        AlertDialog(
                            onDismissRequest = {
                                showRenameDialog = false
                                coroutineScope.launch { dismissState.reset() }
                            },
                            title = { Text(stringResource(Res.string.document_list_title_rename_dialog)) },
                            text = {
                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    singleLine = true,
                                    label = { Text(stringResource(Res.string.document_list_input_rename_hint)) }
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showRenameDialog = false
                                        // The store updates the list through its flow
                                        onRenameDocument(doc, newName)
                                        coroutineScope.launch { dismissState.reset() }
                                    }
                                ) { Text(stringResource(Res.string.common_button_rename)) }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = {
                                        showRenameDialog = false
                                        coroutineScope.launch { dismissState.reset() }
                                    }
                                ) { Text(stringResource(Res.string.common_button_cancel)) }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAboutDialog) {
        AboutLicensesDialog(onDismiss = { showAboutDialog = false })
    }
}

@Composable
fun DocumentCard(document: DocumentSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = document.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))

                val monthNames = stringArrayResource(Res.array.date_months_short)
                val dateString = remember(document.modifiedAt, monthNames) {
                    formatDateTime(document.modifiedAt, monthNames)
                }
                Text(
                    text = stringResource(Res.string.document_list_label_last_modified, dateString),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
