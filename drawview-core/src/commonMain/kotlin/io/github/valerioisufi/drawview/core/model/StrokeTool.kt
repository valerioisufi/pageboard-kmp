package io.github.valerioisufi.drawview.core.model

/**
 * Brush families supported by the stroke engine. [nativeId] matches `InkBrushFamily` in
 * `native/bridge/ink_bridge.h`.
 */
enum class StrokeTool(val nativeId: Int) {
    PRESSURE_PEN(0),
    MARKER(1),
    HIGHLIGHTER(2),
    DASHED_LINE(3),
    ERASER(4),
    LASSO(5);

    /**
     * Brush family actually used to model strokes for this tool: the eraser trail is modeled
     * as a marker and the lasso as a dashed line.
     */
    val brushFamily: StrokeTool
        get() = when (this) {
            ERASER -> MARKER
            LASSO -> DASHED_LINE
            else -> this
        }

    companion object {
        fun fromName(name: String?): StrokeTool? = entries.firstOrNull { it.name == name }
    }
}
