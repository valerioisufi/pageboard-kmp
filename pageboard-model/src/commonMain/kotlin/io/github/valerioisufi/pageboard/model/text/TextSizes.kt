package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.TextAlignment
import kotlinx.serialization.Serializable

/**
 * The sizes of the texts: typographic points, converted to the millimeters of the pages, from
 * [MIN] to [MAX], with the [PRESETS] the controls step through.
 */
public object TextSizes {
    /** Millimeters of a typographic point. */
    public const val PT_TO_MM: Float = 0.3527f

    public const val MIN: Float = 4f
    public const val MAX: Float = 288f

    /** The sizes offered, as the word processors offer them. */
    public val PRESETS: List<Float> = listOf(6f, 8f, 9f, 10f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 36f, 40f, 48f, 56f, 64f, 72f, 96f, 120f, 144f, 192f, 288f)

    /** [size] within the sizes of the texts. */
    public fun clamp(size: Float): Float = size.coerceIn(MIN, MAX)

    /** The preset after [size], or [MAX]. */
    public fun larger(size: Float): Float = PRESETS.firstOrNull { it > size + EPSILON } ?: MAX

    /** The preset before [size], or [MIN]. */
    public fun smaller(size: Float): Float = PRESETS.lastOrNull { it < size - EPSILON } ?: MIN

    /**
     * Height of a line of [fontSize] points in millimeters when the fonts cannot be measured (the
     * line of the fonts of the notes is about 1.2 times their size).
     */
    public fun estimatedLineHeightMm(fontSize: Float): Float = fontSize * PT_TO_MM * 1.2f

    /**
     * Height of [text] in millimeters when it cannot be laid out: a line of [fontSize] points for
     * each of its lines (the lines it wraps into are not counted).
     */
    public fun estimatedHeightMm(text: String, fontSize: Float): Float =
        (text.count { it == '\n' } + 1) * estimatedLineHeightMm(fontSize)

    private const val EPSILON = 0.01f
}

/** The style of a new text: the one of the text written last. */
@Serializable
public data class TextDefaults(
    val fontSize: Float = 16f,
    // The black ink of the design
    val color: Int = 0xFF1A1C18.toInt(),
    val alignment: TextAlignment = TextAlignment.START
)
