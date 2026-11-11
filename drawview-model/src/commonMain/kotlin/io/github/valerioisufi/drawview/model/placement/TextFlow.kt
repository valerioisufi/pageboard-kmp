package io.github.valerioisufi.drawview.model.placement

import io.github.valerioisufi.drawview.model.geometry.Rect

/**
 * A long Markdown text flowed through the free space of a page, then onto new pages after it: split
 * into blocks that never break a formula, a code block or a list item, nor part a heading from what
 * follows it, the blocks one after the other in reading order, those that follow each other in one place joined in one text.
 *
 * @param planner finds the free places (see [PlacementPlanner]).
 * @param minWidthMm the narrowest a piece may be (narrower, a long text is hard to read).
 */
public class TextFlow(
    public val planner: PlacementPlanner = PlacementPlanner(),
    public val minWidthMm: Float = DEFAULT_MIN_WIDTH_MM
) {

    /**
     * A piece of the text: its Markdown, the page it goes on ([newPage] `0`: the first page given to
     * [flow]; `n`: the n-th new page after it) and its box there.
     */
    public class Piece(public val text: String, public val newPage: Int, public val box: Rect)

    /**
     * Where the text begins on the first page given to [flow], when it matters what it is about. The
     * pieces after it on that page stay below it, so that it reads in order.
     *
     * @param policy what makes a place good for its first block.
     * @param widthMm the width that block prefers.
     * @param minWidthMm the narrowest it may be made.
     */
    public class FirstPlace(public val policy: PlacementPolicy, public val widthMm: Float, public val minWidthMm: Float)

    /** Reading order below [above], a piece of the same text: a place above it would read before it. */
    private class Below(private val above: Rect) : PlacementPolicy {
        override fun cost(rect: Rect, block: PlacementBlock): Float =
            ReadingOrder.cost(rect, block) + if (rect.top < above.bottom) ABOVE_COST else 0f

        private companion object {
            const val ABOVE_COST = 1e9f
        }
    }

    /**
     * Places [markdown] on the free space of [first] (none: straight on new pages), then on new
     * pages of [newPage] (empty: their occupancy without content), each as wide as the page allows.
     *
     * @param heightOf the height in millimeters of a Markdown text laid out on a width in millimeters.
     * @return the pieces in the order of the text; empty when the text is blank.
     */
    public fun flow(
        markdown: String,
        first: PageOccupancy?,
        newPage: PageOccupancy,
        heightOf: (text: String, widthMm: Float) -> Float
    ): List<Piece> = flow(markdown, first, newPage, null, heightOf)

    /**
     * As [flow] from the first free place, but beginning at [firstPlace] on [first].
     *
     * @param firstPlace where it begins on [first]; `null`: the first free place from the top.
     */
    public fun flow(
        markdown: String,
        first: PageOccupancy?,
        newPage: PageOccupancy,
        firstPlace: FirstPlace?,
        heightOf: (text: String, widthMm: Float) -> Float
    ): List<Piece> = flow(markdown, first, newPage, firstPlace, null, heightOf)

    /**
     * As [flow] beginning at [firstPlace], every piece [widthMm] wide when given: a piece is then never
     * made narrower, and goes where that width is free.
     */
    public fun flow(
        markdown: String,
        first: PageOccupancy?,
        newPage: PageOccupancy,
        firstPlace: FirstPlace?,
        widthMm: Float?,
        heightOf: (text: String, widthMm: Float) -> Float
    ): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var occupancy = first ?: newPage
        var pageNumber = if (first == null) 1 else 0
        val pending = ArrayDeque(withTheirHeadings(blocksOf(markdown)))
        while (pending.isNotEmpty()) {
            val block = pending.first()
            val usable = occupancy.widthMm - 2 * planner.pageMarginMm
            val width = widthMm?.coerceAtMost(usable) ?: usable
            val placed = pieces.lastOrNull()?.takeIf { it.newPage == pageNumber }
            val begins = firstPlace != null && first != null && pageNumber == 0 && placed == null
            val single = if (begins) {
                PlacementBlock(minOf(firstPlace.widthMm, width), minOf(firstPlace.minWidthMm, width)) { heightOf(block, it) }
            } else {
                PlacementBlock(width, if (widthMm != null) width else minOf(minWidthMm, width)) { heightOf(block, it) }
            }
            val policy = when {
                begins -> firstPlace.policy
                firstPlace != null && placed != null && pageNumber == 0 -> Below(placed.box)
                else -> ReadingOrder
            }
            val below = placed?.box?.bottom?.takeIf { policy is Below }
            val spot = planner.candidates(occupancy, single, policy).firstOrNull { below == null || it.rect.top >= below }?.rect
            if (spot == null) {
                if (occupancy.boxes.isEmpty()) {
                    // Taller than an empty page: split between its lines, or cut where it ends
                    val lines = block.lines()
                    pending.removeFirst()
                    if (lines.size > 1) {
                        lines.chunked((lines.size + 1) / 2).asReversed().forEach { pending.addFirst(it.joinToString("\n")) }
                    } else {
                        pieces += Piece(block, pageNumber, Rect(planner.pageMarginMm, planner.pageMarginMm, planner.pageMarginMm + width, occupancy.heightMm - planner.pageMarginMm))
                    }
                    continue
                }
                occupancy = newPage
                pageNumber++
                continue
            }
            // The blocks that follow, joined while the text still fits where it starts
            pending.removeFirst()
            var text = block
            var box: Rect = spot
            val space = planner.freeSpace(occupancy)
            while (pending.isNotEmpty()) {
                val joined = text + "\n\n" + pending.first()
                val height = heightOf(joined, box.width)
                val grown = Rect(box.left, box.top, box.right, box.top + height)
                if (!space.isFree(grown)) break
                text = joined
                box = grown
                pending.removeFirst()
            }
            pieces += Piece(text, pageNumber, box)
            occupancy += box
        }
        return pieces
    }

    public companion object {
        public const val DEFAULT_MIN_WIDTH_MM: Float = 60f

        /**
         * The sections of [markdown]: its blocks (see [blocksOf]) gathered from one heading to the
         * next (`#` to `###`), what comes before the first heading a section of its own.
         */
        public fun sectionsOf(markdown: String): List<String> {
            val sections = mutableListOf<MutableList<String>>()
            for (block in blocksOf(markdown)) {
                if (sections.isEmpty() || HEADING.matches(block.lineSequence().first())) sections += mutableListOf(block) else sections.last() += block
            }
            return sections.map { it.joinToString("\n\n") }
        }

        private val HEADING = Regex("""^#{1,3}\s.*""")

        /** A line that is a heading of any level: it goes where the block after it goes. */
        private val ANY_HEADING = Regex("""^#{1,6}\s.*""")

        /** [blocks] with each block of a heading alone joined to the block after it: no heading ends a piece. */
        private fun withTheirHeadings(blocks: List<String>): List<String> {
            val joined = mutableListOf<String>()
            var pending: String? = null
            for (block in blocks) {
                val whole = pending?.let { it + "\n\n" + block } ?: block
                pending = if ('\n' !in block && ANY_HEADING.matches(block)) whole else null
                if (pending == null) joined += whole
            }
            pending?.let { joined += it }
            return joined
        }

        /**
         * The blocks of [markdown]: its paragraphs, between blank lines, but a fenced code block
         * (` ``` `), a formula between `$$` and an item of a list with the lines under it are kept
         * whole.
         */
        public fun blocksOf(markdown: String): List<String> {
            val blocks = mutableListOf<String>()
            val current = mutableListOf<String>()
            var fence: String? = null
            var inFormula = false
            fun close() {
                while (current.isNotEmpty() && current.last().isBlank()) current.removeAt(current.size - 1)
                if (current.isNotEmpty()) blocks += current.joinToString("\n")
                current.clear()
            }
            val lines = markdown.trim().lines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trimStart()
                when {
                    fence != null -> {
                        current += line
                        if (trimmed.startsWith(fence)) fence = null
                    }
                    trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                        fence = trimmed.take(3)
                        current += line
                    }
                    inFormula -> {
                        current += line
                        if (trimmed.contains("$$")) inFormula = false
                    }
                    trimmed.startsWith("$$") && trimmed.indexOf("$$", 2) < 0 -> {
                        inFormula = true
                        current += line
                    }
                    line.isBlank() -> {
                        // A blank line inside a list item (the next line indented under it) keeps it whole
                        val next = lines.getOrNull(index + 1)
                        if (current.isNotEmpty() && next != null && next.startsWith("  ") && next.isNotBlank()) current += line else close()
                    }
                    else -> current += line
                }
            }
            close()
            return blocks
        }
    }
}
