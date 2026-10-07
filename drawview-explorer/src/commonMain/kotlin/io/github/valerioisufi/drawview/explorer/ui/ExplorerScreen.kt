package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_back
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_clear_selection
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_confirm
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_delete
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_move
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_select_all
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_dialog_delete_multiple_description
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_dialog_new_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_dialog_new_note
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_fab_close_menu
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_fab_item_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_fab_item_note
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_fab_state_collapsed
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_fab_state_expanded
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_fab_toggle_menu
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_input_name
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_selected_count
import io.github.valerioisufi.drawview.ui.components.AboutLicensesDialog
import io.github.valerioisufi.drawview.ui.session.DocumentAction
import io.github.valerioisufi.drawview.ui.generated.resources.about_dialog_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import io.github.valerioisufi.drawview.ui.generated.resources.Res as DrawViewRes

/**
 * The document library: the content of the current folder, the actions on the selected items
 * and the menu that creates notes and folders.
 *
 * @param title title shown in the root folder (typically the name of the application).
 * @param actions actions of the title bar of the root folder: the About & Licenses dialog by default.
 * @param documentActions actions of the application on a selected document (e.g. its export).
 */
@Composable
fun ExplorerScreen(
    fileExplorerViewModel: FileExplorerViewModel,
    onOpenDocument: (String) -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = { AboutLicensesButton() },
    documentActions: List<DocumentAction> = emptyList()
) {
    val listState = rememberLazyListState()
    // The action chosen on a document, shown until it is done (the selection is cleared meanwhile)
    var chosenAction by remember { mutableStateOf<Pair<DocumentAction, FileExplorerViewModel.FileItem>?>(null) }
    chosenAction?.let { (action, item) -> action.content(item.id, item.name) { chosenAction = null } }
    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // The menu is hidden together with its button while items are selected
    LaunchedEffect(fileExplorerViewModel.selectionMode) {
        if (fileExplorerViewModel.selectionMode) fabMenuExpanded = false
    }

    ExplorerBackHandler(
        enabled = fabMenuExpanded || fileExplorerViewModel.canNavigateBack,
        onBack = {
            if (fabMenuExpanded) fabMenuExpanded = false else fileExplorerViewModel.navigateBack()
        }
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            if (fileExplorerViewModel.selectionMode) {
                SelectionTopBar(fileExplorerViewModel, documentActions) { action, item ->
                    chosenAction = action to item
                    fileExplorerViewModel.clearSelection()
                }
            } else {
                FolderTopBar(fileExplorerViewModel, title, actions)
            }
        },
        floatingActionButton = {
            if (!fileExplorerViewModel.selectionMode) {
                NewItemMenu(
                    fileExplorerViewModel = fileExplorerViewModel,
                    listState = listState,
                    expanded = fabMenuExpanded,
                    onExpandedChange = { fabMenuExpanded = it }
                )
            }
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
        ) {
            FileList(
                directoryFiles = fileExplorerViewModel.currentDirectoryFiles,
                fileExplorerViewModel = fileExplorerViewModel,
                onOpenDocument = onOpenDocument,
                listState = listState
            )
        }
    }
}

/** Opens the About & Licenses dialog of the application (see `LocalAboutAppInfo`). */
@Composable
fun AboutLicensesButton() {
    var showAboutDialog by remember { mutableStateOf(false) }
    IconButton(onClick = { showAboutDialog = true }) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = stringResource(DrawViewRes.string.about_dialog_title)
        )
    }
    if (showAboutDialog) {
        AboutLicensesDialog(onDismiss = { showAboutDialog = false })
    }
}

/**
 * System back (Android back gesture, Escape on the desktop, ...) while it has something to do:
 * otherwise the platform handles it (e.g. Android closes the application).
 */
@Composable
private fun ExplorerBackHandler(enabled: Boolean, onBack: () -> Unit) {
    if (LocalNavigationEventDispatcherOwner.current == null) return
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = state,
        isBackEnabled = enabled,
        onBackCompleted = onBack
    )
}

/**
 * Title bar of the selection mode, with the actions on the selected items: those of the
 * application ([documentActions], chosen through [onDocumentAction]) when a single document is
 * selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    fileExplorerViewModel: FileExplorerViewModel,
    documentActions: List<DocumentAction>,
    onDocumentAction: (DocumentAction, FileExplorerViewModel.FileItem) -> Unit
) {
    val selectedItems = fileExplorerViewModel.selectedItems
    TopAppBar(
        title = {
            Text(
                text = stringResource(Res.string.explorer_selected_count, selectedItems.size),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = { fileExplorerViewModel.clearSelection() }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.explorer_action_clear_selection)
                )
            }
        },
        actions = {
            var showDeleteConfirmDialog by remember { mutableStateOf(false) }
            if (showDeleteConfirmDialog) {
                ConfirmActionDialog(
                    title = stringResource(Res.string.explorer_action_delete),
                    textDescription = stringResource(Res.string.explorer_dialog_delete_multiple_description, selectedItems.size),
                    textConfirmButton = stringResource(Res.string.explorer_action_confirm),
                    onDismissRequest = { showDeleteConfirmDialog = false },
                    onConfirm = {
                        fileExplorerViewModel.deleteSelected()
                        showDeleteConfirmDialog = false
                    }
                )
            }

            var showMoveDialog by remember { mutableStateOf(false) }
            if (showMoveDialog) {
                MoveFileDialog(
                    onDismissRequest = { showMoveDialog = false },
                    getSubFolders = { folderId -> fileExplorerViewModel.getSubFolders(folderId) },
                    isFolderSelected = { folderId ->
                        selectedItems.any { it.id == folderId && it.type == FileType.FOLDER }
                    },
                    isValidMove = { targetId -> fileExplorerViewModel.isValidMove(targetId) },
                    onConfirm = { targetFolderId ->
                        fileExplorerViewModel.moveSelected(targetFolderId)
                        showMoveDialog = false
                    }
                )
            }

            val document = selectedItems.singleOrNull()?.takeIf { it.type == FileType.FILE }
            if (document != null) {
                for (action in documentActions) {
                    IconButton(onClick = { onDocumentAction(action, document) }) {
                        Icon(action.icon, contentDescription = action.label())
                    }
                }
            }
            IconButton(onClick = { showMoveDialog = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.DriveFileMove,
                    contentDescription = stringResource(Res.string.explorer_action_move)
                )
            }
            IconButton(onClick = { showDeleteConfirmDialog = true }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.explorer_action_delete))
            }
            IconButton(onClick = { fileExplorerViewModel.selectAll() }) {
                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(Res.string.explorer_action_select_all))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

/** Title bar of the root ([title] and [actions]) or of a folder (its name and the back button). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderTopBar(
    fileExplorerViewModel: FileExplorerViewModel,
    title: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    AnimatedContent(targetState = fileExplorerViewModel.currentFolder) { folder ->
        if (folder == null) {
            CenterAlignedTopAppBar(title = title, actions = actions)
        } else {
            TopAppBar(
                title = {
                    Text(
                        text = folder.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { fileExplorerViewModel.backFolder() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.explorer_action_back)
                        )
                    }
                }
            )
        }
    }
}

/** The floating button whose menu creates a new note or a new folder in the current folder. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NewItemMenu(
    fileExplorerViewModel: FileExplorerViewModel,
    listState: LazyListState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val fabVisible by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    var openDialogNewFile by rememberSaveable { mutableStateOf(false) }
    if (openDialogNewFile) {
        NewItemDialog(
            type = FileType.FILE,
            title = stringResource(Res.string.explorer_dialog_new_note),
            fileExplorerViewModel = fileExplorerViewModel,
            onDismissRequest = { openDialogNewFile = false }
        )
    }
    var openDialogNewFolder by rememberSaveable { mutableStateOf(false) }
    if (openDialogNewFolder) {
        NewItemDialog(
            type = FileType.FOLDER,
            title = stringResource(Res.string.explorer_dialog_new_folder),
            fileExplorerViewModel = fileExplorerViewModel,
            onDismissRequest = { openDialogNewFolder = false }
        )
    }

    val items = listOf(
        Icons.AutoMirrored.Filled.Article to stringResource(Res.string.explorer_fab_item_note),
        Icons.Filled.Folder to stringResource(Res.string.explorer_fab_item_folder),
    )

    val stateExpandedStr = stringResource(Res.string.explorer_fab_state_expanded)
    val stateCollapsedStr = stringResource(Res.string.explorer_fab_state_collapsed)
    val toggleMenuStr = stringResource(Res.string.explorer_fab_toggle_menu)
    val closeMenuStr = stringResource(Res.string.explorer_fab_close_menu)

    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(
                modifier = Modifier
                    .semantics {
                        traversalIndex = -1f
                        stateDescription = if (expanded) stateExpandedStr else stateCollapsedStr
                        contentDescription = toggleMenuStr
                    }
                    .animateFloatingActionButton(
                        visible = fabVisible || expanded,
                        alignment = Alignment.BottomEnd,
                    ),
                checked = expanded,
                onCheckedChange = { onExpandedChange(!expanded) },
            ) {
                val imageVector by remember {
                    derivedStateOf {
                        if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add
                    }
                }
                Icon(
                    painter = rememberVectorPainter(imageVector),
                    contentDescription = null,
                    modifier = Modifier.animateIcon({ checkedProgress }),
                )
            }
        }
    ) {
        items.forEachIndexed { i, item ->
            FloatingActionButtonMenuItem(
                modifier = Modifier.semantics {
                    isTraversalGroup = true
                    // Add a custom a11y action to allow closing the menu when focusing
                    // the last menu item, since the close button comes before the first
                    // menu item in the traversal order.
                    if (i == items.size - 1) {
                        customActions = listOf(
                            CustomAccessibilityAction(
                                label = closeMenuStr,
                                action = {
                                    onExpandedChange(false)
                                    true
                                },
                            )
                        )
                    }
                },
                onClick = {
                    onExpandedChange(false)
                    when (i) {
                        0 -> openDialogNewFile = true
                        1 -> openDialogNewFolder = true
                    }
                },
                icon = { Icon(item.first, contentDescription = null) },
                text = { Text(text = item.second) }
            )
        }
    }
}

/** Asks the name of a new note or folder and creates it in the current folder. */
@Composable
private fun NewItemDialog(
    type: FileType,
    title: String,
    fileExplorerViewModel: FileExplorerViewModel,
    onDismissRequest: () -> Unit
) {
    var creationError by remember { mutableStateOf<StringResource?>(null) }
    RequestNameDialog(
        title = title,
        labelTextField = stringResource(Res.string.explorer_input_name),
        textConfirmButton = stringResource(Res.string.explorer_action_confirm),
        onDismissRequest = onDismissRequest,
        isAllowedInput = { text ->
            creationError = null
            fileExplorerViewModel.validateFileName(text)
        },
        error = creationError,
        onConfirm = { text ->
            fileExplorerViewModel.createFile(
                type, text,
                onSuccess = onDismissRequest,
                onError = { creationError = it }
            )
        }
    )
}
