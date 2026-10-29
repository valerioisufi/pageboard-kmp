package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.handwriting.groupStrokes
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.math.abs

/**
 * What a note of the lecture is about on its page, found from the time the content of the page was
 * written (`DrawElement.added`, the clock of the device that wrote it): the block of writing the
 * student was adding when the note was said, else the last block written during the lecture.
 * A preference for the placement of the note, never a constraint: the clock of another device may
 * be off, and a lecture imported has no writing of its time.
 */
public object LectureAnchors {

    /** How long before the moment a note was said the writing about it may have started. */
    public const val BEFORE_MS: Long = 60_000

    /** How long after the moment a note was said the writing about it may have gone on. */
    public const val AFTER_MS: Long = 30_000

    /**
     * The box of the writing of [page] about a note said at [saidAt] (wall clock) during a lecture
     * from [lectureStart] until [lectureEnd], or `null` when the page has none of that time.
     */
    public fun anchorOf(page: DrawPage, saidAt: Long, lectureStart: Long, lectureEnd: Long): Rect? {
        val written = page.strokes + page.texts
        return blockNearest(page, written.filter { it.added.time in (saidAt - BEFORE_MS)..(saidAt + AFTER_MS) }, saidAt)
            ?: blockNearest(page, written.filter { it.added.time in lectureStart..lectureEnd }, lectureEnd)
    }

    /** The box of the block of [elements] holding the one written the nearest to [time]. */
    private fun blockNearest(page: DrawPage, elements: List<DrawElement>, time: Long): Rect? {
        val placed = elements.mapNotNull { element -> page.boundsOf(element)?.takeIf { !it.isEmpty }?.let { element to it } }
        if (placed.isEmpty()) return null
        val nearest = placed.indices.minBy { abs(placed[it].first.added.time - time) }
        val block = groupStrokes(placed.map { it.second }).first { nearest in it }
        return block.map { placed[it].second }.reduce { a, b -> a.union(b) }
    }
}
