package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.controller.ToolPreset
import io.github.valerioisufi.drawview.core.geometry.mm
import io.github.valerioisufi.drawview.core.model.LassoMode
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.ui.adaptive.HeightClass
import io.github.valerioisufi.drawview.ui.adaptive.WindowLayout
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandMenu
import io.github.valerioisufi.drawview.ui.command.HoverTooltip
import io.github.valerioisufi.drawview.ui.components.DocumentInfoSelector
import io.github.valerioisufi.drawview.ui.components.ExpandableToolButton
import io.github.valerioisufi.drawview.ui.components.PresetEditMenu
import io.github.valerioisufi.drawview.ui.components.QuickPresetButton
import io.github.valerioisufi.drawview.ui.components.SizeSlider
import io.github.valerioisufi.drawview.ui.components.TextColorPaletteRow
import io.github.valerioisufi.drawview.ui.components.TextColorPickerDialog
import io.github.valerioisufi.drawview.ui.components.TextMarkupPresetButton
import io.github.valerioisufi.drawview.ui.components.TextMarkupPresetEditMenu
import io.github.valerioisufi.drawview.ui.components.ToolButton
import io.github.valerioisufi.drawview.ui.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Where the tools of the editor go, by the space of the editor. */
internal enum class ToolbarArrangement {
    /** In the top bar: one bar (a wide window). */
    ONE_BAR,

    /** In a bar under the top one. */
    TWO_BARS,

    /** Floating at the bottom of the canvas, within reach of the thumb (a phone, a short window). */
    FLOATING_TOOLS
}

/**
 * Which arrangement of the bars of the editor fits its width, and which buttons of the top bar
 * fit beside the title (the others go to the "more" menu).
 */
internal object ToolbarLayout {
    /** The height of a bar. */
    val BarHeight: Dp = 48.dp

    /** The width the title of the document keeps in the top bar. */
    val TitleMinWidth: Dp = 160.dp

    /** The width of a divider between groups of tools. */
    val DividerWidth: Dp = 18.dp

    /** The margins of a bar. */
    val BarPadding: Dp = 16.dp

    /** The buttons of the top bar besides the title and the extensions: back, page grid, drawing mode, more. */
    const val TopButtons = 4

    /** The width of the top bar without the tools: its buttons, the actions of the extensions, the title. */
    fun topWidth(button: Dp, extensionsWidth: Dp, buttons: Int = TopButtons): Dp =
        BarPadding + button * buttons + extensionsWidth + TitleMinWidth

    /** The width of [tools] buttons of tools in [groups] groups. */
    fun toolsWidth(button: Dp, tools: Int, groups: Int): Dp = button * tools + DividerWidth * (groups - 1)

    /**
     * The arrangement of the tools in an editor [width] wide, in a window of [heightClass], with
     * buttons [button] wide, the actions of the extensions [extensionsWidth] wide and [tools]
     * buttons of tools in [groups] groups.
     */
    fun arrange(width: Dp, heightClass: HeightClass, button: Dp, extensionsWidth: Dp, tools: Int, groups: Int): ToolbarArrangement = when {
        width < WindowLayout.MediumWidth || heightClass == HeightClass.COMPACT -> ToolbarArrangement.FLOATING_TOOLS
        topWidth(button, extensionsWidth) + toolsWidth(button, tools, groups) <= width -> ToolbarArrangement.ONE_BAR
        else -> ToolbarArrangement.TWO_BARS
    }

    /** Whether the page grid keeps its button in the top bar (else it goes to the "more" menu). */
    fun pageGridFits(width: Dp, button: Dp, extensionsWidth: Dp): Boolean = topWidth(button, extensionsWidth) <= width
}

/**
 * The top bar of the editor: back and the page grid (when it fits), the title of the document,
 * the tools when they fit in one bar, the actions of the extensions, the drawing mode and the
 * "more" menu with the commands left out of the bars.
 *
 * @param onExtensionsWidth the width the actions of the extensions take, measured.
 */
@Composable
internal fun EditorTopBar(
    controller: DrawViewController,
    commands: EditorCommands,
    isLoading: Boolean,
    arrangement: ToolbarArrangement,
    showPageGrid: Boolean,
    toolsMaxWidth: Dp,
    toolbarActions: @Composable RowScope.() -> Unit,
    onExtensionsWidth: (Dp) -> Unit
) {
    val density = LocalDensity.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .height(ToolbarLayout.BarHeight)
            .padding(horizontal = ToolbarLayout.BarPadding / 2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton(onClick = commands.close.perform, command = commands.close) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(Res.string.common_action_back)
            )
        }
        if (showPageGrid) {
            ToolButton(
                onClick = commands.pageGrid.perform,
                selected = controller.isPageGridVisible,
                command = commands.pageGrid
            ) {
                Icon(
                    imageVector = Icons.Outlined.GridView,
                    contentDescription = stringResource(Res.string.draw_toolbar_action_grid_view)
                )
            }
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            DocumentInfoSelector(document = if (isLoading) null else controller.document)
        }

        if (arrangement == ToolbarArrangement.ONE_BAR && controller.isDrawingMode) {
            Row(
                modifier = Modifier
                    .widthIn(max = toolsMaxWidth)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditorTools(controller, commands, withImports = true)
            }
        }

        Row(
            modifier = Modifier.onSizeChanged { with(density) { onExtensionsWidth(it.width.toDp()) } },
            verticalAlignment = Alignment.CenterVertically
        ) {
            toolbarActions()
        }

        if (controller.isReadOnly) {
            // Shared with this device to be read only
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = stringResource(Res.string.draw_read_only),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        } else {
            ToolButton(
                onClick = commands.drawingMode.perform,
                selected = controller.isDrawingMode,
                command = commands.drawingMode
            ) {
                Icon(
                    imageVector = Icons.Outlined.Draw,
                    contentDescription = stringResource(Res.string.draw_toolbar_action_draw)
                )
            }
        }

        MoreMenu(commands, arrangement, showPageGrid)
    }
}

/**
 * The "more" button and its menu: the commands left out of the bars (the page grid, the imports
 * under floating tools), the pages, the view, the document and about.
 */
@Composable
private fun MoreMenu(commands: EditorCommands, arrangement: ToolbarArrangement, showPageGrid: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val floating = arrangement == ToolbarArrangement.FLOATING_TOOLS
    Box {
        val more = stringResource(Res.string.common_action_more_options)
        HoverTooltip(more) {
            ToolButton(onClick = { expanded = true }) {
                Icon(imageVector = Icons.Outlined.MoreHoriz, contentDescription = more)
            }
        }
        CommandMenu(
            sections = listOf(
                listOfNotNull(commands.pageGrid.takeIf { !showPageGrid }, commands.addPage) +
                    (if (floating) listOf(commands.importPdf, commands.importImage) else emptyList()),
                listOf(commands.fitWidth, commands.stylusOnly, commands.documentBackground),
                listOf(commands.about)
            ),
            expanded = expanded,
            onDismissRequest = { expanded = false }
        )
    }
}

/**
 * The tools of the editor: undo and redo, the drawing tools (their settings opened by their
 * button chosen again, or by a long press), the imports when [withImports].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RowScope.EditorTools(controller: DrawViewController, commands: EditorCommands, withImports: Boolean) {
    val toolManager = controller.toolManager
    val pdfImportAvailable = controller.tileManager.tileRenderer.pdfAdapter != null
    // Tool whose quick-settings ribbon is expanded (null = none)
    var expandedToolSettings by remember { mutableStateOf<Tool?>(null) }

    ToolButton(
        onClick = commands.undo.perform,
        enabled = controller.canUndo,
        command = commands.undo
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
        onClick = commands.redo.perform,
        enabled = controller.canRedo,
        command = commands.redo
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
        command = commands.pen,
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
        command = commands.highlighter,
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
    // 2b. TEXT MARKUP (text of the PDFs)
    // ==========================================
    val hasPdfPages = controller.document.pages.any { it.pdfs.isNotEmpty() }
    if (pdfImportAvailable && (hasPdfPages || controller.selectedTool == Tool.TEXT_MARKUP)) {
        TextMarkupToolButton(
            controller = controller,
            command = commands.textMarkup,
            expanded = expandedToolSettings == Tool.TEXT_MARKUP,
            onExpandedChange = { expandedToolSettings = if (it) Tool.TEXT_MARKUP else null }
        )
    }

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
        command = commands.eraser,
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
        command = commands.text,
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
        command = commands.lasso,
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
        selected = controller.selectedTool == Tool.PAN,
        command = commands.pan
    ) {
        Icon(
            painter = painterResource(Res.drawable.icon_pan_tool),
            contentDescription = stringResource(Res.string.draw_toolbar_action_pan)
        )
    }


    if (withImports) {
        VerticalDivider(
            modifier = Modifier.padding(8.dp),
            thickness = 2.dp
        )

        if (pdfImportAvailable) {
            ToolButton(onClick = commands.importPdf.perform, command = commands.importPdf) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = stringResource(Res.string.draw_toolbar_action_import_pdf)
                )
            }
        }

        ToolButton(onClick = commands.importImage.perform, command = commands.importImage) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = stringResource(Res.string.draw_toolbar_action_import_image)
            )
        }
    }

    // The palette asked by a gesture of the stylus: the settings of the tool chosen (not the
    // requests made before this bar was shown)
    val paletteHandled = remember { mutableIntStateOf(commands.paletteRequests) }
    LaunchedEffect(commands.paletteRequests) {
        if (commands.paletteRequests == paletteHandled.intValue) return@LaunchedEffect
        paletteHandled.intValue = commands.paletteRequests
        when (controller.selectedTool) {
            Tool.INK_PEN, Tool.INK_HIGHLIGHTER, Tool.TEXT_MARKUP -> expandedToolSettings = controller.selectedTool
            Tool.ERASER -> eraserSettingsExpanded = true
            Tool.TEXT -> textSettingsExpanded = true
            Tool.LAZO -> lassoSettingsExpanded = true
            Tool.PAN -> Unit
        }
    }
}

/**
 * The tools floating at the bottom of the canvas (a phone, a short window): undo, redo and the
 * drawing tools, scrolled when they do not fit; the imports are in the "more" menu.
 */
@Composable
internal fun FloatingTools(controller: DrawViewController, commands: EditorCommands, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Row(
                modifier = Modifier
                    .height(ToolbarLayout.BarHeight + 8.dp)
                    .padding(horizontal = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditorTools(controller, commands, withImports = false)
            }
        }
    }
}

/**
 * Button of an ink tool (pen, highlighter) expanding into its presets: tap a preset to select
 * it, tap the selected one to edit it, "+" to add a preset.
 */
@Composable
private fun InkToolButton(
    controller: DrawViewController,
    command: Command,
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
    PresetToolButton(
        controller = controller,
        command = command,
        tool = tool,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        icon = icon,
        contentDescription = contentDescription,
        presetButton = { preset, isSelected, onClick ->
            QuickPresetButton(
                color = Color(preset.color),
                size = preset.sizeMm.mm,
                valueRange = sizeRange,
                isSelected = isSelected,
                onClick = onClick
            )
        },
        presetEditMenu = { index, preset, editing, onDismiss ->
            PresetEditMenu(
                expanded = editing,
                onDismissRequest = onDismiss,
                presetColor = Color(preset.color),
                presetSize = preset.sizeMm.mm,
                sizeValueRange = sizeRange,
                linearThreshold = linearThreshold,
                linearProportion = linearProportion,
                showDeleteOption = toolManager.presetsOf(tool).size > 1,
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
                    onDismiss()
                }
            )
        }
    )
}

/**
 * Button of the text markup tool expanding into its presets (kind of the marks and color): tap a
 * preset to select it, tap the selected one to edit it, "+" to add a preset.
 */
@Composable
private fun TextMarkupToolButton(
    controller: DrawViewController,
    command: Command,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val toolManager = controller.toolManager
    val tool = Tool.TEXT_MARKUP
    PresetToolButton(
        controller = controller,
        command = command,
        tool = tool,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        icon = painterResource(Res.drawable.icon_text_markup),
        contentDescription = stringResource(Res.string.draw_toolbar_action_text_markup),
        presetButton = { preset, isSelected, onClick ->
            TextMarkupPresetButton(
                kind = preset.markup,
                color = Color(preset.color),
                isSelected = isSelected,
                onClick = onClick
            )
        },
        presetEditMenu = { index, preset, editing, onDismiss ->
            TextMarkupPresetEditMenu(
                expanded = editing,
                onDismissRequest = onDismiss,
                kind = preset.markup,
                color = Color(preset.color),
                showDeleteOption = toolManager.presetsOf(tool).size > 1,
                onKindChanged = { kind ->
                    toolManager.presetsOf(tool).getOrNull(index)?.let {
                        toolManager.updatePreset(tool, index, it.copy(markup = kind))
                    }
                },
                onColorChanged = { newColor ->
                    toolManager.presetsOf(tool).getOrNull(index)?.let {
                        toolManager.updatePreset(tool, index, it.copy(color = newColor.toArgb()))
                    }
                },
                onDeleteClicked = {
                    toolManager.removePreset(tool, index)
                    onDismiss()
                }
            )
        }
    )
}

/**
 * Button of a tool with presets expanding into them: tap a preset to select it, tap the selected
 * one to edit it ([presetEditMenu], anchored to the preset), "+" to add a preset. [command] is
 * the one choosing the tool (its tooltip).
 */
@Composable
private fun PresetToolButton(
    controller: DrawViewController,
    command: Command,
    tool: Tool,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    icon: Painter,
    contentDescription: String,
    presetButton: @Composable (preset: ToolPreset, isSelected: Boolean, onClick: () -> Unit) -> Unit,
    presetEditMenu: @Composable (index: Int, preset: ToolPreset, expanded: Boolean, onDismiss: () -> Unit) -> Unit
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
                selected = isToolSelected,
                command = command
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
                    presetButton(preset, isSelected) {
                        if (isSelected) editingPresetIndex = index
                        else controller.selectTool(tool, index)
                    }
                    presetEditMenu(index, preset, editingPresetIndex == index) { editingPresetIndex = null }
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
