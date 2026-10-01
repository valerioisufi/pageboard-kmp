package io.github.valerioisufi.drawview.data

import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.core.controller.DrawPreferences
import io.github.valerioisufi.drawview.core.controller.ToolManager
import io.github.valerioisufi.drawview.core.controller.ToolPreset
import io.github.valerioisufi.drawview.core.engine.InkStrokeEngine
import io.github.valerioisufi.drawview.core.model.BrushConfig
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.InputToolType
import io.github.valerioisufi.drawview.core.model.StrokeMesh
import io.github.valerioisufi.drawview.core.model.StrokePoint
import io.github.valerioisufi.drawview.core.model.StrokeTool
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.data.db.BrushSettingsData

/**
 * Rebuilds an editor stroke (mesh included) from its persisted form: input points and brush.
 */
internal fun restoreStroke(
    points: List<StrokePoint>,
    familyName: String,
    toolTypeName: String,
    size: Float,
    color: Int,
    zIndex: Int,
    dbId: Int = 0,
    id: String? = null
): DrawStroke {
    // Rows written by the first KMP builds stored the brush family in both columns
    val family = StrokeTool.fromName(familyName) ?: StrokeTool.fromName(toolTypeName) ?: StrokeTool.PRESSURE_PEN
    val toolType = InputToolType.fromName(toolTypeName)
    val brush = BrushConfig(
        tool = family,
        size = size,
        color = Color(color),
        // Fine modelling tolerance for the persisted strokes, as in the original app
        epsilon = (size * 0.01f).coerceIn(0.005f, 0.05f)
    )
    val mesh = InkStrokeEngine.buildMesh(points, brush, toolType) ?: StrokeMesh(
        positions = floatArrayOf(),
        indices = shortArrayOf(),
        color = brush.color,
        tool = brush.tool,
        size = brush.size,
        points = points,
        inputToolType = toolType
    )
    val bounds = mesh.computeBounds()
    return if (id != null) {
        DrawStroke(id = id, mesh = mesh, brushConfig = brush, bounds = bounds, zIndex = zIndex, dbId = dbId)
    } else {
        DrawStroke(mesh = mesh, brushConfig = brush, bounds = bounds, zIndex = zIndex, dbId = dbId)
    }
}

internal fun List<ToolPreset>.toBrushSettings(): List<BrushSettingsData> =
    map { BrushSettingsData(it.sizeMm, it.color, it.family.name) }

/**
 * Maps the persisted preferences to the editor model (the default presets replace empty lists).
 */
internal fun buildDrawPreferences(
    isStylusOnlyMode: Boolean,
    lastSelectedTool: String,
    defaultTextFontSize: Float,
    defaultTextColor: Int,
    penPresets: List<BrushSettingsData>,
    highlighterPresets: List<BrushSettingsData>,
    eraserPresets: List<BrushSettingsData>,
    lazoPresets: List<BrushSettingsData>
): DrawPreferences {
    val defaults = ToolManager.defaultPresets()
    fun map(tool: Tool, data: List<BrushSettingsData>, fallbackFamily: StrokeTool): List<ToolPreset> =
        data.map { ToolPreset(it.sizeMm, it.color, StrokeTool.fromName(it.family) ?: fallbackFamily) }
            .ifEmpty { defaults.getValue(tool) }

    return DrawPreferences(
        isStylusOnlyMode = isStylusOnlyMode,
        lastSelectedTool = Tool.entries.firstOrNull { it.name == lastSelectedTool } ?: Tool.INK_PEN,
        defaultTextFontSize = defaultTextFontSize,
        defaultTextColor = defaultTextColor,
        presets = mapOf(
            Tool.INK_PEN to map(Tool.INK_PEN, penPresets, StrokeTool.PRESSURE_PEN),
            Tool.INK_HIGHLIGHTER to map(Tool.INK_HIGHLIGHTER, highlighterPresets, StrokeTool.HIGHLIGHTER),
            Tool.ERASER to map(Tool.ERASER, eraserPresets, StrokeTool.ERASER),
            Tool.LAZO to map(Tool.LAZO, lazoPresets, StrokeTool.LASSO)
        )
    )
}
