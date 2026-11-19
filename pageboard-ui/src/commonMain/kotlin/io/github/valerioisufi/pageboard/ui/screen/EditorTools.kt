package io.github.valerioisufi.pageboard.ui.screen

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.canvas.observe
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.model.geometry.pt
import io.github.valerioisufi.pageboard.ui.command.Command
import io.github.valerioisufi.pageboard.ui.components.HybridScale
import io.github.valerioisufi.pageboard.ui.components.LocalToolSizes
import io.github.valerioisufi.pageboard.ui.components.ToolButton
import io.github.valerioisufi.pageboard.ui.generated.resources.*
import io.github.valerioisufi.pageboard.ui.theme.DrawViewIcons
import io.github.valerioisufi.pageboard.ui.theme.InkPalette
import org.jetbrains.compose.resources.stringResource

// The tools of the editor in its bar, each one with its settings

/**
 * The tools of the editor: undo and redo when [withHistory] (else the bar shows them apart, see
 * [EditorHistory]), the drawing tools (their settings in a popover opened by their button chosen
 * again, or by a long press), the imports when [withImports].
 */
@Composable
internal fun RowScope.EditorTools(controller: DrawViewController, commands: EditorCommands, withImports: Boolean, withHistory: Boolean = true) {
    // Whether the document has PDF pages, as it changes (not at every change of the document)
    val hasPdfPages by controller.document.observe { hasPdfPages() }
    val pdfImportAvailable = controller.tileManager.tileRenderer.pdfAdapter != null
    // The tool whose settings are open (null = none)
    var openSettings by remember { mutableStateOf<Tool?>(null) }

    val barIcon = LocalToolSizes.current.barIcon
    if (withHistory) {
        EditorHistory(commands)
        ToolDivider()
    }

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

/** Undo and redo, the first of the tools. */
@Composable
internal fun EditorHistory(commands: EditorCommands) {
    val barIcon = LocalToolSizes.current.barIcon
    BarButton(commands.undo, DrawViewIcons.Undo, iconSize = barIcon)
    BarButton(commands.redo, DrawViewIcons.Redo, iconSize = barIcon)
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
