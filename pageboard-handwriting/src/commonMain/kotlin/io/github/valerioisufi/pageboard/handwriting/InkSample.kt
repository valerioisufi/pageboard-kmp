package io.github.valerioisufi.pageboard.handwriting

import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.ink.isHandwriting
import kotlin.math.max
import kotlin.math.min

/**
 * The strokes of a block as they were written: their samples in page millimeters (the placement
 * of each stroke applied), in the order they are given (the order of the page, which is the order
 * they were drawn in). What every reader takes; a reader that wants another form (an image, the
 * strokes of its platform) builds it.
 *
 * The times of the samples are those recorded (any origin, only the differences within a stroke
 * matter): [retimed] gives one clock to the whole block.
 *
 * @param missing the strokes of the block that had no samples, left out.
 */
public class InkSample(
    public val strokes: List<List<StrokePoint>>,
    public val missing: Int = 0
) {
    /** Whether there is nothing to read. */
    public val isEmpty: Boolean get() = strokes.isEmpty()

    /** The area of the samples, `null` without any. */
    public val bounds: Rect? get() = boundsOf(strokes.flatten())

    /** The bounds of each stroke. */
    public fun strokeBounds(): List<Rect> = strokes.map { boundsOf(it) ?: Rect.Zero }

    /** The strokes at [indices], in their order here. */
    public fun subset(indices: Collection<Int>): InkSample {
        val wanted = indices.toSet()
        return InkSample(strokes.filterIndexed { i, _ -> i in wanted })
    }

    /**
     * The same strokes on one clock from 0: each stroke keeps the durations between its samples (a
     * stroke without times gets [SAMPLE_MS] between them), the next starts [STROKE_GAP_MS] after it.
     * The readers that take the times need them increasing across the strokes, which the recorded
     * ones are not when strokes were written in different sessions or on different devices.
     */
    public fun retimed(): InkSample {
        var clock = 0L
        val timed = strokes.map { stroke ->
            val first = stroke.firstOrNull()?.timestampMs ?: 0L
            val hasTimes = stroke.size > 1 && stroke.zipWithNext().all { (a, b) -> b.timestampMs >= a.timestampMs } &&
                stroke.last().timestampMs > first
            val points = stroke.mapIndexed { i, p ->
                val t = if (hasTimes) clock + (p.timestampMs - first) else clock + i * SAMPLE_MS
                p.copy(timestampMs = t)
            }
            clock = (points.lastOrNull()?.timestampMs ?: clock) + STROKE_GAP_MS
            points
        }
        return InkSample(timed, missing)
    }

    public companion object {
        /** The time between two samples of a stroke recorded without times (milliseconds). */
        public const val SAMPLE_MS: Long = 8L

        /** The time between the end of a stroke and the start of the next one, in [retimed] (milliseconds). */
        public const val STROKE_GAP_MS: Long = 150L

        /** The strokes of writing of [strokes] as the page shows them (highlights, erasers and lassos left out). */
        public fun of(strokes: List<StrokeElement>): InkSample {
            val writing = strokes.filter { it.brushConfig.tool.isHandwriting }
            val sampled = writing.map { it.mesh.points }.filter { it.isNotEmpty() }
            return InkSample(sampled, missing = writing.size - sampled.size)
        }

        /** The strokes of writing of [strokes] as a store reads them, their placement applied. */
        public fun ofData(strokes: List<StrokeData>): InkSample {
            val writing = strokes.filter { it.tool.isHandwriting }
            val sampled = writing.filter { it.points.isNotEmpty() }.map { stroke ->
                if (stroke.transform.isIdentity) {
                    stroke.points
                } else {
                    val matrix = stroke.transform.toMatrix()
                    stroke.points.map { p -> matrix.mapPoint(p.x, p.y).let { StrokePoint(it.x, it.y, p.pressure, p.timestampMs) } }
                }
            }
            return InkSample(sampled, missing = writing.size - sampled.size)
        }

        internal fun boundsOf(points: List<StrokePoint>): Rect? {
            if (points.isEmpty()) return null
            var left = Float.POSITIVE_INFINITY
            var top = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            for (p in points) {
                left = min(left, p.x)
                top = min(top, p.y)
                right = max(right, p.x)
                bottom = max(bottom, p.y)
            }
            return Rect(left, top, right, bottom)
        }
    }
}
