package io.github.valerioisufi.pageboard.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.pageboard.canvas.tool.Tool
import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.TextMarkupKind
import io.github.valerioisufi.pageboard.model.text.TextDefaults
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A preset of a drawing tool.
 *
 * @property sizeMm tip size in millimeters.
 * @property color ARGB color.
 * @property markup how the text is marked ([Tool.TEXT_MARKUP] only).
 */
@Serializable
public data class ToolPreset(
    val sizeMm: Float,
    val color: Int,
    val family: StrokeTool,
    val markup: TextMarkupKind = TextMarkupKind.HIGHLIGHT
)

/**
 * The preferences of the user the editor keeps (see [PageboardController.preferences]): written as
 * text ([encode]) by whoever stores them.
 */
@Serializable
public data class EditorPreferences(
    val isStylusOnlyMode: Boolean = false,
    val lastSelectedTool: Tool = Tool.INK_PEN,
    val textDefaults: TextDefaults = TextDefaults(),
    /** Whether the boxes snap to what is around them (see `SnapManager`). */
    val isSnapEnabled: Boolean = true,
    val presets: Map<Tool, List<ToolPreset>> = ToolManager.defaultPresets(),
    /** Whether a stroke held still at its end becomes its shape (see `ShapeSnapper`). */
    val isShapeRecognitionEnabled: Boolean = true,
    /** What the shapes tool draws (see `ShapeInserter`). */
    val shapeTool: ShapeToolPreferences = ShapeToolPreferences(),
    /** Whether the text box asks the extensions what may follow the caret (`docs/plans/writing-suggestions.md`). */
    val areTextSuggestionsEnabled: Boolean = true,
    /** Whether holding a stroke still converts it directly into an editable shape element instead of a stroke. */
    val snapToEditableShape: Boolean = false,
    /** Whether wheel and trackpad scrolling locks onto the predominant axis at the start of a gesture. */
    val isScrollAxisLockEnabled: Boolean = true,
    /** Whether checking a checklist item automatically moves completed items to the bottom of the list. */
    val moveCompletedChecklistsToBottom: Boolean = false
) {
    /** These preferences as text (JSON), read again by [decode]. */
    public fun encode(): String = json.encodeToString(serializer(), this)

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The preferences written by [encode], or `null` when [text] cannot be read. */
        public fun decode(text: String): EditorPreferences? = try {
            json.decodeFromString(serializer(), text)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Manages the active tool and the presets (size, color, brush family) of every drawing tool.
 *
 * All the state is backed by Compose snapshot state, so the UI observes every change.
 */
public class ToolManager internal constructor(
    private val onPresetsChanged: (Tool, List<ToolPreset>) -> Unit = { _, _ -> },
    private val onToolSelected: (Tool) -> Unit = {}
) {
    public var selectedTool: Tool by mutableStateOf(Tool.INK_PEN)
        private set

    private val presets: Map<Tool, SnapshotStateList<ToolPreset>> = PRESET_TOOLS.associateWith { tool ->
        mutableStateListOf<ToolPreset>().apply { addAll(defaultPresets().getValue(tool)) }
    }
    private val selectedIndexes = mutableStateMapOf<Tool, Int>().apply {
        PRESET_TOOLS.forEach { put(it, 0) }
    }

    /** Presets of [tool] (empty for tools without presets). */
    public fun presetsOf(tool: Tool): List<ToolPreset> = presets[tool] ?: emptyList()

    /** Index of the selected preset of [tool]. */
    public fun selectedPresetIndex(tool: Tool): Int =
        (selectedIndexes[tool] ?: 0).coerceIn(0, (presetsOf(tool).size - 1).coerceAtLeast(0))

    /** The selected preset of [tool], or `null` for tools without presets. */
    public fun activePreset(tool: Tool = selectedTool): ToolPreset? = presetsOf(tool).getOrNull(selectedPresetIndex(tool))

    internal val isPanTool: Boolean get() = selectedTool == Tool.PAN
    internal val isLassoTool: Boolean get() = selectedTool == Tool.LAZO
    internal val isEraserTool: Boolean get() = selectedTool == Tool.ERASER
    internal val isInkTool: Boolean get() = selectedTool == Tool.INK_PEN || selectedTool == Tool.INK_HIGHLIGHTER

    /** The tool chosen before the selected one (the Apple Pencil switches back to it). */
    public var previousTool: Tool by mutableStateOf(Tool.INK_PEN)
        private set

    public fun selectTool(tool: Tool, presetIndex: Int? = null) {
        val changed = selectedTool != tool
        if (changed) previousTool = selectedTool
        selectedTool = tool
        if (presetIndex != null && tool in presets) {
            selectedIndexes[tool] = presetIndex.coerceIn(0, (presetsOf(tool).size - 1).coerceAtLeast(0))
        }
        if (changed) onToolSelected(tool)
    }

    public fun updatePreset(tool: Tool, index: Int, preset: ToolPreset) {
        val list = presets[tool] ?: return
        if (index !in list.indices) return
        list[index] = preset
        onPresetsChanged(tool, list.toList())
    }

    public fun addPreset(tool: Tool, preset: ToolPreset): Int {
        val list = presets[tool] ?: return -1
        list.add(preset)
        selectedIndexes[tool] = list.size - 1
        onPresetsChanged(tool, list.toList())
        return list.size - 1
    }

    public fun removePreset(tool: Tool, index: Int) {
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
    public fun applyPreferences(preferences: EditorPreferences) {
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
    internal fun brushFor(tool: Tool, pixelsPerMm: Float): BrushConfig? {
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
            color = preset.color,
            epsilon = epsilon
        )
    }

    public companion object {
        /** Tools that own presets. */
        internal val PRESET_TOOLS = listOf(Tool.INK_PEN, Tool.INK_HIGHLIGHTER, Tool.TEXT_MARKUP, Tool.ERASER, Tool.LAZO)

        internal fun defaultPresets(): Map<Tool, List<ToolPreset>> = mapOf(
            Tool.INK_PEN to listOf(
                // The inks of the design (black, blue, red)
                ToolPreset(0.5f, 0xFF1A1C18.toInt(), StrokeTool.PRESSURE_PEN),
                ToolPreset(1.0f, 0xFF1F5FBF.toInt(), StrokeTool.PRESSURE_PEN),
                ToolPreset(2.0f, 0xFFD23B3B.toInt(), StrokeTool.PRESSURE_PEN)
            ),
            Tool.INK_HIGHLIGHTER to listOf(
                ToolPreset(4.0f, 0x40F6E94A, StrokeTool.HIGHLIGHTER),
                ToolPreset(4.0f, 0x40A8E86B, StrokeTool.HIGHLIGHTER)
            ),
            // Opaque colors: the highlights multiply the colors of the PDF
            Tool.TEXT_MARKUP to listOf(
                ToolPreset(0f, 0xFFF6E94A.toInt(), StrokeTool.HIGHLIGHTER, TextMarkupKind.HIGHLIGHT),
                ToolPreset(0f, 0xFFA8E86B.toInt(), StrokeTool.HIGHLIGHTER, TextMarkupKind.HIGHLIGHT),
                ToolPreset(0f, 0xFFD23B3B.toInt(), StrokeTool.HIGHLIGHTER, TextMarkupKind.UNDERLINE)
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
