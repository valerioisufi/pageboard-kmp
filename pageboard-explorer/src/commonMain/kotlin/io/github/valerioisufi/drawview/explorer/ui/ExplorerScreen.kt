package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.ui.window.LocalWindowDecorInsets
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.valerioisufi.drawview.explorer.DocumentOpener
import io.github.valerioisufi.drawview.explorer.DocumentThumbnails
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.explorer.LibrarySearch
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_confirm
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_move_to
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_action_rename
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_delete_confirm
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_delete_shared
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_cannot_undo
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_delete_forever_many
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_delete_forever_title
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_empty_description
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_empty_title
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_moved_many
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_moved_one
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_trash_undo
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_dialog_new_folder
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_input_name
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_items_count
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_move_subtitle
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_root_folder
import io.github.valerioisufi.drawview.ui.command.RegisterCommands
import io.github.valerioisufi.drawview.ui.components.AboutLicensesDialog
import io.github.valerioisufi.drawview.ui.components.DesignIconButton
import io.github.valerioisufi.drawview.ui.components.DesignSnackbar
import io.github.valerioisufi.drawview.ui.components.IconButtonStyle
import io.github.valerioisufi.drawview.ui.extension.DocumentTag
import io.github.valerioisufi.drawview.ui.extension.LibraryItemsAction
import io.github.valerioisufi.drawview.ui.extension.LibrarySelection
import io.github.valerioisufi.drawview.ui.extension.LibraryAction
import io.github.valerioisufi.drawview.ui.generated.resources.Res as DrawViewRes
import io.github.valerioisufi.drawview.ui.generated.resources.about_dialog_title
import io.github.valerioisufi.drawview.ui.platform.PickedFile
import io.github.valerioisufi.drawview.ui.platform.fileDropTarget
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The document library: the sections and the folders, the search and the filters, the notes as
 * cards or rows, the details of the selection, the button making notes and folders; laid out by
 * the width it has (see [LibraryLayout]) as in the design (docs/plans/ui-design.md).
 *
 * @param title the brand of the application (its mark and its name), at the top of the library.
 * @param actions actions of the application in the library (e.g. joining a shared document, its
 *   information): its bar, or its navigation pane.
 * @param itemsActions actions of the application on the items of the library and on the folder
 *   shown (e.g. their export).
 * @param tags what the application says of the documents (e.g. that they have a lecture): their
 *   badges and the filters of the library.
 * @param thumbnails draws the first pages of the documents; without them the library is a list.
 * @param onFilesDropped takes the files dropped from the system onto the library (e.g. makes a
 *   note of a PDF); whether it took them.
 * @param onImportPdf imports a PDF chosen by the user as a note, if the application can.
 * @param onImportImage imports an image chosen by the user as a note, if the application can.
 */
@Composable
internal fun ExplorerScreen(
    fileExplorerViewModel: FileExplorerViewModel,
    onOpenDocument: (String) -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    actions: List<LibraryAction> = listOf(aboutLibraryAction()),
    itemsActions: List<LibraryItemsAction> = emptyList(),
    tags: List<DocumentTag> = emptyList(),
    thumbnails: DocumentThumbnails? = null,
    search: LibrarySearch? = null,
    onFilesDropped: ((List<PickedFile>) -> Boolean)? = null,
    onImportPdf: (() -> Unit)? = null,
    onImportImage: (() -> Unit)? = null,
    onOpenDocumentInNewWindow: ((String) -> Unit)? = null,
    onOpenDocumentWithPlace: DocumentOpener = DocumentOpener { id, _ -> onOpenDocument(id) },
    gridState: LazyGridState = rememberLibraryGridState(fileExplorerViewModel)
) {
    val viewModel = fileExplorerViewModel
    LaunchedEffect(viewModel, tags) { viewModel.tags = tags }
    LaunchedEffect(viewModel, search) {
        viewModel.contentSearch = search
        search?.onQueryChange(viewModel.searchQuery)
        snapshotFlow { viewModel.searchDocumentIds }.collect { search?.onScopeChange(it) }
    }
    val searchFocus = remember { FocusRequester() }
    // Going somewhere takes the focus from the search (and the keyboard away), keeping the shortcuts
    val contentFocus = remember { FocusRequester() }
    val shownPlace = viewModel.section to viewModel.currentFolder?.id
    var focusedPlace by remember { mutableStateOf(shownPlace) }
    LaunchedEffect(shownPlace) {
        if (shownPlace != focusedPlace) {
            focusedPlace = shownPlace
            runCatching { contentFocus.requestFocus() }
        }
    }
    // The items dragged with a mouse onto a folder
    val drag = remember { LibraryDrag() }
    // The action of the application chosen on items or on the folder shown, shown until it is
    // done (the selection is cleared meanwhile)
    var chosenAction by remember { mutableStateOf<Pair<LibraryItemsAction, LibrarySelection>?>(null) }
    chosenAction?.let { (action, selection) -> action.content(selection) { chosenAction = null } }
    // The action of the application chosen in the library, shown until it is done
    var libraryAction by remember { mutableStateOf<LibraryAction?>(null) }
    libraryAction?.let { action -> action.content { libraryAction = null } }
    // The dialog opened by a command (new item, rename, move, delete, about)
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    dialog?.let { LibraryDialogHost(it, viewModel, onOpenDocument, onImportPdf, onImportImage, onDismiss = { dialog = null }) }
    // The commands of the library: its bars and menus, the menu bar of the window, the shortcuts
    val commands = rememberLibraryCommands(
        viewModel = viewModel,
        itemsActions = itemsActions,
        onOpenDocument = onOpenDocument,
        onShowDialog = { dialog = it },
        onChooseAction = { action, selection ->
            chosenAction = action to selection
            viewModel.clearSelection()
        },
        onFocusSearch = { runCatching { searchFocus.requestFocus() } },
        onOpenDocumentInNewWindow = onOpenDocumentInNewWindow,
        thumbnail = { item -> thumbnails?.let { thumbnails -> { thumbnails.of(item.id, item.modifiedAt, ExportThumbnailWidth) } } }
    )
    RegisterCommands(commands.all)
    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // The menu is hidden together with its button while items are selected
    LaunchedEffect(viewModel.selectionMode) {
        if (viewModel.selectionMode) fabMenuExpanded = false
    }

    // What was moved to the trash last, told with Undo
    val snackbars = remember { SnackbarHostState() }
    val notice = viewModel.trashNotice
    val noticeText = notice?.let {
        if (it.names.size == 1) stringResource(Res.string.explorer_trash_moved_one, it.names.single())
        else pluralStringResource(Res.plurals.explorer_trash_moved_many, it.names.size, it.names.size)
    }
    val undoLabel = stringResource(Res.string.explorer_trash_undo)
    LaunchedEffect(notice) {
        if (notice == null || noticeText == null) return@LaunchedEffect
        val result = snackbars.showSnackbar(noticeText, actionLabel = undoLabel, withDismissAction = true, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoTrash(notice)
        viewModel.trashNoticeShown(notice)
    }

    val backEnabled = fabMenuExpanded || viewModel.canNavigateBack
    val onBack = {
        if (fabMenuExpanded) fabMenuExpanded = false else viewModel.navigateBack()
    }
    ExplorerBackHandler(enabled = backEnabled, onBack = onBack)

    CompositionLocalProvider(LocalLibraryDrag provides drag, LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        BoxWithConstraints(
            modifier = modifier
                .background(MaterialTheme.colorScheme.background)
                .then(if (onFilesDropped != null) Modifier.fileDropTarget(onFilesDropped) else Modifier)
                // The Back key of Android moves the focus out one level at a time before it is a
                // back: with the focus in the search it would take three presses to leave it
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Back || !backEnabled) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) {
                        runCatching { contentFocus.requestFocus() }
                        onBack()
                    }
                    true
                }
                .focusRequester(contentFocus)
                .focusable()
        ) {
            // A list on a phone, a grid of the first pages wider, beside the navigation and the
            // details of the selection on the widest (or without thumbnails, a list)
            val layout = if (thumbnails == null) LibraryLayout.LIST else LibraryLayout.of(maxWidth)
            val compact = layout == LibraryLayout.LIST
            val itemActions = ItemActions(
                viewModel = viewModel,
                onOpenDocument = onOpenDocument,
                onOpenDocumentWithPlace = onOpenDocumentWithPlace,
                thumbnails = thumbnails,
                commands = commands,
                onShowDetails = if (layout == LibraryLayout.PANES) { item -> viewModel.showDetails(item) } else null,
                onOpenDocumentInNewWindow = onOpenDocumentInNewWindow
            )
            val newNote = { commands.newNote.perform() }
            Row(modifier = Modifier.fillMaxSize()) {
                if (layout == LibraryLayout.PANES) {
                    LibraryNavigationPane(viewModel, commands, title, actions, onAction = { libraryAction = it })
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + if (layout == LibraryLayout.PANES) WindowInsetsSides.End else WindowInsetsSides.Horizontal))
                ) {
                    val decorStartPad = LocalWindowDecorInsets.current.calculateStartPadding(LocalLayoutDirection.current)
                    when (layout) {
                        LibraryLayout.LIST -> if (viewModel.selectionMode) {
                            SelectionBar(viewModel, commands, withActions = true, modifier = Modifier.padding(start = 4.dp + decorStartPad, end = 4.dp))
                        } else {
                            LibraryAppBar(
                                title,
                                actions,
                                onAction = { libraryAction = it },
                                modifier = Modifier.padding(start = 16.dp + decorStartPad, end = 8.dp),
                                onOpenTrash = { viewModel.showSection(FileExplorerViewModel.Section.TRASH) }
                            )
                        }
                        LibraryLayout.GRID -> LibraryAppBar(
                            brand = title,
                            actions = actions,
                            onAction = { libraryAction = it },
                            modifier = Modifier.padding(start = 28.dp + decorStartPad, end = 20.dp, top = 8.dp),
                            onOpenTrash = { viewModel.showSection(FileExplorerViewModel.Section.TRASH) },
                            search = { searchModifier -> LibrarySearchField(viewModel, commands, searchFocus, modifier = searchModifier) }
                        )
                        LibraryLayout.PANES -> Unit
                    }
                    // The search stays in sight and within reach of its shortcut, wherever the list is
                    when (layout) {
                        LibraryLayout.LIST -> LibrarySearchField(
                            viewModel, commands, searchFocus,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        LibraryLayout.GRID -> Unit
                        LibraryLayout.PANES -> PanesHeader(
                            viewModel, commands, searchFocus,
                            modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 12.dp)
                        )
                    }
                    LibraryContent(
                        actions = itemActions,
                        compact = compact,
                        gridState = gridState,
                        onNewNote = newNote,
                        onImportPdf = onImportPdf,
                        modifier = Modifier.weight(1f)
                    ) {
                        libraryHeader(layout, viewModel, commands)
                    }
                    // The pages of a document of the trash, read only
                    val previewed = viewModel.previewed
                    if (previewed != null && thumbnails != null) {
                        TrashPreview(
                            item = previewed,
                            thumbnails = thumbnails,
                            onRestore = {
                                viewModel.restore(previewed)
                                viewModel.previewed = null
                            },
                            onDismiss = { viewModel.previewed = null }
                        )
                    }
                }
                if (layout == LibraryLayout.PANES && viewModel.targetItems.isNotEmpty()) {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LibraryDetailsPane(itemActions, commands, onClose = { viewModel.clearSelection() })
                }
            }
            NewItemScrim(fabMenuExpanded, onDismiss = { fabMenuExpanded = false })
            // Nothing is made in the trash
            if ((!viewModel.selectionMode || layout == LibraryLayout.PANES) && viewModel.section != FileExplorerViewModel.Section.TRASH) {
                NewItemButton(
                    commands = commands,
                    extended = !compact,
                    withMenu = layout != LibraryLayout.PANES,
                    expanded = fabMenuExpanded,
                    onExpandedChange = { fabMenuExpanded = it },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                        .padding(
                            end = if (layout == LibraryLayout.PANES && viewModel.targetItems.isNotEmpty()) LibraryLayout.DetailsWidth + 28.dp else if (compact) 16.dp else 24.dp,
                            bottom = if (compact) 16.dp else 24.dp
                        )
                )
            }
            SnackbarHost(
                hostState = snackbars,
                snackbar = { data -> DesignSnackbar(data) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(start = 16.dp, end = 16.dp, bottom = if (compact) 88.dp else 24.dp)
                    .widthIn(max = 520.dp)
            )
            LibraryDragShadow(drag, viewModel)
        }
    }
}

/**
 * The list of the library, with its position: kept while a note is open in the editor, back at the
 * top when what it shows changes. The position is requested, not forced with a measure of its own,
 * so that it does not run into the thumbnails being drawn (docs/plans/library-search.md).
 */
@Composable
internal fun rememberLibraryGridState(viewModel: FileExplorerViewModel): LazyGridState {
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, viewModel.searchQuery.trim(), viewModel.searchScope, viewModel.section, viewModel.currentFolder?.id, viewModel.filter) {
        gridState.requestScrollToItem(0)
    }
    return gridState
}

/** The fixed header of a wide library: the title or the selection, the search, the views. */
@Composable
private fun PanesHeader(viewModel: FileExplorerViewModel, commands: LibraryCommands, searchFocus: FocusRequester, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.weight(1f)) {
            if (viewModel.selectionMode) {
                SelectionBar(viewModel, commands, withActions = false)
            } else {
                LibraryTitle(viewModel, commands, compact = false)
            }
        }
        LibrarySearchField(viewModel, commands, searchFocus, modifier = Modifier.width(280.dp))
        LibraryViewControls(viewModel, commands, showViews = true)
    }
}

/**
 * The header of the content, scrolled with it: the title (or the selection) where it is not fixed
 * above the list, the views where there is room; where the search looks, the filters.
 */
private fun LazyGridScope.libraryHeader(
    layout: LibraryLayout,
    viewModel: FileExplorerViewModel,
    commands: LibraryCommands
) {
    if (layout != LibraryLayout.PANES) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column(
                modifier = Modifier.padding(
                    start = if (layout == LibraryLayout.LIST) 16.dp else 0.dp,
                    end = if (layout == LibraryLayout.LIST) 16.dp else 0.dp,
                    top = if (layout == LibraryLayout.LIST) 4.dp else 20.dp,
                    bottom = 6.dp
                )
            ) {
                if (layout == LibraryLayout.LIST) {
                    LibraryTitle(viewModel, commands, compact = true) {
                        FolderActionsButton(viewModel, commands)
                    }
                } else if (viewModel.selectionMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionBar(viewModel, commands, withActions = true, modifier = Modifier.weight(1f))
                    }
                } else {
                    LibraryTitle(viewModel, commands, compact = false) {
                        LibraryViewControls(viewModel, commands, showViews = true)
                    }
                }
            }
        }
    }
    if (viewModel.section == FileExplorerViewModel.Section.TRASH) return
    if (viewModel.canWidenSearch) {
        item(key = "search-scope", span = { GridItemSpan(maxLineSpan) }) {
            LibrarySearchScopeChips(
                viewModel,
                contentPadding = if (layout == LibraryLayout.LIST) PaddingValues(horizontal = 16.dp) else PaddingValues()
            )
        }
    }
    item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
        LibraryFilterChips(
            viewModel,
            contentPadding = if (layout == LibraryLayout.LIST) PaddingValues(horizontal = 16.dp) else PaddingValues(),
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

/** The About & Licenses dialog of the application (see `LocalAboutAppInfo`), as an action of the library. */
@Composable
public fun aboutLibraryAction(): LibraryAction = remember {
    LibraryAction(
        icon = DrawViewIcons.Info,
        label = { stringResource(DrawViewRes.string.about_dialog_title) }
    ) { onDone -> AboutLicensesDialog(onDismiss = onDone) }
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

/** Asks the name of a new folder and creates it in the current folder. */
@Composable
private fun NewFolderDialog(fileExplorerViewModel: FileExplorerViewModel, onDismissRequest: () -> Unit) {
    var creationError by remember { mutableStateOf<StringResource?>(null) }
    RequestNameDialog(
        title = stringResource(Res.string.explorer_dialog_new_folder),
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
                FileType.FOLDER, text,
                onSuccess = onDismissRequest,
                onError = { creationError = it }
            )
        }
    )
}

/** Shows [dialog], opened by a command of the library, until it is done or dismissed. */
@Composable
private fun LibraryDialogHost(
    dialog: LibraryDialog,
    viewModel: FileExplorerViewModel,
    onOpenDocument: (String) -> Unit,
    onImportPdf: (() -> Unit)?,
    onImportImage: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    when (dialog) {
        is LibraryDialog.NewItem -> if (dialog.type == FileType.FILE) {
            NewNoteDialog(viewModel, onOpenDocument, onImportPdf, onImportImage, onDismiss)
        } else {
            NewFolderDialog(viewModel, onDismissRequest = onDismiss)
        }
        is LibraryDialog.Rename -> RequestNameDialog(
            title = stringResource(Res.string.explorer_action_rename),
            labelTextField = stringResource(Res.string.explorer_input_name),
            textConfirmButton = stringResource(Res.string.explorer_action_confirm),
            initialText = dialog.item.name,
            onDismissRequest = onDismiss,
            isAllowedInput = { text -> viewModel.validateFileName(text, renamedFile = dialog.item) },
            onConfirm = { text ->
                viewModel.renameFile(dialog.item, text)
                onDismiss()
            },
            icon = DrawViewIcons.Pencil
        )
        is LibraryDialog.Move -> {
            val from = viewModel.folderName(dialog.items.firstOrNull()?.folderId) ?: stringResource(Res.string.explorer_root_folder)
            MoveFileDialog(
                onDismissRequest = onDismiss,
                getSubFolders = { folderId -> viewModel.getSubFolders(folderId) },
                isFolderSelected = { folderId -> dialog.items.any { it.id == folderId && it.type == FileType.FOLDER } },
                isValidMove = { targetId -> viewModel.isValidMove(targetId, dialog.items) },
                onConfirm = { targetFolderId ->
                    viewModel.moveFiles(dialog.items, targetFolderId)
                    onDismiss()
                },
                title = stringResource(Res.string.explorer_action_move_to),
                subtitle = stringResource(Res.string.explorer_move_subtitle, pluralStringResource(Res.plurals.explorer_items_count, dialog.items.size, dialog.items.size), from)
            )
        }
        is LibraryDialog.DeleteForGood -> {
            val items = dialog.items
            val shared = items.firstOrNull { it.isShared }
            ConfirmActionDialog(
                title = items.singleOrNull()?.let { stringResource(Res.string.explorer_trash_delete_forever_title, it.name) }
                    ?: pluralStringResource(Res.plurals.explorer_trash_delete_forever_many, items.size, items.size),
                textDescription = stringResource(Res.string.explorer_trash_cannot_undo),
                textConfirmButton = stringResource(Res.string.explorer_delete_confirm),
                onDismissRequest = onDismiss,
                onConfirm = {
                    viewModel.deleteForGood(items)
                    onDismiss()
                },
                note = shared?.let { stringResource(Res.string.explorer_delete_shared, it.name) }
            )
        }
        LibraryDialog.EmptyTrash -> {
            val count = viewModel.trash.size
            ConfirmActionDialog(
                title = stringResource(Res.string.explorer_trash_empty_title),
                textDescription = pluralStringResource(Res.plurals.explorer_trash_empty_description, count, count) + " " +
                    stringResource(Res.string.explorer_trash_cannot_undo),
                textConfirmButton = stringResource(Res.string.explorer_delete_confirm),
                onDismissRequest = onDismiss,
                onConfirm = {
                    viewModel.emptyTrash()
                    onDismiss()
                }
            )
        }
        LibraryDialog.About -> AboutLicensesDialog(onDismiss = onDismiss)
    }
}

/** How wide the image of the first page of a note is drawn for the sheet of an action, in pixels. */
private const val ExportThumbnailWidth = 160
