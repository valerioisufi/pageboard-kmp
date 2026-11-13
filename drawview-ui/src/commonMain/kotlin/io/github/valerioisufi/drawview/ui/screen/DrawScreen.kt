package io.github.valerioisufi.drawview.ui.screen

import io.github.valerioisufi.drawview.ui.components.ToolSizes
import io.github.valerioisufi.drawview.ui.editor.TEXT_TO_NOTES_COMMAND_ID
import io.github.valerioisufi.drawview.ui.editor.TextImportSheet
import io.github.valerioisufi.drawview.model.text.TextCleanup
import io.github.valerioisufi.drawview.canvas.controller.TextImport
import io.github.valerioisufi.drawview.ui.platform.LocalHiddenSystemBars
import io.github.valerioisufi.drawview.ui.platform.PickedFile
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.zIndex
import io.github.valerioisufi.drawview.canvas.DrawCanvas
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.observe
import io.github.valerioisufi.drawview.canvas.observeChanges
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.geometry.Dimension
import io.github.valerioisufi.drawview.model.geometry.mm
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.RegisterCommands
import io.github.valerioisufi.drawview.ui.components.AboutLicensesDialog
import io.github.valerioisufi.drawview.ui.components.DesignSnackbar
import io.github.valerioisufi.drawview.ui.components.PageGridOverlay
import io.github.valerioisufi.drawview.ui.components.PageTemplateConfigurator
import io.github.valerioisufi.drawview.ui.components.TonalButton
import io.github.valerioisufi.drawview.ui.editor.CanvasContextMenu
import io.github.valerioisufi.drawview.ui.editor.SelectionActionBar
import io.github.valerioisufi.drawview.ui.editor.TextEditorOverlay
import io.github.valerioisufi.drawview.ui.editor.PlacementBar
import io.github.valerioisufi.drawview.ui.editor.TextMarkupActionBar
import io.github.valerioisufi.drawview.ui.extension.EditorPanel
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.generated.resources.common_action_undo
import io.github.valerioisufi.drawview.ui.generated.resources.message_pages_deleted
import io.github.valerioisufi.drawview.ui.platform.editorInsets
import io.github.valerioisufi.drawview.ui.platform.immersiveSystemBars
import io.github.valerioisufi.drawview.ui.platform.PencilInteractionEffect
import io.github.valerioisufi.drawview.ui.platform.PickerType
import io.github.valerioisufi.drawview.ui.platform.fileDropTarget
import io.github.valerioisufi.drawview.ui.platform.rememberFilePicker
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.render.LocalDrawViewEngines
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.LocalCanvasColors
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import io.github.valerioisufi.drawview.ui.window.LocalWindowDecorInsets
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Main drawing interface: top navigation bar, tool ribbon, drawing canvas with its overlays
 * (selection action bar, context menu, text editor), page grid and template dialogs.
 *
 * @param controller editing session of the document.
 * @param onNavigateBack invoked by the back button of the toolbar.
 * @param isLoading shows a progress bar while the document is loaded.
 * @param toolbarActions additional actions of the top bar, before the drawing mode toggle.
 * @param panels panels contributed to the editor (e.g. an assistant), placed beside the canvas or
 *   over it by [EditorScaffold].
 * @param canvasOverlay content drawn over the canvas (e.g. hints of an assistant).
 * @param extraCommands commands contributed to the editor (e.g. by its extensions), registered
 *   with those of the editor for the menu bar and the shortcuts.
 * @param pageGridActions actions contributed on the pages selected in the page grid (e.g. their
 *   export), in the bar of its selection.
 * @param location where the document is (its folder), shown under its title.
 * @param readOnlyNote why the document may only be read (e.g. who decides who edits), if known.
 * @param renamer renames the document from its title and the command "Rename…" (none: the title
 *   is not changed in the editor).
 *
 * The engines of the controller ([DrawViewController.engines]) are the [LocalDrawViewEngines] of
 * what the editor composes (its panels and overlays included).
 */
@Composable
internal fun DrawScreen(
    controller: DrawViewController,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    toolbarActions: @Composable RowScope.() -> Unit = {},
    panels: List<EditorPanel> = emptyList(),
    canvasOverlay: @Composable BoxScope.() -> Unit = {},
    extraCommands: List<Command> = emptyList(),
    pageGridActions: List<Command> = emptyList(),
    location: String? = null,
    readOnlyNote: @Composable () -> String? = { null },
    renamer: DocumentRenamer? = null
) {
    // The room of the system bars given to the editor, where it gains some, and kept by the
    // windows opened over it (menus, popovers, dialogs)
    val hiddenBars = immersiveSystemBars()
    CompositionLocalProvider(LocalDrawViewEngines provides controller.engines, LocalHiddenSystemBars provides hiddenBars) {
        DrawScreenContent(controller, onNavigateBack, modifier, isLoading, toolbarActions, panels, canvasOverlay, extraCommands, pageGridActions, location, readOnlyNote, renamer)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawScreenContent(
    controller: DrawViewController,
    onNavigateBack: () -> Unit,
    modifier: Modifier,
    isLoading: Boolean,
    toolbarActions: @Composable RowScope.() -> Unit,
    panels: List<EditorPanel>,
    canvasOverlay: @Composable BoxScope.() -> Unit,
    extraCommands: List<Command>,
    pageGridActions: List<Command>,
    location: String?,
    readOnlyNote: @Composable () -> String?,
    renamer: DocumentRenamer?
) {
    // Whether some pages hold PDF pages, as it changes (not at every change of the document)
    val hasPdfPages by controller.document.observe { hasPdfPages() }

    // Theme colors of the canvas (desk, paper, selection), updated with the Material theme
    val colorScheme = MaterialTheme.colorScheme
    val canvasColors = LocalCanvasColors.current
    LaunchedEffect(colorScheme, canvasColors) {
        controller.themeColors = io.github.valerioisufi.drawview.canvas.DrawThemeColors(
            backgroundColor = canvasColors.desk.toArgb(),
            surfaceColor = colorScheme.surface.toArgb(),
            primaryColor = colorScheme.primary.toArgb(),
            onSurfaceColor = colorScheme.onSurface.toArgb()
        )
        controller.requestRedraw()
    }

    // Text blocks rendering (Markdown + LaTeX) of the tiles, thumbnails and selection
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val notesFonts = rememberNotesFonts()
    DisposableEffect(controller, fontFamilyResolver, notesFonts) {
        if (controller.textAdapter == null) {
            controller.textAdapter = ComposeTextTileAdapter(fontFamilyResolver, notesFonts, controller.engines.formulas, controller.engines.code)
        }
        onDispose { }
    }

    // One-shot messages of the editor
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(controller) {
        controller.messages.collect { message ->
            val text = when (message) {
                DrawViewController.Message.STYLUS_MODE_ACTIVATED -> getString(Res.string.stylus_mode_activated)
                DrawViewController.Message.IMPORT_IMAGE_FAILED -> getString(Res.string.message_import_image_failed)
                DrawViewController.Message.IMPORT_PDF_FAILED -> getString(Res.string.message_import_pdf_failed)
                DrawViewController.Message.PDF_NOT_SUPPORTED -> getString(Res.string.message_pdf_not_supported)
                DrawViewController.Message.PDF_PAGE_WITHOUT_TEXT -> getString(Res.string.message_pdf_page_without_text)
                DrawViewController.Message.PASTED_WITHOUT_MISSING_FILES -> getString(Res.string.message_pasted_without_missing_files)
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    // The pages deleted, told with Undo: they come back from the trash of the document
    LaunchedEffect(controller) {
        controller.pageManager.deleted.collect { ids ->
            val text = getPluralString(Res.plurals.message_pages_deleted, ids.size, ids.size)
            val result = snackbarHostState.showSnackbar(text, actionLabel = getString(Res.string.common_action_undo), duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) controller.pageManager.restorePages(ids)
        }
    }

    val pdfPicker = rememberFilePicker(PickerType.PDF) { file ->
        controller.mediaManager.importPdf(file.bytes)
    }
    val imagePicker = rememberFilePicker(PickerType.IMAGE) { file ->
        controller.mediaManager.importImage(file.bytes, file.extension.ifBlank { "png" })
    }
    // A text file: the student says where it goes (its sheet)
    val importTextFile = { file: PickedFile ->
        val text = file.text()
        controller.pendingTextImport = TextImport(
            text, controller.viewport.mostVisiblePageIndex(),
            isMarkdown = file.extension == "md" || file.extension == "markdown" || TextCleanup.looksLikeMarkdown(text),
            name = file.name.substringBeforeLast('.')
        )
    }
    val textPicker = rememberFilePicker(PickerType.TEXT) { importTextFile(it) }
    // Template dialogs
    var showSinglePageConfigurator by remember { mutableStateOf(false) }
    // The pages selected in the grid the configurator gives a format to (none: the page of the menu)
    var configuredPageIds by remember { mutableStateOf<List<String>?>(null) }
    var showDocumentConfigurator by remember { mutableStateOf(false) }
    var pendingDocumentTemplate by remember { mutableStateOf<Pair<Dimension, PageBackground>?>(null) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // The commands of the editor: the bars below, the menu bar of the window, the shortcuts
    val commands = rememberEditorCommands(
        controller = controller,
        onClose = onNavigateBack,
        onImportPdf = pdfPicker,
        onImportImage = imagePicker,
        onImportText = textPicker,
        onEditDocumentBackground = { showDocumentConfigurator = true },
        onShowAbout = { showAboutDialog = true },
        canRename = renamer != null
    )
    // "Rename…" only where the document can be renamed
    val renamable = renamer != null
    RegisterCommands(remember(commands, extraCommands, renamable) { commands.all.filter { renamable || it !== commands.rename } + extraCommands })
    // The double tap and the squeeze of an Apple Pencil, as the user set them in the system
    PencilInteractionEffect(onAction = commands::perform)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // =========================================================
        // BARS, CANVAS AND OVERLAYS (+ PANELS, beside at full height)
        // =========================================================
        EditorScaffold(
            panels = panels,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) { bottomInset ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Where the tools go, by the width of the editor and the buttons of its input
                val adaptive = currentAdaptiveInfo()
                val window = adaptive.window
                val button = LocalMinimumInteractiveComponentSize.current.takeIf { it.isSpecified } ?: 48.dp
                // The tools in the bars sized for a pen where there is touch (see ToolGroup)
                val toolButton = if (adaptive.input.hasTouch) ToolSizes.PenTarget else button
                var extensionsWidth by remember { mutableStateOf(0.dp) }
                val pdfTools = if (controller.tileManager.tileRenderer.pdfAdapter != null) 1 else 0
                val textMarkupShown = pdfTools == 1 && hasPdfPages
                val toolCount = 2 + 7 + (if (textMarkupShown) 1 else 0) + 1 + pdfTools
                // The top bar starts after the controls of the window (the traffic lights of macOS)
                val decorWidth = LocalWindowDecorInsets.current.calculateStartPadding(LocalLayoutDirection.current)
                // The actions of the extensions behind one button where they leave the title too
                // little room (a phone: back and "more" beside them; else the page grid too)
                val floating = ToolbarLayout.arrange(maxWidth, window.heightClass, button, 0.dp, toolCount, groups = 3, decorWidth) == ToolbarArrangement.FLOATING_TOOLS
                val extensionsInBar = extensionsWidth == 0.dp ||
                    ToolbarLayout.extensionsFit(maxWidth, button, extensionsWidth, buttons = if (floating) 2 else 3, decorWidth)
                val barExtensionsWidth = if (extensionsInBar) extensionsWidth else button
                val arrangement = ToolbarLayout.arrange(maxWidth, window.heightClass, button, barExtensionsWidth, toolCount, groups = 3, decorWidth, toolButton)
                val showPageGrid = arrangement != ToolbarArrangement.FLOATING_TOOLS || ToolbarLayout.pageGridFits(maxWidth, button, barExtensionsWidth, decorWidth)
                val toolsMaxWidth = (maxWidth - ToolbarLayout.topWidth(button, barExtensionsWidth, decorWidth = decorWidth)).coerceAtLeast(0.dp)
                val titleMaxWidth = ToolbarLayout.titleMaxWidth(maxWidth, button, barExtensionsWidth, toolCount, groups = 3, decorWidth, toolButton)

                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .zIndex(1f)
                            .background(MaterialTheme.colorScheme.surface)
                            .windowInsetsPadding(editorInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                    ) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            EditorTopBar(
                                controller = controller,
                                commands = commands,
                                isLoading = isLoading,
                                arrangement = arrangement,
                                showPageGrid = showPageGrid,
                                toolsMaxWidth = toolsMaxWidth,
                                titleMaxWidth = titleMaxWidth,
                                toolbarActions = toolbarActions,
                                onExtensionsWidth = { extensionsWidth = it },
                                extensionsInBar = extensionsInBar,
                                location = location,
                                readOnlyNote = readOnlyNote,
                                renamer = renamer
                            )

                            // The group of the tools in a bar under the top one
                            AnimatedVisibility(visible = arrangement == ToolbarArrangement.TWO_BARS && controller.isDrawingMode) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    ToolGroup {
                                        EditorTools(controller, commands, withImports = true)
                                    }
                                }
                            }
                        }
                    }

                    // A PDF or an image dragged from the files of the computer is imported
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .fileDropTarget { files ->
                                !controller.isReadOnly && files.map { file ->
                                    if (file.extension in PickedFile.TEXT_EXTENSIONS) {
                                        importTextFile(file)
                                        true
                                    } else {
                                        controller.mediaManager.importFile(file.bytes, file.extension)
                                    }
                                }.any { it }
                            }
                    ) {
                        if (isLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.secondary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        } else {
                            DrawCanvas(
                                controller = controller,
                                modifier = Modifier.fillMaxSize()
                            )

                            SelectionActionBar(
                                controller = controller,
                                commands = commands,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )

                            TextMarkupActionBar(
                                controller = controller,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )

                            PlacementBar(
                                controller = controller,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )

                            CanvasContextMenu(
                                controller = controller,
                                commands = commands,
                                onEditPageBackground = { showSinglePageConfigurator = true }
                            )

                            TextEditorOverlay(controller = controller, commands = commands)

                            canvasOverlay()
                        }

                        // The tools floating at the bottom, above the panels rising from there
                        // (while a text is written its formatting bar takes their place)
                        val floating = arrangement == ToolbarArrangement.FLOATING_TOOLS && controller.isDrawingMode && !isLoading &&
                            !controller.textEditorManager.isEditing
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = bottomInset)
                                .windowInsetsPadding(editorInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                                .padding(start = 8.dp, end = 8.dp, bottom = if (floating) 8.dp else 18.dp)
                        ) {
                            if (!isLoading && bottomInset == 0.dp && !controller.textEditorManager.isEditing) {
                                PageIndicator(controller, withZoom = arrangement != ToolbarArrangement.FLOATING_TOOLS)
                            }
                            // Where the tools would be, why there are none
                            if (arrangement == ToolbarArrangement.FLOATING_TOOLS && controller.isReadOnly && !isLoading) {
                                ReadOnlyBadge(readOnlyNote(), floating = true)
                            }
                            // Not the one of the column of the screen, around
                            androidx.compose.animation.AnimatedVisibility(
                                visible = floating,
                                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                                exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
                            ) {
                                FloatingTools(controller, commands)
                            }
                        }

                        SnackbarHost(
                            hostState = snackbarHostState,
                            snackbar = { data -> DesignSnackbar(data) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = bottomInset + if (floating) ToolbarLayout.FloatingHeight + 56.dp else 48.dp)
                                .padding(16.dp)
                        )
                    }
                }
            }
        }

        // =========================================================
        // PAGE GRID
        // =========================================================
        AnimatedVisibility(
            visible = controller.isPageGridVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PageGridOverlay(
                    controller = controller,
                    commands = commands,
                    extensionActions = pageGridActions,
                    onEditPageBackground = { configuredPageIds = null; showSinglePageConfigurator = true },
                    onEditPagesBackground = { ids -> configuredPageIds = ids; showSinglePageConfigurator = true }
                )
                // The messages over the grid too (the pages deleted there, with Undo)
                SnackbarHost(
                    hostState = snackbarHostState,
                    snackbar = { data -> DesignSnackbar(data) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(editorInsets())
                        .padding(16.dp)
                )
            }
        }
    }

    // =========================================================
    // BACKGROUND TEMPLATE DIALOGS
    // =========================================================

    // 1. Pages: the page targeted by the context menu or the page grid, or the pages selected in
    // the grid (from the format of the first one)
    TextImportSheet(controller, asNotes = extraCommands.firstOrNull { it.id == TEXT_TO_NOTES_COMMAND_ID })

    if (showSinglePageConfigurator) {
        val pageIds = configuredPageIds
        val targetIndex = controller.pageManager.contextMenuTargetPageIndex
        val close = { showSinglePageConfigurator = false; configuredPageIds = null }

        AdaptiveSheet(onDismissRequest = close, maxWidth = 760.dp) {
            // The pages as they change, while the sheet is open
            controller.document.observeChanges()
            val targetPage = pageIds?.firstOrNull()?.let { controller.document.pageById(it) } ?: controller.document.pages.getOrNull(targetIndex)
            PageTemplateConfigurator(
                subtitle = if (pageIds != null) {
                    pluralStringResource(Res.plurals.page_config_subtitle_pages, pageIds.size, pageIds.size, controller.document.name)
                } else {
                    stringResource(Res.string.page_config_subtitle_page, targetIndex + 1, controller.document.name)
                },
                initialDimension = targetPage?.dimension ?: Dimension.A4(),
                initialBackground = targetPage?.background ?: PageBackground.Solid(),
                onApply = { newDimension, newBackground ->
                    if (pageIds != null) {
                        controller.pageManager.changePagesTemplate(pageIds, newDimension, newBackground)
                    } else {
                        controller.pageManager.changeSinglePageTemplate(targetIndex, newDimension, newBackground)
                    }
                    close()
                },
                onCancel = close
            )
        }
    }

    // 2. Whole document
    if (showDocumentConfigurator) {
        val document = controller.document
        AdaptiveSheet(onDismissRequest = { showDocumentConfigurator = false }, maxWidth = 760.dp) {
            // The document as it changes, while the sheet is open
            document.observeChanges()
            PageTemplateConfigurator(
                subtitle = stringResource(Res.string.page_config_subtitle_document, document.name),
                initialDimension = Dimension(document.defaultWidthMm.mm, document.defaultHeightMm.mm),
                initialBackground = document.defaultBackground,
                onApply = { newDimension, newBackground ->
                    showDocumentConfigurator = false
                    pendingDocumentTemplate = newDimension to newBackground
                },
                onCancel = { showDocumentConfigurator = false }
            )
        }
    }

    // 3. Confirmation (overwrite the custom backgrounds?)
    pendingDocumentTemplate?.let { (dimension, background) ->
        AlertDialog(
            onDismissRequest = { pendingDocumentTemplate = null },
            icon = { Icon(DrawViewIcons.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
            title = { Text(stringResource(Res.string.dialog_override_bg_title)) },
            text = { Text(stringResource(Res.string.dialog_override_bg_message)) },
            confirmButton = {
                TonalButton(onClick = {
                    controller.pageManager.applyDocumentTemplateChange(dimension, background, overrideAll = true)
                    pendingDocumentTemplate = null
                }) {
                    Text(stringResource(Res.string.dialog_override_bg_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    controller.pageManager.applyDocumentTemplateChange(dimension, background, overrideAll = false)
                    pendingDocumentTemplate = null
                }) {
                    Text(stringResource(Res.string.dialog_override_bg_dismiss))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    if (showAboutDialog) {
        AboutLicensesDialog(onDismiss = { showAboutDialog = false })
    }
}
