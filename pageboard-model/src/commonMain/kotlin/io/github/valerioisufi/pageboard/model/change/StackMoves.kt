package io.github.valerioisufi.pageboard.model.change

import io.github.valerioisufi.pageboard.model.DrawElement
import io.github.valerioisufi.pageboard.model.DrawPage
import io.github.valerioisufi.pageboard.model.ElementOrder
import io.github.valerioisufi.pageboard.model.geometry.Rect

/** How a selection moves in the stack of its page (see [ReorderElements.of]). */
public enum class StackMove {
    /** On top of everything. */
    FRONT,

    /** Just above the next element above it that it overlaps. */
    FORWARD,

    /** Just under the next element under it that it overlaps. */
    BACKWARD,

    /** Under everything. */
    BACK
}

/**
 * The new orders of a selection moved in the stack of its page: the selection (ordered as in the
 * stack) moves as a block, its order among itself kept, past the elements it overlaps (by their
 * bounds), so that every move changes what is seen.
 */
internal object StackMoves {

    /** The orders of the elements [ids] of [page] moved by [move], or `null` when nothing would change. */
    fun of(page: DrawPage, ids: Set<String>, move: StackMove): List<ElementOrdering>? {
        val stack = page.stack
        val selected = stack.filter { it.id in ids }
        if (selected.isEmpty()) return null
        val bounds = selected.mapNotNull { page.boundsOf(it) }.reduceOrNull(::union)
        fun overlapsSelection(element: DrawElement): Boolean {
            if (element.id in ids) return false
            val box = page.boundsOf(element) ?: return true
            return bounds == null || box.overlaps(bounds)
        }
        val lowest = stack.indexOf(selected.first())
        val highest = stack.indexOf(selected.last())
        return when (move) {
            StackMove.FRONT -> {
                if ((lowest + 1 until stack.size).none { overlapsSelection(stack[it]) }) return null
                // The orders of the change: on top, in their order
                selected.map { ElementOrdering(it.id) }
            }
            StackMove.BACK -> {
                if ((0 until highest).none { overlapsSelection(stack[it]) }) return null
                val bottom = stack.first { it.id !in ids }
                ordered(page, selected, null, bottom, insertAt = stack.indexOf(bottom))
            }
            StackMove.FORWARD -> {
                val passed = (highest + 1 until stack.size).firstOrNull { overlapsSelection(stack[it]) } ?: return null
                val key = stack[passed].order
                // The next one above it, past the ones of the same order
                val above = (passed + 1 until stack.size).map { stack[it] }.firstOrNull { it.id !in ids && it.order > key }
                    ?: return selected.map { ElementOrdering(it.id) }
                ordered(page, selected, stack[passed], above, insertAt = passed + 1)
            }
            StackMove.BACKWARD -> {
                val passed = (lowest - 1 downTo 0).firstOrNull { overlapsSelection(stack[it]) } ?: return null
                val key = stack[passed].order
                val below = (passed - 1 downTo 0).map { stack[it] }.firstOrNull { it.id !in ids && it.order < key }
                ordered(page, selected, below, stack[passed], insertAt = passed)
            }
        }
    }

    /**
     * The orders of [selected] between [below] (`null`: the bottom) and [above]; when they would be
     * too long, every element of the stack of [page] gets a new short order, [selected] at
     * [insertAt] of the stack (its index before the move).
     */
    private fun ordered(page: DrawPage, selected: List<DrawElement>, below: DrawElement?, above: DrawElement, insertAt: Int): List<ElementOrdering> {
        val orders = ElementOrder.between(below?.order, above.order, selected.size)
        if (orders.all { it.key.length <= ElementOrder.MAX_LENGTH }) return selected.zip(orders) { element, order -> ElementOrdering(element.id, order) }
        // The stack keyed again, in its new order
        val stack = page.stack
        val rest = stack.filter { it !in selected }
        val restBefore = stack.take(insertAt).count { it !in selected }
        val target = rest.take(restBefore) + selected + rest.drop(restBefore)
        return target.zip(ElementOrder.spread(target.size)) { element, order -> ElementOrdering(element.id, order) }
    }

    private fun union(a: Rect, b: Rect): Rect = Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
}
