package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import io.github.valerioisufi.drawview.ui.components.AboutLicensesDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.valerioisufi.drawview.core.canvas.DrawCanvas
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.geometry.Dimension
import io.github.valerioisufi.drawview.core.geometry.mm
import io.github.valerioisufi.drawview.core.model.LassoMode
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.ui.components.DocumentInfoSelector
import io.github.valerioisufi.drawview.ui.components.ExpandableToolButton
import io.github.valerioisufi.drawview.ui.components.PageGridOverlay
import io.github.valerioisufi.drawview.ui.components.PageTemplateConfigurator
import io.github.valerioisufi.drawview.ui.components.PresetEditMenu
import io.github.valerioisufi.drawview.ui.components.QuickPresetButton
import io.github.valerioisufi.drawview.ui.components.SizeSlider
import io.github.valerioisufi.drawview.ui.components.TextColorPaletteRow
import io.github.valerioisufi.drawview.ui.components.TextColorPickerDialog
import io.github.valerioisufi.drawview.ui.components.ToolButton
import io.github.valerioisufi.drawview.ui.editor.CanvasContextMenu
import io.github.valerioisufi.drawview.ui.editor.SelectionActionBar
import io.github.valerioisufi.drawview.ui.editor.TextEditorOverlay
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.platform.HideSystemBarsEffect
import io.github.valerioisufi.drawview.ui.platform.PickerType
import io.github.valerioisufi.drawview.ui.platform.rememberFilePicker
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Main drawing interface: top navigation bar, tool ribbon, drawing canvas with its overlays
 * (selection action bar, context menu, text editor), page grid and template dialogs.
 *
 * @param controller editing session of the document.
 * @param onNavigateBack invoked by the back button of the toolbar.
 * @param isLoading shows a progress bar while the document is loaded.
 * @param toolbarActions additional actions of the top bar, before the drawing mode toggle.
 * @param sidePanel panel shown at the end of the canvas (e.g. an assistant), sharing its height.
 * @param canvasOverlay content drawn over the canvas (e.g. hints of an assistant).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawScreen(
    controller: DrawViewController,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    toolbarActions: @Composable RowScope.() -> Unit = {},
    sidePanel: @Composable () -> Unit = {},
    canvasOverlay: @Composable BoxScope.() -> Unit = {}
) {
    // Immersive editor (Android), as in the original app
    HideSystemBarsEffect()

    // Theme colors of the canvas (desk, paper, selection), updated with the Material theme
    val colorScheme = MaterialTheme.colorScheme
    LaunchedEffect(colorScheme) {
        controller.themeColors = io.github.valerioisufi.drawview.core.model.DrawThemeColors(
            backgroundColor = colorScheme.surfaceVariant.toArgb(),
            surfaceColor = colorScheme.surface.toArgb(),
            primaryColor = colorScheme.primary.toArgb(),
            onSurfaceColor = colorScheme.onSurface.toArgb()
        )
        controller.requestRedraw()
    }

    // Text blocks rendering (Markdown + LaTeX) of the tiles, thumbnails and selection
    val fontFamilyResolver = LocalFontFamilyResolver.current
    DisposableEffect(controller, fontFamilyResolver) {
        val renderer = controller.tileManager.tileRenderer
        if (renderer.textAdapter == null) {
            renderer.textAdapter = ComposeTextTileAdapter(fontFamilyResolver)
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
    val pdfImportAvailable = controller.tileManager.tileRenderer.pdfAdapter != null

    // Tool whose quick-settings ribbon is expanded (null = none)
    var expandedToolSettings by remember { mutableStateOf<Tool?>(null) }

    // Template dialogs
    var showSinglePageConfigurator by remember { mutableStateOf(false) }
    var showDocumentConfigurator by remember { mutableStateOf(false) }
    var pendingDocumentTemplate by remember { mutableStateOf<Pair<Dimension, PageBackground>?>(null) }
    var showAboutDialog by remember { mutableStateOf(false) }

    val toolManager = controller.toolManager

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .zIndex(1f)
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(1f)
                            .height(44.dp)
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row {
                            ToolButton(onClick = onNavigateBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = stringResource(Res.string.common_action_back)
                                )
                            }
                            ToolButton(
                                onClick = { controller.togglePageGrid() },
                                selected = controller.isPageGridVisible
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.GridView,
                                    contentDescription = stringResource(Res.string.draw_toolbar_action_grid_view)
                                )
                            }
                        }

                        DocumentInfoSelector(
                            document = if (isLoading) null else controller.documentData,
                            modifier = Modifier
                        )

                        Row {
                            toolbarActions()

                            ToolButton(
                                onClick = {
                                    expandedToolSettings = null
                                    controller.toggleDrawingMode()
                                },
                                selected = controller.isDrawingMode
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Draw,
                                    contentDescription = stringResource(Res.string.draw_toolbar_action_draw)
                                )
                            }

                            var moreOptionsExpanded by remember { mutableStateOf(false) }

                            ToolButton(
                                onClick = { moreOptionsExpanded = true },
                                expanded = moreOptionsExpanded,
                                onDismissRequest = { moreOptionsExpanded = false },
                                dropDownMenu = {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = stringResource(Res.string.draw_menu_settings),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(bottom = 12.dp)
                                        )

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(text = stringResource(Res.string.draw_menu_stylus_only))
                                            Switch(
                                                checked = controller.isStylusOnlyMode,
                                                onCheckedChange = { isChecked -> controller.updateStylusOnlyMode(isChecked) }
                                            )
                                        }

                                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    moreOptionsExpanded = false
                                                    showDocumentConfigurator = true
                                                }
                                                .padding(vertical = 8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Palette,
                                                contentDescription = null,
                                                modifier = Modifier.padding(end = 16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = stringResource(Res.string.draw_menu_document_background),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    moreOptionsExpanded = false
                                                    showAboutDialog = true
                                                }
                                                .padding(vertical = 8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Info,
                                                contentDescription = null,
                                                modifier = Modifier.padding(end = 16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = stringResource(Res.string.draw_menu_about_licenses),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreHoriz,
                                    contentDescription = stringResource(Res.string.common_action_more_options)
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = controller.isDrawingMode) {
                        Column {
                            HorizontalDivider()

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .padding(horizontal = 4.dp)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ToolButton(
                                    onClick = { controller.undo() },
                                    enabled = controller.canUndo
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Undo,
                                        contentDescription = stringResource(Res.string.common_action_undo),
                                        tint = if (controller.canUndo) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        }
                                    )
                                }

                                ToolButton(
                                    onClick = { controller.redo() },
                                    enabled = controller.canRedo
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Redo,
                                        contentDescription = stringResource(Res.string.common_action_redo),
                                        tint = if (controller.canRedo) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        }
                                    )
                                }

                                VerticalDivider(
                                    modifier = Modifier.padding(8.dp),
                                    thickness = 2.dp
                                )

                                // ==========================================
                                // 1. INK PEN
                                // ==========================================
                                InkToolButton(
                                    controller = controller,
                                    tool = Tool.INK_PEN,
                                    expanded = expandedToolSettings == Tool.INK_PEN,
                                    onExpandedChange = { expandedToolSettings = if (it) Tool.INK_PEN else null },
                                    icon = painterResource(Res.drawable.icon_ink_pen),
                                    contentDescription = stringResource(Res.string.draw_toolbar_action_ink_pen),
                                    // Linear precision up to 3 pt, on half of the slider
                                    sizeRange = 0.1f..15f,
                                    linearThreshold = 3f,
                                    linearProportion = 0.5f
                                )

                                // ==========================================
                                // 2. INK HIGHLIGHTER
                                // ==========================================
                                InkToolButton(
                                    controller = controller,
                                    tool = Tool.INK_HIGHLIGHTER,
                                    expanded = expandedToolSettings == Tool.INK_HIGHLIGHTER,
                                    onExpandedChange = { expandedToolSettings = if (it) Tool.INK_HIGHLIGHTER else null },
                                    icon = painterResource(Res.drawable.icon_ink_highlighter),
                                    contentDescription = stringResource(Res.string.draw_toolbar_action_highlighter),
                                    // Much wider range for the highlighters, linear only up to 8 pt
                                    sizeRange = 1f..30f,
                                    linearThreshold = 8f,
                                    linearProportion = 0.3f
                                )

                                // ==========================================
                                // 3. ERASER
                                // ==========================================
                                var eraserSettingsExpanded by remember { mutableStateOf(false) }
                                ToolButton(
                                    onClick = {
                                        expandedToolSettings = null
                                        if (controller.selectedTool == Tool.ERASER) {
                                            eraserSettingsExpanded = true
                                        } else {
                                            controller.selectTool(Tool.ERASER)
                                        }
                                    },
                                    onLongClick = {
                                        expandedToolSettings = null
                                        controller.selectTool(Tool.ERASER)
                                        eraserSettingsExpanded = true
                                    },
                                    selected = controller.selectedTool == Tool.ERASER,
                                    dropDownMenu = {
                                        val eraserIndex = toolManager.selectedPresetIndex(Tool.ERASER)
                                        val eraserPreset = toolManager.activePreset(Tool.ERASER)
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = stringResource(Res.string.draw_toolbar_label_eraser_size),
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            SizeSlider(
                                                modifier = Modifier.padding(8.dp),
                                                size = (eraserPreset?.sizeMm ?: 8f).mm,
                                                // Large erasers, linear precision only for small corrections
                                                valueRange = 2f..50f,
                                                linearThreshold = 10f,
                                                linearProportion = 0.25f,
                                                onSizeChanged = { newMeasure ->
                                                    eraserPreset?.let {
                                                        toolManager.updatePreset(Tool.ERASER, eraserIndex, it.copy(sizeMm = newMeasure.mm))
                                                    }
                                                }
                                            )
                                        }
                                    },
                                    expanded = eraserSettingsExpanded,
                                    onDismissRequest = { eraserSettingsExpanded = false }
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.icon_ink_eraser),
                                        contentDescription = stringResource(Res.string.draw_toolbar_action_eraser)
                                    )
                                }

                                // ==========================================
                                // 4. TEXT
                                // ==========================================
                                var textSettingsExpanded by remember { mutableStateOf(false) }
                                ToolButton(
                                    onClick = {
                                        expandedToolSettings = null
                                        if (controller.selectedTool == Tool.TEXT) {
                                            textSettingsExpanded = true
                                        } else {
                                            controller.selectTool(Tool.TEXT)
                                        }
                                    },
                                    onLongClick = {
                                        expandedToolSettings = null
                                        controller.selectTool(Tool.TEXT)
                                        textSettingsExpanded = true
                                    },
                                    selected = controller.selectedTool == Tool.TEXT,
                                    dropDownMenu = { TextToolSettings(controller) },
                                    expanded = textSettingsExpanded,
                                    onDismissRequest = { textSettingsExpanded = false }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.TextFields,
                                        contentDescription = stringResource(Res.string.draw_toolbar_action_text)
                                    )
                                }

                                // ==========================================
                                // 5. LASSO
                                // ==========================================
                                var lassoSettingsExpanded by remember { mutableStateOf(false) }
                                ToolButton(
                                    onClick = {
                                        expandedToolSettings = null
                                        if (controller.selectedTool == Tool.LAZO) {
                                            lassoSettingsExpanded = true
                                        } else {
                                            controller.selectTool(Tool.LAZO)
                                        }
                                    },
                                    onLongClick = {
                                        expandedToolSettings = null
                                        controller.selectTool(Tool.LAZO)
                                        lassoSettingsExpanded = true
                                    },
                                    selected = controller.selectedTool == Tool.LAZO,
                                    dropDownMenu = {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            horizontalAlignment = Alignment.Start
                                        ) {
                                            Text(
                                                text = stringResource(Res.string.draw_toolbar_title_lasso_mode),
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(bottom = 12.dp)
                                            )
                                            LassoModeOption(
                                                selected = controller.lassoMode == LassoMode.ALL,
                                                label = stringResource(Res.string.draw_toolbar_option_select_all),
                                                onSelect = { controller.lassoMode = LassoMode.ALL }
                                            )
                                            LassoModeOption(
                                                selected = controller.lassoMode == LassoMode.IMAGES_ONLY,
                                                label = stringResource(Res.string.draw_toolbar_option_images_only),
                                                onSelect = { controller.lassoMode = LassoMode.IMAGES_ONLY }
                                            )
                                        }
                                    },
                                    expanded = lassoSettingsExpanded,
                                    onDismissRequest = { lassoSettingsExpanded = false }
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.icon_lasso_select),
                                        contentDescription = stringResource(Res.string.draw_toolbar_action_lasso)
                                    )
                                }

                                // ==========================================
                                // 6. PAN
                                // ==========================================
                                ToolButton(
                                    onClick = {
                                        expandedToolSettings = null
                                        controller.selectTool(Tool.PAN)
                                    },
                                    selected = controller.selectedTool == Tool.PAN
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.icon_pan_tool),
                                        contentDescription = stringResource(Res.string.draw_toolbar_action_pan)
                                    )
                                }

                                VerticalDivider(
                                    modifier = Modifier.padding(8.dp),
                                    thickness = 2.dp
                                )

                                if (pdfImportAvailable) {
                                    ToolButton(onClick = pdfPicker) {
                                        Icon(
                                            imageVector = Icons.Default.PictureAsPdf,
                                            contentDescription = stringResource(Res.string.draw_toolbar_action_import_pdf)
                                        )
                                    }
                                }

                                ToolButton(onClick = imagePicker) {
                                    Icon(
                                        imageVector = Icons.Default.Image,
                                        contentDescription = stringResource(Res.string.draw_toolbar_action_import_image)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()
                }
            }

            // =========================================================
            // CANVAS AND OVERLAYS (+ SIDE PANEL)
            // =========================================================
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
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
                            modifier = Modifier.align(Alignment.TopCenter)
                        )

                        CanvasContextMenu(
                            controller = controller,
                            onEditPageBackground = { showSinglePageConfigurator = true }
                        )

                        TextEditorOverlay(controller = controller)

                        canvasOverlay()
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    )
                }

                sidePanel()
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

        ModalBottomSheet(onDismissRequest = { showSinglePageConfigurator = false }) {
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
        ModalBottomSheet(onDismissRequest = { showDocumentConfigurator = false }) {
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

/**
 * Button of an ink tool (pen, highlighter) expanding into its presets: tap a preset to select
 * it, tap the selected one to edit it, "+" to add a preset.
 */
@Composable
private fun InkToolButton(
    controller: DrawViewController,
    tool: Tool,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    icon: Painter,
    contentDescription: String,
    sizeRange: ClosedFloatingPointRange<Float>,
    linearThreshold: Float,
    linearProportion: Float
) {
    val toolManager = controller.toolManager
    var editingPresetIndex by remember { mutableStateOf<Int?>(null) }
    val isToolSelected = controller.selectedTool == tool

    ExpandableToolButton(
        isExpanded = expanded,
        isSelected = isToolSelected,
        mainIcon = {
            ToolButton(
                onClick = {
                    if (isToolSelected) {
                        onExpandedChange(!expanded)
                    } else {
                        controller.selectTool(tool)
                        onExpandedChange(false)
                    }
                },
                onLongClick = {
                    controller.selectTool(tool)
                    onExpandedChange(true)
                },
                selected = isToolSelected
            ) {
                Icon(painter = icon, contentDescription = contentDescription)
            }
        },
        expandedContent = {
            val presets = toolManager.presetsOf(tool)
            val selectedIndex = toolManager.selectedPresetIndex(tool)

            presets.forEachIndexed { index, preset ->
                val isSelected = isToolSelected && selectedIndex == index

                Box {
                    QuickPresetButton(
                        color = Color(preset.color),
                        size = preset.sizeMm.mm,
                        valueRange = sizeRange,
                        isSelected = isSelected,
                        onClick = {
                            if (isSelected) editingPresetIndex = index
                            else controller.selectTool(tool, index)
                        }
                    )

                    PresetEditMenu(
                        expanded = editingPresetIndex == index,
                        onDismissRequest = { editingPresetIndex = null },
                        presetColor = Color(preset.color),
                        presetSize = preset.sizeMm.mm,
                        sizeValueRange = sizeRange,
                        linearThreshold = linearThreshold,
                        linearProportion = linearProportion,
                        showDeleteOption = presets.size > 1,
                        onColorChanged = { newColor ->
                            toolManager.presetsOf(tool).getOrNull(index)?.let {
                                toolManager.updatePreset(tool, index, it.copy(color = newColor.toArgb()))
                            }
                        },
                        onSizeChanged = { newSize ->
                            toolManager.presetsOf(tool).getOrNull(index)?.let {
                                toolManager.updatePreset(tool, index, it.copy(sizeMm = newSize.mm))
                            }
                        },
                        onDeleteClicked = {
                            toolManager.removePreset(tool, index)
                            editingPresetIndex = null
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            ToolButton(
                onClick = {
                    val base = toolManager.activePreset(tool) ?: presets.firstOrNull()
                    if (base != null) {
                        val newIndex = toolManager.addPreset(tool, base.copy())
                        controller.selectTool(tool, newIndex)
                        editingPresetIndex = newIndex
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(Res.string.draw_toolbar_action_add_preset)
                )
            }
        }
    )
}

/**
 * Defaults of the text tool: font size, color, Markdown live preview and math mode.
 */
@Composable
private fun TextToolSettings(controller: DrawViewController) {
    val editor = controller.textEditorManager
    Column(
        modifier = Modifier
            .padding(16.dp)
            .width(260.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(Res.string.draw_toolbar_title_text_settings),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Text(
            text = "${stringResource(Res.string.draw_toolbar_label_font_size)}: ${controller.defaultTextFontSize.toInt()} pt",
            fontSize = 14.sp
        )
        Slider(
            value = controller.defaultTextFontSize,
            onValueChange = { newSize -> controller.updateTextDefaults(newSize, controller.defaultTextColor) },
            valueRange = 10f..48f,
            steps = 18,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = stringResource(Res.string.draw_toolbar_label_text_color),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        var showDefaultColorWheelDialog by remember { mutableStateOf(false) }
        TextColorPaletteRow(
            selectedColor = Color(controller.defaultTextColor),
            onColorSelected = { newColor ->
                controller.updateTextDefaults(controller.defaultTextFontSize, newColor.toArgb())
            },
            modifier = Modifier.fillMaxWidth(),
            onCustomWheelClick = { showDefaultColorWheelDialog = true }
        )

        if (showDefaultColorWheelDialog) {
            TextColorPickerDialog(
                initialColor = Color(controller.defaultTextColor),
                onDismissRequest = { showDefaultColorWheelDialog = false },
                onColorConfirmed = { newColor ->
                    controller.updateTextDefaults(controller.defaultTextFontSize, newColor.toArgb())
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(Res.string.draw_toolbar_label_markdown), fontSize = 14.sp)
            Switch(
                checked = editor.isMarkdownLivePreviewEnabled,
                onCheckedChange = { editor.isMarkdownLivePreviewEnabled = it }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(Res.string.draw_toolbar_label_math_mode), fontSize = 14.sp)
            Switch(
                checked = editor.isMathMode,
                onCheckedChange = { editor.isMathMode = it }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LassoModeOption(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onSelect)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label)
    }
}
