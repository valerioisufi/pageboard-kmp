package io.github.valerioisufi.drawview.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.zIndex
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.TextFormatTarget
import io.github.valerioisufi.drawview.canvas.observe
import io.github.valerioisufi.drawview.canvas.tool.LassoMode
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.geometry.mm
import io.github.valerioisufi.drawview.model.geometry.pt
import io.github.valerioisufi.drawview.ui.adaptive.HeightClass
import io.github.valerioisufi.drawview.ui.adaptive.WindowLayout
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.command.Command
import io.github.valerioisufi.drawview.ui.command.CommandMenu
import io.github.valerioisufi.drawview.ui.command.DesignMenu
import io.github.valerioisufi.drawview.ui.components.AddPresetCard
import io.github.valerioisufi.drawview.ui.components.ColorSwatches
import io.github.valerioisufi.drawview.ui.components.DesignIconButton
import io.github.valerioisufi.drawview.ui.components.DocumentInfoSelector
import io.github.valerioisufi.drawview.ui.components.EraserPreview
import io.github.valerioisufi.drawview.ui.components.FontSizeControl
import io.github.valerioisufi.drawview.ui.components.HybridScale
import io.github.valerioisufi.drawview.ui.components.IconButtonStyle
import io.github.valerioisufi.drawview.ui.components.LocalToolSizes
import io.github.valerioisufi.drawview.ui.components.OptionCard
import io.github.valerioisufi.drawview.ui.components.PopoverTitle
import io.github.valerioisufi.drawview.ui.components.PresetCard
import io.github.valerioisufi.drawview.ui.components.SettingHint
import io.github.valerioisufi.drawview.ui.components.SwitchSetting
import io.github.valerioisufi.drawview.ui.components.SettingLabel
import io.github.valerioisufi.drawview.ui.components.SizeSetting
import io.github.valerioisufi.drawview.ui.components.StrokePreview
import io.github.valerioisufi.drawview.ui.components.TextColorPickerDialog
import io.github.valerioisufi.drawview.ui.components.TextMarkupPresetButton
import io.github.valerioisufi.drawview.ui.components.TextMarkupSample
import io.github.valerioisufi.drawview.ui.components.ToolButton
import io.github.valerioisufi.drawview.ui.components.ToolSizes
import io.github.valerioisufi.drawview.ui.components.textMarkupKindLabel
import io.github.valerioisufi.drawview.ui.editor.TextAlignmentButtons
import io.github.valerioisufi.drawview.ui.generated.resources.*
import io.github.valerioisufi.drawview.ui.theme.DrawViewElevation
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.InkPalette
import io.github.valerioisufi.drawview.ui.theme.elevation
import io.github.valerioisufi.drawview.ui.util.formatDecimal
import io.github.valerioisufi.drawview.ui.window.LocalWindowDecorInsets
import io.github.valerioisufi.drawview.ui.window.WindowDraggable
import org.jetbrains.compose.resources.pluralStringResource
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
    /** The height of the top bar (and of the bar of the tools under it). */
    val BarHeight: Dp = 64.dp

    /** The height of the top bar of a phone. */
    val CompactBarHeight: Dp = 52.dp

    /** The height of the tools floating at the bottom. */
    val FloatingHeight: Dp = 56.dp

    /** The width the title of the document keeps in the top bar. */
    val TitleMinWidth: Dp = 160.dp

    /** The widest title of the document beside the tools. */
    val TitleMaxWidth: Dp = 280.dp

    /** A tool button and the space after it. */
    val ToolWidth: Dp = 46.dp

    /** The width of a divider between groups of tools. */
    val DividerWidth: Dp = 17.dp

    /** The margins of a bar, and of the group of the tools. */
    val BarPadding: Dp = 24.dp
    val GroupPadding: Dp = 16.dp

    /** The buttons of the top bar besides the title and the extensions: back, page grid, more. */
    const val TopButtons = 3

    /**
     * The width of the top bar without the tools: its buttons, the actions of the extensions, the
     * title, and the controls of the window at its start [decorWidth] wide (the traffic lights of
     * macOS, `LocalWindowDecorInsets`).
     */
    fun topWidth(button: Dp, extensionsWidth: Dp, buttons: Int = TopButtons, decorWidth: Dp = 0.dp): Dp =
        BarPadding + decorWidth + button * buttons + extensionsWidth + TitleMinWidth

    /** The width of the group of [tools] buttons of tools in [groups] groups, buttons [button] wide at least. */
    fun toolsWidth(button: Dp, tools: Int, groups: Int): Dp =
        max(ToolWidth, button + 2.dp) * tools + DividerWidth * (groups - 1) + GroupPadding

    /**
     * The arrangement of the tools in an editor [width] wide, in a window of [heightClass], with
     * buttons [button] wide, the actions of the extensions [extensionsWidth] wide, [tools]
     * buttons of tools in [groups] groups and the controls of the window [decorWidth] wide at the
     * start of the top bar.
     */
    fun arrange(
        width: Dp,
        heightClass: HeightClass,
        button: Dp,
        extensionsWidth: Dp,
        tools: Int,
        groups: Int,
        decorWidth: Dp = 0.dp,
        toolButton: Dp = button
    ): ToolbarArrangement = when {
        width < WindowLayout.MediumWidth || heightClass == HeightClass.COMPACT -> ToolbarArrangement.FLOATING_TOOLS
        topWidth(button, extensionsWidth, decorWidth = decorWidth) + toolsWidth(toolButton, tools, groups) <= width -> ToolbarArrangement.ONE_BAR
        else -> ToolbarArrangement.TWO_BARS
    }

    /**
     * The widest title beside the tools in one bar: the room they leave in a bar [width] wide, up
     * to [TitleMaxWidth], so that a long title never pushes the buttons after the tools out of
     * the bar.
     */
    fun titleMaxWidth(width: Dp, button: Dp, extensionsWidth: Dp, tools: Int, groups: Int, decorWidth: Dp = 0.dp, toolButton: Dp = button): Dp =
        (width - topWidth(button, extensionsWidth, decorWidth = decorWidth) - toolsWidth(toolButton, tools, groups) + TitleMinWidth)
            .coerceIn(TitleMinWidth, TitleMaxWidth)

    /**
     * Whether the actions of the extensions, [extensionsWidth] wide, keep their place in the top
     * bar beside a title of [TitleMinWidth] and [buttons] other buttons (else they are behind one
     * button).
     */
    fun extensionsFit(width: Dp, button: Dp, extensionsWidth: Dp, buttons: Int, decorWidth: Dp = 0.dp): Boolean =
        topWidth(button, extensionsWidth, buttons, decorWidth) <= width

    /** Whether the page grid keeps its button in the top bar (else it goes to the "more" menu). */
    fun pageGridFits(width: Dp, button: Dp, extensionsWidth: Dp, decorWidth: Dp = 0.dp): Boolean =
        topWidth(button, extensionsWidth, decorWidth = decorWidth) <= width
}

/**
 * The top bar of the editor: back and the page grid (when it fits), the title of the document and
 * what it holds, the tools in their group when they fit in one bar, the actions of the extensions
 * and the "more" menu with the commands left out of the bars.
 *
 * @param toolsMaxWidth the widest group of the tools, or "Read only", in the top bar.
 * @param titleMaxWidth the widest title beside the tools in the top bar.
 * @param location where the document is (its folder), shown under its title.
 * @param extensionsInBar whether the actions of the extensions are in the bar, else behind one
 *   button that shows them in a popover.
 * @param onExtensionsWidth the width the actions of the extensions take, measured.
 * @param readOnlyNote why the document may only be read, beside "Read only" where the tools would be.
 * @param renamer renames the document from its title (none: the title is not changed there).
 */
@Composable
internal fun EditorTopBar(
    controller: DrawViewController,
    commands: EditorCommands,
    isLoading: Boolean,
    arrangement: ToolbarArrangement,
    showPageGrid: Boolean,
    toolsMaxWidth: Dp,
    titleMaxWidth: Dp,
    toolbarActions: @Composable RowScope.() -> Unit,
    onExtensionsWidth: (Dp) -> Unit,
    extensionsInBar: Boolean = true,
    location: String? = null,
    readOnlyNote: @Composable () -> String? = { null },
    renamer: DocumentRenamer? = null
) {
    val density = LocalDensity.current
    val compact = arrangement == ToolbarArrangement.FLOATING_TOOLS
    val decorStartPad = LocalWindowDecorInsets.current.calculateStartPadding(LocalLayoutDirection.current)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .height(if (compact) ToolbarLayout.CompactBarHeight else ToolbarLayout.BarHeight)
            .padding(
                start = if (compact) 4.dp + decorStartPad else 12.dp + decorStartPad,
                end = if (compact) 4.dp else 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp)
    ) {
        BarButton(commands.close, DrawViewIcons.ArrowLeft, label = stringResource(Res.string.common_action_back))
        if (showPageGrid) {
            BarButton(commands.pageGrid, DrawViewIcons.LayoutGrid, selected = controller.isPageGridVisible)
        }

        val subtitle = documentSubtitle(controller, location, pageOnly = compact)
        val toolsInBar = arrangement == ToolbarArrangement.ONE_BAR && controller.isDrawingMode
        // A document read only: why there are no tools, where they would be
        val readOnlyInBar = !compact && controller.isReadOnly && !isLoading
        DocumentInfoSelector(
            document = if (isLoading) null else controller.document,
            subtitle = subtitle,
            location = location,
            renamer = renamer.takeIf { !controller.isReadOnly },
            renameRequests = commands.renameRequests,
            modifier = when {
                toolsInBar -> Modifier.widthIn(max = titleMaxWidth)
                readOnlyInBar -> Modifier.widthIn(max = ToolbarLayout.TitleMaxWidth)
                else -> Modifier.weight(1f)
            }
        )

        if (toolsInBar) {
            WindowDraggable(modifier = Modifier.weight(1f)) { Spacer(modifier = Modifier.fillMaxWidth()) }
            ToolGroup(modifier = Modifier.widthIn(max = toolsMaxWidth)) {
                EditorTools(controller, commands, withImports = true)
            }
            WindowDraggable(modifier = Modifier.weight(1f)) { Spacer(modifier = Modifier.fillMaxWidth()) }
        } else if (readOnlyInBar) {
            WindowDraggable(modifier = Modifier.weight(1f)) { Spacer(modifier = Modifier.fillMaxWidth()) }
            ReadOnlyBadge(readOnlyNote(), floating = false, modifier = Modifier.widthIn(max = toolsMaxWidth))
            WindowDraggable(modifier = Modifier.weight(1f)) { Spacer(modifier = Modifier.fillMaxWidth()) }
        }

        if (extensionsInBar) {
            Row(
                modifier = Modifier.onSizeChanged { with(density) { onExtensionsWidth(it.width.toDp()) } },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                toolbarActions()
            }
        } else {
            ExtensionsButton(toolbarActions, onExtensionsWidth)
        }

        MoreMenu(commands, arrangement, showPageGrid, renamable = renamer != null && !controller.isReadOnly)
    }
}

/**
 * The actions of the extensions where they do not fit beside the title: one button showing them
 * in a popover, closed once one of them is tapped (what they open is kept by their extension, not
 * by their button). Their width is measured there too, so that they come back to the bar when it
 * has room for them.
 */
@Composable
private fun ExtensionsButton(toolbarActions: @Composable RowScope.() -> Unit, onExtensionsWidth: (Dp) -> Unit) {
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        DesignIconButton(
            icon = DrawViewIcons.Zap,
            label = stringResource(Res.string.editor_action_extensions),
            onClick = { expanded = true },
            selected = expanded,
            tint = MaterialTheme.colorScheme.onSurface
        )
        DesignMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Row(
                modifier = Modifier
                    .onSizeChanged { with(density) { onExtensionsWidth(it.width.toDp()) } }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                            if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) expanded = false
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                toolbarActions()
            }
        }
    }
}

/** "Analysis · 12 pages" beside the tools, "Page 3 of 12" on a phone (the page looked at). */
@Composable
private fun documentSubtitle(controller: DrawViewController, location: String?, pageOnly: Boolean): String {
    val total by controller.document.observe { pages.size }
    if (pageOnly) {
        val page by remember(controller) {
            androidx.compose.runtime.derivedStateOf {
                controller.viewport.camera.moveCount
                controller.version
                (controller.viewport.mostVisiblePageIndex() ?: 0) + 1
            }
        }
        return stringResource(Res.string.editor_page_indicator, page, total)
    }
    return listOfNotNull(location, pluralStringResource(Res.plurals.editor_pages_count, total, total)).joinToString(" · ")
}

/** A button of the top bar running [command] (its label and shortcut in the tooltip), its icon [iconSize] wide. */
@Composable
private fun BarButton(command: Command, icon: ImageVector, label: String = command.label(), selected: Boolean = false, iconSize: Dp = 22.dp) {
    DesignIconButton(
        iconSize = iconSize,
        icon = icon,
        label = label,
        onClick = { command.performIfEnabled() },
        selected = selected,
        enabled = command.isEnabled(),
        shortcut = command.shortcutLabel(currentAdaptiveInfo().conventions),
        tint = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * The group of the tools in the bars: a pill of the container of the surfaces, the tools scrolled
 * when they do not fit; sized for a pen where there is touch (a tablet), with targets of
 * [ToolSizes.PenTarget], as they are as now with a mouse.
 */
@Composable
internal fun ToolGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    if (currentAdaptiveInfo().input.hasTouch) {
        CompositionLocalProvider(LocalToolSizes provides ToolSizes.Pen, LocalMinimumInteractiveComponentSize provides ToolSizes.PenTarget) {
            ToolGroupRow(modifier, content)
        }
    } else {
        ToolGroupRow(modifier, content)
    }
}

@Composable
private fun ToolGroupRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

/**
 * A document that may only be read, where the tools would be: "Read only" and why ([note], e.g.
 * who decides who edits); in the top bar on the container of the tools, else [floating] at the
 * bottom of the canvas.
 */
@Composable
internal fun ReadOnlyBadge(note: String?, floating: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .then(if (floating) Modifier.elevation(DrawViewElevation.Level2, shape) else Modifier)
            .background(if (floating) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer, shape)
            .height(if (floating) 44.dp else 48.dp)
            .padding(start = 14.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(DrawViewIcons.Eye, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(stringResource(Res.string.draw_read_only), style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
        if (note != null) {
            Box(modifier = Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Text(note, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The line between two groups of tools. */
@Composable
private fun ToolDivider() {
    Box(modifier = Modifier.width(ToolbarLayout.DividerWidth - 2.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/**
 * The "more" button and its menu: the commands left out of the bars (the page grid, the imports
 * under floating tools), the view, the document (its name when [renamable]), the drawing mode and
 * about.
 */
@Composable
private fun MoreMenu(commands: EditorCommands, arrangement: ToolbarArrangement, showPageGrid: Boolean, renamable: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val floating = arrangement == ToolbarArrangement.FLOATING_TOOLS
    Box {
        DesignIconButton(
            icon = DrawViewIcons.MoreHorizontal,
            label = stringResource(Res.string.common_action_more_options),
            onClick = { expanded = true },
            tint = MaterialTheme.colorScheme.onSurface
        )
        CommandMenu(
            sections = listOf(
                listOfNotNull(commands.pageGrid.takeIf { !showPageGrid }, commands.addPage) +
                    (if (floating) listOf(commands.importPdf, commands.importImage, commands.importText) else emptyList()),
                listOfNotNull(commands.rename.takeIf { renamable }, commands.fitWidth, commands.stylusOnly, commands.snapping, commands.shapes, commands.documentBackground, commands.drawingMode),
                listOf(commands.about)
            ),
            expanded = expanded,
            onDismissRequest = { expanded = false }
        )
    }
}

/**
 * The tools of the editor: undo and redo, the drawing tools (their settings in a popover opened
 * by their button chosen again, or by a long press), the imports when [withImports].
 */
@Composable
internal fun RowScope.EditorTools(controller: DrawViewController, commands: EditorCommands, withImports: Boolean) {
    // Whether the document has PDF pages, as it changes (not at every change of the document)
    val hasPdfPages by controller.document.observe { hasPdfPages() }
    val pdfImportAvailable = controller.tileManager.tileRenderer.pdfAdapter != null
    // The tool whose settings are open (null = none)
    var openSettings by remember { mutableStateOf<Tool?>(null) }

    val barIcon = LocalToolSizes.current.barIcon
    BarButton(commands.undo, DrawViewIcons.Undo, iconSize = barIcon)
    BarButton(commands.redo, DrawViewIcons.Redo, iconSize = barIcon)
    ToolDivider()

    ToolWithSettings(controller, Tool.INK_PEN, commands.pen, DrawViewIcons.Pen, openSettings, { openSettings = it }) {
        InkSettings(controller, Tool.INK_PEN, stringResource(Res.string.tool_pen), InkPalette.inks, HybridScale(0.1f..15f, linearThreshold = 3f, linearProportion = 0.5f))
    }
    ToolWithSettings(controller, Tool.INK_HIGHLIGHTER, commands.highlighter, DrawViewIcons.Highlighter, openSettings, { openSettings = it }) {
        // Much wider range for the highlighters, linear only up to 8 pt
        InkSettings(controller, Tool.INK_HIGHLIGHTER, stringResource(Res.string.tool_highlighter), InkPalette.highlights, HybridScale(1f..30f, linearThreshold = 8f, linearProportion = 0.3f))
    }
    val shapeTarget = remember(controller) { ShapeToolTarget(controller) }
    ToolWithSettings(
        controller, Tool.SHAPES, commands.shapeTool, DrawViewIcons.Shapes, openSettings, { openSettings = it },
        iconContent = { ShapeToolIcon(controller.shapeInserter.style) }
    ) {
        ShapeSettings(shapeTarget, shapesTitle(), withHint = true)
    }
    if (pdfImportAvailable && (hasPdfPages || controller.selectedTool == Tool.TEXT_MARKUP)) {
        ToolWithSettings(controller, Tool.TEXT_MARKUP, commands.textMarkup, DrawViewIcons.TextMarkup, openSettings, { openSettings = it }) {
            MarkupSettings(controller)
        }
    }
    ToolWithSettings(controller, Tool.ERASER, commands.eraser, DrawViewIcons.Eraser, openSettings, { openSettings = it }) {
        EraserSettings(controller)
    }
    ToolWithSettings(controller, Tool.TEXT, commands.text, DrawViewIcons.Text, openSettings, { openSettings = it }) {
        TextToolSettings(controller)
    }
    ToolWithSettings(controller, Tool.LAZO, commands.lasso, DrawViewIcons.Lasso, openSettings, { openSettings = it }) {
        LassoSettings(controller)
    }
    ToolButton(
        onClick = {
            openSettings = null
            controller.selectTool(Tool.PAN)
        },
        selected = controller.selectedTool == Tool.PAN,
        command = commands.pan
    ) {
        Icon(DrawViewIcons.Pan, contentDescription = stringResource(Res.string.draw_toolbar_action_pan))
    }

    if (withImports) {
        ToolDivider()
        if (pdfImportAvailable) BarButton(commands.importPdf, DrawViewIcons.FileText, iconSize = barIcon)
        BarButton(commands.importImage, DrawViewIcons.Image, iconSize = barIcon)
    }

    // The palette asked by a gesture of the stylus: the settings of the tool chosen (not the
    // requests made before this bar was shown)
    val paletteHandled = remember { mutableIntStateOf(commands.paletteRequests) }
    LaunchedEffect(commands.paletteRequests) {
        if (commands.paletteRequests == paletteHandled.intValue) return@LaunchedEffect
        paletteHandled.intValue = commands.paletteRequests
        openSettings = controller.selectedTool.takeIf { it != Tool.PAN }
    }
}

/**
 * The button of [tool] and the popover of its [settings]: chosen by a tap, its settings opened by
 * a tap on it chosen or by a long press; the dot of its ink when it draws.
 */
@Composable
private fun ToolWithSettings(
    controller: DrawViewController,
    tool: Tool,
    command: Command,
    icon: ImageVector,
    openSettings: Tool?,
    onOpenSettings: (Tool?) -> Unit,
    iconContent: (@Composable () -> Unit)? = null,
    settings: @Composable ColumnScope.() -> Unit
) {
    val selected = controller.selectedTool == tool
    val ink = when (tool) {
        Tool.INK_PEN, Tool.INK_HIGHLIGHTER, Tool.TEXT_MARKUP -> controller.toolManager.activePreset(tool)?.let { Color(it.color).copy(alpha = 1f) }
        Tool.SHAPES -> Color(controller.shapeInserter.style.stroke)
        Tool.TEXT -> Color(controller.textDefaults.color)
        else -> null
    }
    ToolButton(
        onClick = {
            if (selected) {
                onOpenSettings(tool)
            } else {
                onOpenSettings(null)
                controller.selectTool(tool)
            }
        },
        onLongClick = {
            controller.selectTool(tool)
            onOpenSettings(tool)
        },
        selected = selected,
        command = command,
        ink = ink,
        expanded = openSettings == tool,
        onDismissRequest = { onOpenSettings(null) },
        dropDownMenu = settings
    ) {
        if (iconContent != null) iconContent() else Icon(icon, contentDescription = command.label())
    }
}

/**
 * The settings of an ink tool (the pen, the highlighter): the size, the presets (tap to choose
 * one, "+" to add one), the inks of [swatches] and the color wheel; they change the preset chosen.
 */
@Composable
internal fun ColumnScope.InkSettings(controller: DrawViewController, tool: Tool, title: String, swatches: List<Color>, scale: HybridScale) {
    val toolManager = controller.toolManager
    val presets = toolManager.presetsOf(tool)
    val index = toolManager.selectedPresetIndex(tool)
    val active = toolManager.activePreset(tool) ?: return
    val color = Color(active.color)
    var wheelOpen by remember { mutableStateOf(false) }
    fun update(transform: (io.github.valerioisufi.drawview.canvas.controller.ToolPreset) -> io.github.valerioisufi.drawview.canvas.controller.ToolPreset) {
        toolManager.presetsOf(tool).getOrNull(index)?.let { toolManager.updatePreset(tool, index, transform(it)) }
    }
    val sizePt = active.sizeMm.mm.pt
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        PopoverTitle(title) { StrokePreview(color.copy(alpha = maxOf(color.alpha, 0.6f)), width = (sizePt * 1.2f).coerceIn(1.5f, 14f).dp) }
        SizeSetting(
            label = stringResource(Res.string.tool_settings_size),
            value = sizePt,
            unit = "pt",
            scale = scale,
            onValueChange = { newPt -> update { it.copy(sizeMm = newPt.pt.mm) } }
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingLabel(stringResource(Res.string.tool_settings_presets), modifier = Modifier.weight(1f))
                if (presets.size > 1) {
                    DesignIconButton(
                        icon = DrawViewIcons.Trash,
                        label = stringResource(Res.string.tool_settings_delete_preset),
                        onClick = { toolManager.removePreset(tool, index) },
                        iconSize = 18.dp
                    )
                }
            }
            // Up to three presets share the width with "+", more are scrolled
            val fits = presets.size <= 3
            Row(
                modifier = if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEachIndexed { i, preset ->
                    val presetPt = preset.sizeMm.mm.pt
                    PresetCard(
                        color = Color(preset.color).let { it.copy(alpha = maxOf(it.alpha, 0.6f)) },
                        strokeWidth = (presetPt * 1.2f).coerceIn(1.5f, 10f).dp,
                        label = "${formatDecimal(presetPt, 1)} pt",
                        selected = i == index,
                        onClick = { controller.selectTool(tool, i) },
                        modifier = if (fits) Modifier.weight(1f) else Modifier.width(78.dp)
                    )
                }
                AddPresetCard(
                    label = stringResource(Res.string.draw_toolbar_action_add_preset),
                    onClick = {
                        val newIndex = toolManager.addPreset(tool, active.copy())
                        controller.selectTool(tool, newIndex)
                    },
                    modifier = Modifier.width(44.dp)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingLabel(stringResource(Res.string.tool_settings_ink))
            // The highlighters keep their transparency
            ColorSwatches(
                colors = swatches,
                selected = color.copy(alpha = 1f),
                onSelect = { swatch -> update { it.copy(color = swatch.copy(alpha = color.alpha).toArgb()) } },
                onCustom = { wheelOpen = true }
            )
        }
        SwitchSetting(
            title = stringResource(Res.string.command_shapes),
            description = stringResource(Res.string.tool_settings_shapes_hint),
            checked = controller.shapeSnapper.isEnabled,
            onCheckedChange = controller.shapeSnapper::updateEnabled
        )
    }
    if (wheelOpen) {
        TextColorPickerDialog(
            initialColor = color.copy(alpha = 1f),
            onDismissRequest = { wheelOpen = false },
            onColorConfirmed = { picked -> update { it.copy(color = picked.copy(alpha = color.alpha).toArgb()) } }
        )
    }
}

/** The settings of the eraser: its size (in millimeters) and what it erases. */
@Composable
internal fun ColumnScope.EraserSettings(controller: DrawViewController) {
    val toolManager = controller.toolManager
    val index = toolManager.selectedPresetIndex(Tool.ERASER)
    val preset = toolManager.activePreset(Tool.ERASER)
    val sizeMm = preset?.sizeMm ?: 8f
    // Large erasers, linear precision only for small corrections
    val scale = remember { HybridScale(2f..50f, linearThreshold = 10f, linearProportion = 0.25f) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        PopoverTitle(stringResource(Res.string.tool_eraser)) { EraserPreview(scale.position(sizeMm)) }
        SizeSetting(
            label = stringResource(Res.string.draw_toolbar_label_eraser_size),
            value = sizeMm,
            unit = "mm",
            scale = scale,
            onValueChange = { newMm -> preset?.let { toolManager.updatePreset(Tool.ERASER, index, it.copy(sizeMm = newMm)) } }
        )
        SettingHint(stringResource(Res.string.tool_settings_eraser_hint))
    }
}

/** The settings of the lasso: what it selects. */
@Composable
internal fun ColumnScope.LassoSettings(controller: DrawViewController) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        PopoverTitle(stringResource(Res.string.draw_toolbar_title_lasso_mode))
        OptionCard(
            icon = DrawViewIcons.Lasso,
            title = stringResource(Res.string.draw_toolbar_option_select_all),
            description = stringResource(Res.string.tool_settings_lasso_all_hint),
            selected = controller.lassoMode == LassoMode.ALL,
            onClick = { controller.lassoMode = LassoMode.ALL }
        )
        OptionCard(
            icon = DrawViewIcons.Image,
            title = stringResource(Res.string.draw_toolbar_option_images_only),
            description = stringResource(Res.string.tool_settings_lasso_images_hint),
            selected = controller.lassoMode == LassoMode.IMAGES_ONLY,
            onClick = { controller.lassoMode = LassoMode.IMAGES_ONLY }
        )
    }
}

/**
 * The settings of the text tool, the style of a new text (size, color, alignment), with the
 * controls of the formatting bar.
 */
@Composable
internal fun ColumnScope.TextToolSettings(controller: DrawViewController) {
    val defaults = remember(controller) { TextDefaultsTarget(controller) }
    var wheelOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        PopoverTitle(stringResource(Res.string.draw_toolbar_title_text_settings))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingLabel(stringResource(Res.string.draw_toolbar_label_font_size), modifier = Modifier.weight(1f))
            FontSizeControl(fontSizePt = defaults.fontSize, onFontSizeChange = defaults::changeFontSize)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingLabel(stringResource(Res.string.draw_toolbar_label_text_color))
            ColorSwatches(
                colors = InkPalette.inks.take(5),
                selected = Color(defaults.color),
                onSelect = { defaults.changeColor(it.toArgb()) },
                onCustom = { wheelOpen = true }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingLabel(stringResource(Res.string.text_editor_action_alignment), modifier = Modifier.weight(1f))
            TextAlignmentButtons(defaults, commands = null)
        }
        SettingHint(stringResource(Res.string.tool_settings_text_hint))
    }
    if (wheelOpen) {
        TextColorPickerDialog(
            initialColor = Color(defaults.color),
            onDismissRequest = { wheelOpen = false },
            onColorConfirmed = { defaults.changeColor(it.toArgb()) }
        )
    }
}

/** The style of a new text, as a target of the formatting controls. */
private class TextDefaultsTarget(private val controller: DrawViewController) : TextFormatTarget {
    override val fontSize: Float get() = controller.textDefaults.fontSize
    override val color: Int get() = controller.textDefaults.color
    override val alignment: TextAlignment get() = controller.textDefaults.alignment
    override fun changeFontSize(size: Float) = controller.updateTextDefaults(controller.textDefaults.copy(fontSize = size))
    override fun changeColor(argb: Int) = controller.updateTextDefaults(controller.textDefaults.copy(color = argb))
    override fun changeAlignment(newAlignment: TextAlignment) = controller.updateTextDefaults(controller.textDefaults.copy(alignment = newAlignment))
}

/**
 * The settings of the marks of the text of the PDFs: the presets (tap one to mark with it, "+" to
 * add one), the kind and the color of the one chosen.
 */
@Composable
internal fun ColumnScope.MarkupSettings(controller: DrawViewController) {
    val toolManager = controller.toolManager
    val tool = Tool.TEXT_MARKUP
    val presets = toolManager.presetsOf(tool)
    val index = toolManager.selectedPresetIndex(tool)
    val active = toolManager.activePreset(tool) ?: return
    fun update(kind: TextMarkupKind = active.markup, color: Color = Color(active.color)) {
        toolManager.updatePreset(tool, index, active.copy(markup = kind, color = color.copy(alpha = 1f).toArgb()))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        PopoverTitle(stringResource(Res.string.draw_toolbar_action_text_markup)) {
            if (presets.size > 1) {
                DesignIconButton(
                    icon = DrawViewIcons.Trash,
                    label = stringResource(Res.string.tool_settings_delete_preset),
                    onClick = { toolManager.removePreset(tool, index) },
                    iconSize = 18.dp
                )
            }
        }
        SettingHint(stringResource(Res.string.tool_settings_markup_hint))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            presets.forEachIndexed { i, preset ->
                TextMarkupPresetButton(kind = preset.markup, color = Color(preset.color), isSelected = i == index, onClick = { controller.selectTool(tool, i) })
            }
            val add = stringResource(Res.string.draw_toolbar_action_add_preset)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(role = Role.Button) { controller.selectTool(tool, toolManager.addPreset(tool, active.copy())) }
                    .semantics { contentDescription = add }
            ) {
                Icon(DrawViewIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (kind in TextMarkupKind.entries) {
                    val selected = kind == active.markup
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable(role = Role.RadioButton) { update(kind = kind) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(30.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        ) {
                            TextMarkupSample(kind, Color(active.color))
                        }
                        Text(
                            textMarkupKindLabel(kind),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) Icon(DrawViewIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                    }
                }
                ColorSwatches(
                    colors = if (active.markup == TextMarkupKind.HIGHLIGHT) InkPalette.highlights else InkPalette.inks.take(5),
                    selected = Color(active.color),
                    onSelect = { update(color = it) },
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                )
            }
        }
    }
}

/**
 * The tools floating at the bottom of the canvas (a phone, a short window): undo, redo and the
 * drawing tools, scrolled when they do not fit; the imports are in the "more" menu.
 */
@Composable
internal fun FloatingTools(controller: DrawViewController, commands: EditorCommands, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = modifier
            .elevation(DrawViewElevation.Level2, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .height(ToolbarLayout.FloatingHeight)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        EditorTools(controller, commands, withImports = false)
    }
}

/** Where the tools of a phone end: the page and the zoom, "3 / 12 · 100%", on the inverse surface. */
@Composable
internal fun PageIndicator(controller: DrawViewController, withZoom: Boolean, modifier: Modifier = Modifier) {
    val state by remember(controller) {
        androidx.compose.runtime.derivedStateOf {
            controller.viewport.camera.moveCount
            controller.version
            val page = (controller.viewport.mostVisiblePageIndex() ?: 0) + 1
            val zoom = kotlin.math.round(controller.viewport.camera.getCurrentScale() * 100f).toInt()
            Triple(page, controller.document.pages.size, zoom)
        }
    }
    val (page, total, zoom) = state
    val shape = CircleShape
    Row(
        modifier = modifier
            .elevation(DrawViewElevation.Level2, shape)
            .background(MaterialTheme.colorScheme.inverseSurface, shape)
            .padding(horizontal = if (withZoom) 16.dp else 14.dp, vertical = if (withZoom) 8.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("$page / $total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inverseOnSurface)
        if (withZoom) {
            Box(modifier = Modifier.size(3.dp).background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.6f), CircleShape))
            Text("$zoom%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f))
        }
    }
}

/** An icon button of the tools of an extension (e.g. the assistant), tonal when [active]. */
@Composable
public fun EditorActionButton(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false, highlighted: Boolean = false) {
    DesignIconButton(
        icon = icon,
        label = label,
        onClick = onClick,
        style = if (active || highlighted) IconButtonStyle.Tonal else IconButtonStyle.Standard,
        selected = highlighted,
        tint = if (active || highlighted) Color.Unspecified else MaterialTheme.colorScheme.onSurface
    )
}
