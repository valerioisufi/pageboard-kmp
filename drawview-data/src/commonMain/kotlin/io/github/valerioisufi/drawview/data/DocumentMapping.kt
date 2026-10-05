package io.github.valerioisufi.drawview.data

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.core.controller.DrawPreferences
import io.github.valerioisufi.drawview.core.controller.ToolManager
import io.github.valerioisufi.drawview.core.controller.ToolPreset
import io.github.valerioisufi.drawview.core.engine.InkStrokeEngine
import io.github.valerioisufi.drawview.core.model.BrushConfig
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup
import io.github.valerioisufi.drawview.core.model.InputToolType
import io.github.valerioisufi.drawview.core.model.StrokeMesh
import io.github.valerioisufi.drawview.core.model.StrokePoint
import io.github.valerioisufi.drawview.core.model.StrokeTool
import io.github.valerioisufi.drawview.core.model.TextMarkupKind
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.core.model.newElementId
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
    id: String = newElementId()
): DrawStroke {
    val family = StrokeTool.fromName(familyName) ?: StrokeTool.PRESSURE_PEN
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
    return DrawStroke(id = id, mesh = mesh, brushConfig = brush, bounds = bounds, zIndex = zIndex)
}

/**
 * Persisted form of the presets of [tool]: the family of a preset of the text markup tool is the
 * kind of its marks.
 */
internal fun List<ToolPreset>.toBrushSettings(tool: Tool): List<BrushSettingsData> =
    map { BrushSettingsData(it.sizeMm, it.color, if (tool == Tool.TEXT_MARKUP) it.markup.name else it.family.name) }

/** The lines of a mark of the text of a PDF as a flat list: left, top, right, bottom of each line. */
internal fun DrawTextMarkup.flatRects(): List<Float> = rects.flatMap { listOf(it.left, it.top, it.right, it.bottom) }

/**
 * Rebuilds a mark of the text of a PDF from its persisted form ([rects]: see [flatRects]).
 */
internal fun restoreTextMarkup(
    resourceId: String,
    pdfPageIndex: Int,
    pdfWidthPt: Float,
    pdfHeightPt: Float,
    startIndex: Int,
    endIndex: Int,
    text: String,
    rects: List<Float>,
    kind: String,
    color: Int,
    zIndex: Int,
    id: String = newElementId()
) = DrawTextMarkup(
    id = id,
    resourceId = resourceId,
    pdfPageIndex = pdfPageIndex,
    pdfWidthPt = pdfWidthPt,
    pdfHeightPt = pdfHeightPt,
    startIndex = startIndex,
    endIndex = endIndex,
    text = text,
    rects = rects.chunked(4).filter { it.size == 4 }.map { Rect(it[0], it[1], it[2], it[3]) },
    kind = TextMarkupKind.fromName(kind) ?: TextMarkupKind.HIGHLIGHT,
    color = color,
    zIndex = zIndex
)

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
    lazoPresets: List<BrushSettingsData>,
    markupPresets: List<BrushSettingsData>
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
            Tool.TEXT_MARKUP to markupPresets.map {
                ToolPreset(it.sizeMm, it.color, StrokeTool.HIGHLIGHTER, TextMarkupKind.fromName(it.family) ?: TextMarkupKind.HIGHLIGHT)
            }.ifEmpty { defaults.getValue(Tool.TEXT_MARKUP) },
            Tool.ERASER to map(Tool.ERASER, eraserPresets, StrokeTool.ERASER),
            Tool.LAZO to map(Tool.LAZO, lazoPresets, StrokeTool.LASSO)
        )
    )
}
