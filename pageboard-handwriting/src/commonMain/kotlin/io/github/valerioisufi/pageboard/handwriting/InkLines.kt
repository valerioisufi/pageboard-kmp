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
 * [drawingFactor] times it, or higher than [tallFactor] times it, is drawing, and so is a stroke
 * touching a drawing without writing beside it. The others go to the line whose middle is nearest
 * to theirs, within [lineOverlap] of the height of the writing beyond their half height; the small
 * ones (dots, accents, the bar of a t) go to the nearest line.
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

        // The large strokes, then what touches them (the petals of a flower, the rays of a sun)
        val drawing = bounds.indices.filter { i ->
            val b = bounds[i]
            (b.height > drawingFactor * writingHeight && b.width > drawingFactor * writingHeight) || b.height > tallFactor * writingHeight
        }.toMutableSet()
        var grown = true
        while (grown) {
            grown = false
            for (i in bounds.indices) {
                if (i in drawing) continue
                val b = bounds[i].grown(TOUCH_SHARE * writingHeight)
                if (drawing.any { d -> touches(b, bounds[d]) && !isWritingAround(i, bounds, drawing, writingHeight) }) {
                    drawing.add(i)
                    grown = true
                }
            }
        }
        val small = bounds.indices.filter { it !in drawing && heights[it] < SMALL_SHARE * writingHeight }.toSet()
        val body = bounds.indices.filter { it !in drawing && it !in small }.sortedBy { bounds[it].centerY }

        // A line is the band of the middles of its strokes: the ascenders and descenders do not widen it
        val lines = mutableListOf<MutableList<Int>>()
        val middles = mutableListOf<MutableList<Float>>()
        for (i in body) {
            val b = bounds[i]
            val line = middles.indices
                .filter { l -> abs(b.centerY - median(middles[l])) < lineOverlap * writingHeight + b.height / 2f }
                .minByOrNull { l -> abs(b.centerY - median(middles[l])) }
            if (line == null) {
                lines.add(mutableListOf(i))
                middles.add(mutableListOf(b.centerY))
            } else {
                lines[line].add(i)
                middles[line].add(b.centerY)
            }
        }
        val bands = lines.map { line -> line.map { bounds[it] }.reduce { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) } }
            .toMutableList()
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
        val order = bands.indices.sortedBy { l -> lines[l].map { bounds[it].centerY }.let(::median) }
        return InkLayout(order.map { l -> ink.subset(lines[l].sorted()) }, ink.subset(drawing))
    }

    /** Whether stroke [i] has writing beside it on its line (a letter next to a drawing stays a letter). */
    private fun isWritingAround(i: Int, bounds: List<Rect>, drawing: Set<Int>, writingHeight: Float): Boolean {
        val b = bounds[i]
        return bounds.indices.count { j ->
            j != i && j !in drawing && abs(bounds[j].centerY - b.centerY) < writingHeight &&
                abs(bounds[j].centerX - b.centerX) < NEIGHBOUR_SHARE * writingHeight
        } >= WRITING_NEIGHBOURS
    }

    private fun touches(a: Rect, b: Rect) = a.left <= b.right && b.left <= a.right && a.top <= b.bottom && b.top <= a.bottom

    private fun Rect.grown(by: Float) = Rect(left - by, top - by, right + by, bottom + by)

    private fun median(values: List<Float>): Float = values.sorted()[values.size / 2]

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

        /** A stroke this close to a drawing (a share of the height of the writing) is part of it... */
        const val TOUCH_SHARE = 0.5f

        /** ...unless it has this many strokes of writing beside it, within this share of the height. */
        const val WRITING_NEIGHBOURS = 2
        const val NEIGHBOUR_SHARE = 2.5f
    }
}
