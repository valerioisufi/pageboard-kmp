package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.geometry.Rect
import kotlin.math.floor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Styling definition for page background rendering.
 */
@Serializable
public sealed class PageBackground {
    public abstract val backgroundColor: Int

    /**
     * The lines or the dots drawn over [backgroundColor] on a page of [widthMm] x [heightMm], those
     * of [area] only (page millimeters; the whole page by default), or `null` for a solid color.
     */
    public fun pattern(widthMm: Float, heightMm: Float, area: Rect = Rect(0f, 0f, widthMm, heightMm)): BackgroundPattern? {
        fun positions(spacingMm: Float, lengthMm: Float, from: Float, to: Float): FloatArray {
            if (spacingMm <= 0f) return FloatArray(0)
            val end = minOf(to, lengthMm)
            val positions = ArrayList<Float>()
            // Every spacing after the edge, from the one before the area (a line crossing its edge)
            var k = maxOf(floor(from / spacingMm).toInt(), 1)
            while (k * spacingMm < end) positions.add(k++ * spacingMm)
            return positions.toFloatArray()
        }
        val rows = { spacing: Float -> positions(spacing, heightMm, area.top, area.bottom) }
        val columns = { spacing: Float -> positions(spacing, widthMm, area.left, area.right) }
        return when (this) {
            is Solid -> null
            is Ruled -> BackgroundPattern(lineColor, spacingMm, thicknessMm, dots = false, rows(spacingMm), FloatArray(0), widthMm, heightMm)
            is Grid -> BackgroundPattern(lineColor, spacingMm, thicknessMm, dots = false, rows(spacingMm), columns(spacingMm), widthMm, heightMm)
            is Dotted -> BackgroundPattern(dotColor, spacingMm, dotRadiusMm, dots = true, rows(spacingMm), columns(spacingMm), widthMm, heightMm)
        }
    }

    @Serializable
    @SerialName("Solid")
    public data class Solid(
        override val backgroundColor: Int = 0xFFFFFFFF.toInt()
    ) : PageBackground()

    @Serializable
    @SerialName("Ruled")
    public data class Ruled(
        override val backgroundColor: Int = 0xFFFFFFFF.toInt(),
        val lineColor: Int = 0x330000FF.toInt(),
        val spacingMm: Float = 8f,
        val thicknessMm: Float = 0.5f
    ) : PageBackground()

    @Serializable
    @SerialName("Grid")
    public data class Grid(
        override val backgroundColor: Int = 0xFFFFFFFF.toInt(),
        val lineColor: Int = 0x330000FF.toInt(),
        val spacingMm: Float = 5f,
        val thicknessMm: Float = 0.5f
    ) : PageBackground()

    @Serializable
    @SerialName("Dotted")
    public data class Dotted(
        override val backgroundColor: Int = 0xFFFFFFFF.toInt(),
        val dotColor: Int = 0x50000000.toInt(),
        val spacingMm: Float = 5f,
        val dotRadiusMm: Float = 0.5f
    ) : PageBackground()
}

/**
 * The pattern of a [PageBackground] on a page of [widthMm] x [heightMm], in page millimeters:
 * horizontal lines at [rows] and vertical lines at [columns] across the page, or ([dots]) a dot at
 * every row and column.
 *
 * @param spacingMm distance between two lines or two dots.
 * @param sizeMm thickness of the lines, or radius of the dots.
 */
public class BackgroundPattern(
    public val color: Int,
    public val spacingMm: Float,
    public val sizeMm: Float,
    public val dots: Boolean,
    public val rows: FloatArray,
    public val columns: FloatArray,
    public val widthMm: Float,
    public val heightMm: Float
)
