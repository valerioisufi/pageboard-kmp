package io.github.valerioisufi.drawview.core.model

/**
 * A single raw input sample of a stroke, in stroke units (page millimeters).
 *
 * @property pressure normalized stylus pressure in `[0, 1]`, or a negative value when unknown.
 * @property timestampMs timestamp of the sample (any monotonic origin, only deltas matter).
 */
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = -1f,
    val timestampMs: Long = 0L
)

/**
 * Device that generated the inputs of a stroke. Mirrors `ink::StrokeInput::ToolType`: the
 * Google Ink brushes react to stylus pressure only for [STYLUS] inputs.
 */
enum class InputToolType(val nativeId: Int) {
    UNKNOWN(0),
    MOUSE(1),
    TOUCH(2),
    STYLUS(3);

    companion object {
        fun fromName(name: String?): InputToolType =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}
