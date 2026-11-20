package io.github.valerioisufi.pageboard.handwriting

import io.github.valerioisufi.pageboard.model.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The lines of a block of handwriting, from the top, and what was left out as drawing.
 *
 * @param lines each line with its strokes in the order of the block.
 * @param drawing the strokes that look like a drawing (a diagram, a large arrow): not read.
 */
public class InkLayout(public val lines: List<InkSample>, public val drawing: InkSample)

/**
 * Splits a block of handwriting into lines by the vertical overlap of its strokes, and leaves out
 * as drawing the strokes much larger than the writing around them: a misreading of a drawing would
 * be found by the searches and suggested while writing.
 *
 * The height of the writing is the median height of the strokes. A stroke higher and wider than
 * [drawingFactor] times it, or higher than [tallFactor] times it, is drawing. The others go to the
 * line they overlap by at least [lineOverlap] of the lower of the two heights; the small ones
 * (dots, accents, the bar of a t) go to the nearest line.
 */
public class InkLines(
    public val drawingFactor: Float = 4f,
    public val tallFactor: Float = 6f,
    public val lineOverlap: Float = 0.5f
) {
    public fun split(ink: InkSample): InkLayout {
        if (ink.isEmpty) return InkLayout(emptyList(), InkSample(emptyList()))
        val bounds = ink.strokeBounds()
        val heights = bounds.map { max(it.height, MIN_HEIGHT_MM) }
        val writingHeight = heights.sorted()[heights.size / 2]

        val drawing = bounds.indices.filter { i ->
            val b = bounds[i]
            (b.height > drawingFactor * writingHeight && b.width > drawingFactor * writingHeight) || b.height > tallFactor * writingHeight
        }.toSet()
        val small = bounds.indices.filter { it !in drawing && heights[it] < SMALL_SHARE * writingHeight }.toSet()
        val body = bounds.indices.filter { it !in drawing && it !in small }.sortedBy { bounds[it].centerY }

        // The bands of the lines: the strokes of the body, top to bottom
        val lines = mutableListOf<MutableList<Int>>()
        val bands = mutableListOf<Rect>()
        for (i in body) {
            val b = bounds[i]
            val line = bands.indices.firstOrNull { l -> overlap(bands[l], b) >= lineOverlap * min(bands[l].height, b.height) }
            if (line == null) {
                lines.add(mutableListOf(i))
                bands.add(b)
            } else {
                lines[line].add(i)
                bands[line] = Rect(min(bands[line].left, b.left), min(bands[line].top, b.top), max(bands[line].right, b.right), max(bands[line].bottom, b.bottom))
            }
        }
        // The small strokes to the nearest line; alone, a line of their own
        for (i in small) {
            val b = bounds[i]
            val nearest = bands.indices.minByOrNull { l -> distance(bands[l], b) }
            if (nearest == null) {
                lines.add(mutableListOf(i))
                bands.add(b)
            } else {
                lines[nearest].add(i)
            }
        }
        val order = bands.indices.sortedBy { bands[it].top }
        return InkLayout(order.map { l -> ink.subset(lines[l].sorted()) }, ink.subset(drawing))
    }

    private fun overlap(a: Rect, b: Rect): Float = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))

    private fun distance(band: Rect, b: Rect): Float {
        val dy = when {
            b.centerY < band.top -> band.top - b.centerY
            b.centerY > band.bottom -> b.centerY - band.bottom
            else -> 0f
        }
        val dx = when {
            b.centerX < band.left -> band.left - b.centerX
            b.centerX > band.right -> b.centerX - band.right
            else -> 0f
        }
        return dy * VERTICAL_WEIGHT + abs(dx)
    }

    private val Rect.centerX: Float get() = (left + right) / 2f
    private val Rect.centerY: Float get() = (top + bottom) / 2f

    private companion object {
        /** A stroke lower than this is a dot (millimeters). */
        const val MIN_HEIGHT_MM = 0.2f

        /** A stroke lower than this share of the writing is small: a dot, an accent, a bar. */
        const val SMALL_SHARE = 0.4f

        /** A small stroke belongs to the line above or below it before the one beside it. */
        const val VERTICAL_WEIGHT = 3f
    }
}
