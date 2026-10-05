package io.github.valerioisufi.drawview.lecture.handwriting

import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * A block of handwriting: strokes of a page close to each other (a few lines of notes, a formula,
 * a drawing), transcribed as a whole.
 *
 * @param key identity of the block: it changes when a stroke is added or erased.
 * @param bounds area of the strokes, in page millimeters.
 */
class InkBlock(
    val pageIndex: Int,
    val pageId: String,
    val key: String,
    val strokeIds: Set<String>,
    val bounds: Rect
)

/**
 * Groups strokes in blocks: two strokes whose areas are closer than [gapMm] are in the same
 * block. Returns the indexes of the strokes of every block, the blocks from the top of the page.
 */
internal fun groupStrokes(bounds: List<Rect>, gapMm: Float = BLOCK_GAP_MM): List<List<Int>> {
    val parent = IntArray(bounds.size) { it }
    fun root(i: Int): Int {
        var r = i
        while (parent[r] != r) r = parent[r]
        var j = i
        while (parent[j] != r) {
            val next = parent[j]
            parent[j] = r
            j = next
        }
        return r
    }
    // Sorted by left edge: a stroke is compared only with those starting before its right edge
    val order = bounds.indices.sortedBy { bounds[it].left }
    for ((position, i) in order.withIndex()) {
        val a = bounds[i]
        for (k in position + 1 until order.size) {
            val j = order[k]
            val b = bounds[j]
            if (b.left > a.right + gapMm) break
            if (b.top <= a.bottom + gapMm && a.top <= b.bottom + gapMm) parent[root(j)] = root(i)
        }
    }
    return bounds.indices.groupBy { root(it) }.values
        .sortedBy { block -> block.minOf { bounds[it].top } }
}

/** The key of the block of [strokeIds]: the same strokes, the same key. */
internal fun blockKey(strokeIds: Collection<String>): String {
    // FNV-1a, 64 bits
    var hash = -0x340d631b7bdddcdbL
    for (id in strokeIds.sorted()) {
        for (c in id) {
            hash = hash xor c.code.toLong()
            hash *= 0x100000001b3L
        }
        hash = hash xor ','.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16)
}

internal fun unionOf(rects: List<Rect>): Rect = rects.reduce { a, b ->
    Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
}

/** Strokes closer than this (millimeters) are in the same block: the lines of a paragraph. */
internal const val BLOCK_GAP_MM = 8f
