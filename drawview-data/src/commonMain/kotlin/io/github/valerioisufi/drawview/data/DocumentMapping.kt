package io.github.valerioisufi.drawview.data

import io.github.valerioisufi.drawview.core.controller.DrawPreferences
import io.github.valerioisufi.drawview.core.controller.ToolManager
import io.github.valerioisufi.drawview.core.controller.ToolPreset
import io.github.valerioisufi.drawview.core.model.TextMarkupKind
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.data.db.BrushSettingsData
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.text.TextDefaults

/**
 * Persisted form of the presets of [tool]: the family of a preset of the text markup tool is the
 * kind of its marks.
 */
internal fun List<ToolPreset>.toBrushSettings(tool: Tool): List<BrushSettingsData> =
    map { BrushSettingsData(it.sizeMm, it.color, if (tool == Tool.TEXT_MARKUP) it.markup.name else it.family.name) }

/**
 * Maps the persisted preferences to the editor model (the default presets replace empty lists).
 */
internal fun buildDrawPreferences(
    isStylusOnlyMode: Boolean,
    lastSelectedTool: String,
    defaultTextFontSize: Float,
    defaultTextColor: Int,
    defaultTextAlignment: String,
    isSnapEnabled: Boolean,
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
        textDefaults = TextDefaults(
            fontSize = defaultTextFontSize,
            color = defaultTextColor,
            alignment = TextAlignment.fromName(defaultTextAlignment) ?: TextAlignment.START
        ),
        isSnapEnabled = isSnapEnabled,
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
