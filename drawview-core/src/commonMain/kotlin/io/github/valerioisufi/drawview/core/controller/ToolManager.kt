package io.github.valerioisufi.drawview.core.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.core.model.BrushConfig
import io.github.valerioisufi.drawview.core.model.StrokeTool
import io.github.valerioisufi.drawview.core.model.Tool

/**
 * A preset of a drawing tool.
 *
 * @property sizeMm tip size in millimeters.
 * @property color ARGB color.
 */
data class ToolPreset(
    val sizeMm: Float,
    val color: Int,
    val family: StrokeTool
)

/**
 * Global user preferences relevant to the editor.
 */
data class DrawPreferences(
    val isStylusOnlyMode: Boolean = false,
    val lastSelectedTool: Tool = Tool.INK_PEN,
    val defaultTextFontSize: Float = 16f,
    val defaultTextColor: Int = 0xFF000000.toInt(),
    val presets: Map<Tool, List<ToolPreset>> = ToolManager.defaultPresets()
)

/**
 * Manages the active tool and the presets (size, color, brush family) of every drawing tool.
 *
 * All the state is backed by Compose snapshot state, so the UI observes every change.
 */
class ToolManager(
    private val onPresetsChanged: (Tool, List<ToolPreset>) -> Unit = { _, _ -> },
    private val onToolSelected: (Tool) -> Unit = {}
) {
    var selectedTool: Tool by mutableStateOf(Tool.INK_PEN)
        private set

    private val presets: Map<Tool, SnapshotStateList<ToolPreset>> = PRESET_TOOLS.associateWith { tool ->
        mutableStateListOf<ToolPreset>().apply { addAll(defaultPresets().getValue(tool)) }
    }
    private val selectedIndexes = mutableStateMapOf<Tool, Int>().apply {
        PRESET_TOOLS.forEach { put(it, 0) }
    }

    /** Presets of [tool] (empty for tools without presets). */
    fun presetsOf(tool: Tool): List<ToolPreset> = presets[tool] ?: emptyList()

    /** Index of the selected preset of [tool]. */
    fun selectedPresetIndex(tool: Tool): Int =
        (selectedIndexes[tool] ?: 0).coerceIn(0, (presetsOf(tool).size - 1).coerceAtLeast(0))

    /** The selected preset of [tool], or `null` for tools without presets. */
    fun activePreset(tool: Tool = selectedTool): ToolPreset? = presetsOf(tool).getOrNull(selectedPresetIndex(tool))

    val isPanTool: Boolean get() = selectedTool == Tool.PAN
    val isTextTool: Boolean get() = selectedTool == Tool.TEXT
    val isLassoTool: Boolean get() = selectedTool == Tool.LAZO
    val isEraserTool: Boolean get() = selectedTool == Tool.ERASER
    val isInkTool: Boolean get() = selectedTool == Tool.INK_PEN || selectedTool == Tool.INK_HIGHLIGHTER

    fun selectTool(tool: Tool, presetIndex: Int? = null) {
        val changed = selectedTool != tool
        selectedTool = tool
        if (presetIndex != null && tool in presets) {
            selectedIndexes[tool] = presetIndex.coerceIn(0, (presetsOf(tool).size - 1).coerceAtLeast(0))
        }
        if (changed) onToolSelected(tool)
    }

    fun updatePreset(tool: Tool, index: Int, preset: ToolPreset) {
        val list = presets[tool] ?: return
        if (index !in list.indices) return
        list[index] = preset
        onPresetsChanged(tool, list.toList())
    }

    fun addPreset(tool: Tool, preset: ToolPreset): Int {
        val list = presets[tool] ?: return -1
        list.add(preset)
        selectedIndexes[tool] = list.size - 1
        onPresetsChanged(tool, list.toList())
        return list.size - 1
    }

    fun removePreset(tool: Tool, index: Int) {
        val list = presets[tool] ?: return
        if (list.size <= 1 || index !in list.indices) return
        list.removeAt(index)
        val current = selectedIndexes[tool] ?: 0
        if (current >= list.size) selectedIndexes[tool] = list.size - 1
        else if (current > index) selectedIndexes[tool] = current - 1
        onPresetsChanged(tool, list.toList())
    }

    /**
     * Applies the persisted preferences (without echoing them back to the persistence layer).
     */
    fun applyPreferences(preferences: DrawPreferences) {
        for (tool in PRESET_TOOLS) {
            val list = presets.getValue(tool)
            val stored = preferences.presets[tool].orEmpty().ifEmpty { defaultPresets().getValue(tool) }
            if (list.toList() != stored) {
                list.clear()
                list.addAll(stored)
            }
            selectedIndexes[tool] = (selectedIndexes[tool] ?: 0).coerceIn(0, list.size - 1)
        }
        selectedTool = preferences.lastSelectedTool
    }

    /**
     * Brush used to model the strokes of [tool] with its active preset.
     *
     * @param pixelsPerMm current zoom level: the modelling tolerance is kept below a tenth of a
     * screen pixel, as in the original implementation.
     */
    fun brushFor(tool: Tool, pixelsPerMm: Float): BrushConfig? {
        val preset = activePreset(tool) ?: return null
        val epsilon = (0.1f / pixelsPerMm.coerceAtLeast(0.01f)).coerceIn(0.001f, 0.5f)
        val family = when (tool) {
            Tool.ERASER -> StrokeTool.ERASER
            Tool.LAZO -> StrokeTool.LASSO
            else -> preset.family
        }
        return BrushConfig(
            tool = family,
            size = preset.sizeMm.coerceAtLeast(epsilon * 2f),
            color = Color(preset.color),
            epsilon = epsilon
        )
    }

    companion object {
        /** Tools that own presets. */
        val PRESET_TOOLS = listOf(Tool.INK_PEN, Tool.INK_HIGHLIGHTER, Tool.ERASER, Tool.LAZO)

        fun defaultPresets(): Map<Tool, List<ToolPreset>> = mapOf(
            Tool.INK_PEN to listOf(
                ToolPreset(0.5f, 0xFF000000.toInt(), StrokeTool.PRESSURE_PEN),
                ToolPreset(1.0f, 0xFF0000FF.toInt(), StrokeTool.PRESSURE_PEN),
                ToolPreset(2.0f, 0xFFFF0000.toInt(), StrokeTool.PRESSURE_PEN)
            ),
            Tool.INK_HIGHLIGHTER to listOf(
                ToolPreset(4.0f, 0x40FFFF00, StrokeTool.HIGHLIGHTER),
                ToolPreset(4.0f, 0x4000FF00, StrokeTool.HIGHLIGHTER)
            ),
            Tool.ERASER to listOf(
                ToolPreset(8.0f, 0xC8FF8DA1.toInt(), StrokeTool.ERASER)
            ),
            Tool.LAZO to listOf(
                ToolPreset(0.5f, 0xFF8799B2.toInt(), StrokeTool.LASSO)
            )
        )
    }
}
