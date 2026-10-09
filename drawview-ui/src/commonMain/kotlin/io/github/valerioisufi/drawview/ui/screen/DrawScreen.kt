package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveSheet
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.components.AboutLicensesDialog
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.RegisterCommands
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.zIndex
import io.github.valerioisufi.drawview.core.canvas.DrawCanvas
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.geometry.Dimension
import io.github.valerioisufi.drawview.core.geometry.mm
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.ui.components.PageGridOverlay
import io.github.valerioisufi.drawview.ui.components.PageTemplateConfigurator
import io.github.valerioisufi.drawview.ui.editor.CanvasContextMenu
import io.github.valerioisufi.drawview.ui.editor.SelectionActionBar
import io.github.valerioisufi.drawview.ui.editor.TextMarkupActionBar
import io.github.valerioisufi.drawview.ui.editor.TextEditorOverlay
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.platform.HideSystemBarsEffect
import io.github.valerioisufi.drawview.ui.platform.PickerType
import io.github.valerioisufi.drawview.ui.platform.PencilInteractionEffect
import io.github.valerioisufi.drawview.ui.platform.fileDropTarget
import io.github.valerioisufi.drawview.ui.platform.rememberFilePicker
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.session.EditorPanel
import io.github.valerioisufi.drawview.ui.theme.LocalCanvasColors
import io.github.valerioisufi.drawview.ui.theme.rememberNotesFonts
import org.jetbrains.compose.resources.getString
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawScreen(
    controller: DrawViewController,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    toolbarActions: @Composable RowScope.() -> Unit = {},
    panels: List<EditorPanel> = emptyList(),
    canvasOverlay: @Composable BoxScope.() -> Unit = {},
    extraCommands: List<Command> = emptyList()
) {
    // Immersive editor (Android), as in the original app
    HideSystemBarsEffect()

    // Theme colors of the canvas (desk, paper, selection), updated with the Material theme
    val colorScheme = MaterialTheme.colorScheme
    val canvasColors = LocalCanvasColors.current
    LaunchedEffect(colorScheme, canvasColors) {
        controller.themeColors = io.github.valerioisufi.drawview.core.model.DrawThemeColors(
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
        val renderer = controller.tileManager.tileRenderer
        if (renderer.textAdapter == null) {
            renderer.textAdapter = ComposeTextTileAdapter(fontFamilyResolver, notesFonts)
            controller.tileManager.invalidateAll()
            controller.requestRedraw()
        }
        onDispose { }
    }
    // The formulas are drawn again once the LaTeX engine is ready
    val latexReady by LatexEngine.isReady.collectAsState()
    LaunchedEffect(latexReady) {
        if (latexReady) {
            (controller.tileManager.tileRenderer.textAdapter as? ComposeTextTileAdapter)?.clearCaches()
            controller.tileManager.invalidateAll()
            controller.requestRedraw()
        }
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
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    val pdfPicker = rememberFilePicker(PickerType.PDF) { file ->
        controller.mediaManager.importPdf(file.bytes)
    }
    val imagePicker = rememberFilePicker(PickerType.IMAGE) { file ->
        controller.mediaManager.importImage(file.bytes, file.extension.ifBlank { "png" })
    }
    // Template dialogs
    var showSinglePageConfigurator by remember { mutableStateOf(false) }
    var showDocumentConfigurator by remember { mutableStateOf(false) }
    var pendingDocumentTemplate by remember { mutableStateOf<Pair<Dimension, PageBackground>?>(null) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // The commands of the editor: the bars below, the menu bar of the window, the shortcuts
    val commands = rememberEditorCommands(
        controller = controller,
        onClose = onNavigateBack,
        onImportPdf = pdfPicker,
        onImportImage = imagePicker,
        onEditDocumentBackground = { showDocumentConfigurator = true },
        onShowAbout = { showAboutDialog = true }
    )
    RegisterCommands(remember(commands, extraCommands) { commands.all + extraCommands })
    // The double tap and the squeeze of an Apple Pencil, as the user set them in the system
    PencilInteractionEffect(onAction = commands::perform)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Where the tools go, by the width of the editor and the buttons of its input
        val window = currentAdaptiveInfo().window
        val button = LocalMinimumInteractiveComponentSize.current.takeIf { it.isSpecified } ?: 48.dp
        var extensionsWidth by remember { mutableStateOf(0.dp) }
        val pdfTools = if (controller.tileManager.tileRenderer.pdfAdapter != null) 1 else 0
        val textMarkupShown = pdfTools == 1 && controller.document.pages.any { it.pdfs.isNotEmpty() }
        val toolCount = 2 + 6 + (if (textMarkupShown) 1 else 0) + 1 + pdfTools
        val arrangement = ToolbarLayout.arrange(maxWidth, window.heightClass, button, extensionsWidth, toolCount, groups = 3)
        val showPageGrid = arrangement != ToolbarArrangement.FLOATING_TOOLS || ToolbarLayout.pageGridFits(maxWidth, button, extensionsWidth)
        val toolsMaxWidth = (maxWidth - ToolbarLayout.topWidth(button, extensionsWidth)).coerceAtLeast(0.dp)

        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .zIndex(1f)
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    EditorTopBar(
                        controller = controller,
                        commands = commands,
                        isLoading = isLoading,
                        arrangement = arrangement,
                        showPageGrid = showPageGrid,
                        toolsMaxWidth = toolsMaxWidth,
                        toolbarActions = toolbarActions,
                        onExtensionsWidth = { extensionsWidth = it }
                    )

                    // The bar of the tools under the top one
                    AnimatedVisibility(visible = arrangement == ToolbarArrangement.TWO_BARS && controller.isDrawingMode) {
                        Column {
                            HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(ToolbarLayout.BarHeight)
                                    .padding(horizontal = 4.dp)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                EditorTools(controller, commands, withImports = true)
                            }
                        }
                    }

                    HorizontalDivider()
                }
            }

            // =========================================================
            // CANVAS AND OVERLAYS (+ PANELS)
            // =========================================================
            EditorScaffold(
                panels = panels,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { bottomInset ->
                // A PDF or an image dragged from the files of the computer is imported
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .fileDropTarget { files ->
                            !controller.isReadOnly && files.map { controller.mediaManager.importFile(it.bytes, it.extension) }.any { it }
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
                    // Not the one of the column of the screen, around
                    androidx.compose.animation.AnimatedVisibility(
                        visible = floating,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomInset)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                            .padding(16.dp)
                    ) {
                        FloatingTools(controller, commands)
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomInset + if (floating) ToolbarLayout.BarHeight + 24.dp else 0.dp)
                            .padding(16.dp)
                    )
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
            PageGridOverlay(
                controller = controller,
                onEditPageBackground = { showSinglePageConfigurator = true }
            )
        }
    }

    // =========================================================
    // BACKGROUND TEMPLATE DIALOGS
    // =========================================================

    // 1. Single page (the page targeted by the context menu or the page grid)
    if (showSinglePageConfigurator) {
        val targetIndex = controller.pageManager.contextMenuTargetPageIndex
        val targetPage = controller.document.pages.getOrNull(targetIndex)

        AdaptiveSheet(onDismissRequest = { showSinglePageConfigurator = false }) {
            PageTemplateConfigurator(
                initialDimension = targetPage?.dimension ?: Dimension.A4(),
                initialBackground = targetPage?.background ?: PageBackground.Solid(),
                onApply = { newDimension, newBackground ->
                    controller.pageManager.changeSinglePageTemplate(targetIndex, newDimension, newBackground)
                    showSinglePageConfigurator = false
                },
                onCancel = { showSinglePageConfigurator = false }
            )
        }
    }

    // 2. Whole document
    if (showDocumentConfigurator) {
        val document = controller.document
        AdaptiveSheet(onDismissRequest = { showDocumentConfigurator = false }) {
            PageTemplateConfigurator(
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
            title = { Text(stringResource(Res.string.dialog_override_bg_title)) },
            text = { Text(stringResource(Res.string.dialog_override_bg_message)) },
            confirmButton = {
                TextButton(onClick = {
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
            }
        )
    }

    if (showAboutDialog) {
        AboutLicensesDialog(onDismiss = { showAboutDialog = false })
    }
}
