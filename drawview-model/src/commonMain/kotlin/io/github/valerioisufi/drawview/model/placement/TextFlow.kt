package io.github.valerioisufi.drawview.model.placement

import io.github.valerioisufi.drawview.model.geometry.Rect

/**
 * A long Markdown text flowed through the free space of a page, then onto new pages after it: split
 * into blocks that never break a formula, a code block or a list item, the blocks one after the
 * other in reading order, those that follow each other in one place joined in one text.
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
    ): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var occupancy = first ?: newPage
        var pageNumber = if (first == null) 1 else 0
        val pending = ArrayDeque(blocksOf(markdown))
        while (pending.isNotEmpty()) {
            val block = pending.first()
            val width = occupancy.widthMm - 2 * planner.pageMarginMm
            val single = PlacementBlock(width, minOf(minWidthMm, width)) { heightOf(block, it) }
            val spot = planner.candidates(occupancy, single, ReadingOrder).firstOrNull()?.rect
            if (spot == null) {
                if (occupancy.boxes.isEmpty()) {
                    // Taller than an empty page: split between its lines, or cut where it ends
                    val lines = block.lines()
                    pending.removeFirst()
                    if (lines.size > 1) {
                        lines.chunked((lines.size + 1) / 2).asReversed().forEach { pending.addFirst(it.joinToString("\n")) }
                    } else {
                        pieces += Piece(block, pageNumber, Rect(planner.pageMarginMm, planner.pageMarginMm, occupancy.widthMm - planner.pageMarginMm, occupancy.heightMm - planner.pageMarginMm))
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
