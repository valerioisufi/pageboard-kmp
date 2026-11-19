package io.github.valerioisufi.pageboard.model.placement

import io.github.valerioisufi.pageboard.model.geometry.Rect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A block of content to place: a text whose height follows its width.
 *
 * @param preferredWidthMm the width it is laid out on when there is room.
 * @param minWidthMm the narrowest it may be made to fit (a narrower block is hard to read).
 * @param heightAt its height in millimeters laid out on a width in millimeters.
 */
public class PlacementBlock(
    public val preferredWidthMm: Float,
    public val minWidthMm: Float = DEFAULT_MIN_WIDTH_MM,
    public val heightAt: (widthMm: Float) -> Float
) {
    public companion object {
        public const val DEFAULT_MIN_WIDTH_MM: Float = 30f
    }
}

/** A place found for a block: its box on the page and its cost (the lower, the better). */
public class PlacementCandidate(public val rect: Rect, public val cost: Float)

/** What makes a place good for a block: the lower the cost, the better (see [PlacementPlanner]). */
public fun interface PlacementPolicy {

    /** The cost of putting [block] at [rect] (a free box of the page, at one of the widths of the block). */
    public fun cost(rect: Rect, block: PlacementBlock): Float
}

/**
 * Next to [anchor], what the block is about, in the order a page is read: to its right or below it
 * rather than above it, aligned with its left edge, as wide as the block prefers. Without an
 * anchor, the first free place from the top.
 */
public class BesideAnchor(public val anchor: Rect?) : PlacementPolicy {
    override fun cost(rect: Rect, block: PlacementBlock): Float {
        val narrowing = (block.preferredWidthMm - rect.width).coerceAtLeast(0f) / block.preferredWidthMm * NARROWING_COST
        val anchor = anchor ?: return rect.top + rect.left * 0.2f + narrowing
        val gapX = max(0f, max(anchor.left - rect.right, rect.left - anchor.right))
        val gapY = max(0f, max(anchor.top - rect.bottom, rect.top - anchor.bottom))
        val above = rect.bottom <= anchor.top + EDGE_MM
        val left = rect.right <= anchor.left + EDGE_MM
        var cost = gapX * (if (left) LEFT_COST else RIGHT_COST) + gapY * (if (above) ABOVE_COST else BELOW_COST)
        if (above) cost += ABOVE_PENALTY
        if (left) cost += LEFT_PENALTY
        if (abs(rect.left - anchor.left) < EDGE_MM) cost -= ALIGNED_BONUS
        // Between two places as near, the one read first
        return cost + narrowing + (rect.top + rect.left) * 0.01f
    }

    private companion object {
        const val EDGE_MM = 1f
        const val RIGHT_COST = 1.2f
        const val LEFT_COST = 2f
        const val BELOW_COST = 1f
        const val ABOVE_COST = 3f
        const val ABOVE_PENALTY = 20f
        const val LEFT_PENALTY = 10f
        const val ALIGNED_BONUS = 4f
        const val NARROWING_COST = 25f
    }
}

/**
 * Beside [anchor] first, at its height (in the margin or the free column next to it, to its right
 * rather than its left), as a short note in the margin of a book; below it (see [BesideAnchor])
 * when nothing is free beside it. Without an anchor, as [BesideAnchor].
 */
public class InMargin(public val anchor: Rect?) : PlacementPolicy {
    private val below = BesideAnchor(anchor)

    override fun cost(rect: Rect, block: PlacementBlock): Float {
        val anchor = anchor ?: return below.cost(rect, block)
        val beside = rect.top < anchor.bottom && rect.bottom > anchor.top
        if (!beside) return below.cost(rect, block) + NOT_BESIDE_PENALTY
        val gapX = max(0f, max(anchor.left - rect.right, rect.left - anchor.right))
        val left = rect.right <= anchor.left
        val narrowing = (block.preferredWidthMm - rect.width).coerceAtLeast(0f) / block.preferredWidthMm * NARROWING_COST
        return gapX * (if (left) 2f else 1f) + abs(rect.top - anchor.top) * 0.5f + narrowing
    }

    private companion object {
        const val NOT_BESIDE_PENALTY = 30f
        const val NARROWING_COST = 25f
    }
}

/** The bottom of the free space of the page, from the left (an announcement, apart from the notes). */
public object PageBottom : PlacementPolicy {
    override fun cost(rect: Rect, block: PlacementBlock): Float {
        val narrowing = (block.preferredWidthMm - rect.width).coerceAtLeast(0f) / block.preferredWidthMm * 25f
        return -rect.bottom + rect.left * 0.01f + narrowing
    }
}

/** The first free place in reading order, from the top (the pieces of a text flowing through a page). */
public object ReadingOrder : PlacementPolicy {
    override fun cost(rect: Rect, block: PlacementBlock): Float = rect.top * 1000f + rect.left
}

/**
 * Finds the places where blocks fit on a page without covering its content (see [FreeSpace]),
 * scored by a [PlacementPolicy]: every position of the grid, at the widths of the block from the
 * one it prefers down to the narrowest, its left edge also on the anchor of [BesideAnchor], its
 * top on the lines of a ruled page. Deterministic: the same page gives the same places on every
 * device.
 *
 * @param maxCandidates the most places returned for a block.
 * @param separationMm the least distance between the top-left corners of two places returned.
 */
public class PlacementPlanner(
    public val cellMm: Float = FreeSpace.DEFAULT_CELL_MM,
    public val clearanceMm: Float = FreeSpace.DEFAULT_CLEARANCE_MM,
    public val pageMarginMm: Float = FreeSpace.DEFAULT_PAGE_MARGIN_MM,
    public val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    public val separationMm: Float = DEFAULT_SEPARATION_MM
) {

    /** The free space of [occupancy] as this planner sees it. */
    public fun freeSpace(occupancy: PageOccupancy): FreeSpace = FreeSpace(occupancy, cellMm, clearanceMm, pageMarginMm)

    /** The best places for [block] on the page of [occupancy], the best first; empty when it fits nowhere. */
    public fun candidates(occupancy: PageOccupancy, block: PlacementBlock, policy: PlacementPolicy): List<PlacementCandidate> {
        val space = freeSpace(occupancy)
        val found = mutableListOf<PlacementCandidate>()
        val xs = positions(space.columns, occupancy.widthMm)
        (policy as? BesideAnchor)?.anchor?.let { xs.add(it.left) }
        val ys = if (occupancy.lineSpacingMm >= cellMm) {
            generateSequence(occupancy.lineSpacingMm) { it + occupancy.lineSpacingMm }.takeWhile { it < occupancy.heightMm }.toMutableList()
        } else {
            positions(space.rows, occupancy.heightMm)
        }
        for (width in widths(block, occupancy.widthMm)) {
            val height = block.heightAt(width).coerceAtLeast(cellMm)
            for (y in ys) {
                if (y + height > occupancy.heightMm) break
                for (x in xs) {
                    val rect = Rect(x, y, x + width, y + height)
                    if (rect.right <= occupancy.widthMm && space.isFree(rect)) found += PlacementCandidate(rect, policy.cost(rect, block))
                }
            }
        }
        found.sortWith(compareBy<PlacementCandidate> { it.cost }.thenBy { it.rect.top }.thenBy { it.rect.left }.thenByDescending { it.rect.width })
        val chosen = mutableListOf<PlacementCandidate>()
        for (candidate in found) {
            if (chosen.size >= maxCandidates) break
            if (chosen.none { hypot(it.rect.left - candidate.rect.left, it.rect.top - candidate.rect.top) < separationMm }) chosen += candidate
        }
        return chosen
    }

    /**
     * Places [blocks] one after the other on the page of [occupancy], each where its policy finds
     * the best place once the previous ones are there: the box of each, or `null` for those that
     * fit nowhere.
     */
    public fun placeAll(occupancy: PageOccupancy, blocks: List<Pair<PlacementBlock, PlacementPolicy>>): List<Rect?> {
        var current = occupancy
        return blocks.map { (block, policy) ->
            candidates(current, block, policy).firstOrNull()?.rect?.also { current += it }
        }
    }

    /** The left (or top) edge of every cell of the grid, over a length of [lengthMm]. */
    private fun positions(cells: Int, lengthMm: Float): MutableList<Float> =
        (0 until cells).map { it * cellMm }.filter { it < lengthMm }.toMutableList()

    /** The widths tried for [block]: the one it prefers, then narrower by a fifth, down to the narrowest. */
    private fun widths(block: PlacementBlock, pageWidthMm: Float): List<Float> {
        val most = pageWidthMm - 2 * pageMarginMm
        val least = min(block.minWidthMm, most)
        var width = min(block.preferredWidthMm, most)
        if (width <= 0f) return emptyList()
        return buildList {
            while (width > least) {
                add(snap(width))
                width *= NARROWING
            }
            add(snap(least))
        }.distinct()
    }

    /** [width] down to whole cells, so that a box covers no cell more than it needs. */
    private fun snap(width: Float): Float = max(cellMm, floor(width / cellMm) * cellMm)

    public companion object {
        public const val DEFAULT_MAX_CANDIDATES: Int = 5
        public const val DEFAULT_SEPARATION_MM: Float = 20f
        private const val NARROWING = 0.8f
    }
}
