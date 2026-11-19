package io.github.valerioisufi.pageboard.canvas.controller

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.pageboard.model.DrawDocument

/**
 * The pages selected in the page grid (see `docs/plans/page-selection.md`), kept by their identifiers:
 * the selection follows the pages when they move, and a page removed (here or by another device)
 * leaves it. It is not stored and not sent to the others.
 */
@Stable
public class PageSelection internal constructor(private val document: DrawDocument) {
    private var ids by mutableStateOf(emptySet<String>())

    /** The last page selected or deselected one by one: where a range starts from ([selectRange]). */
    private var anchor: String? = null

    /** Whether the grid is selecting: a tap selects or deselects a page instead of showing it. */
    public var isActive: Boolean by mutableStateOf(false)

    /** The pages selected, shown in the document, in its order. */
    public val pageIds: List<String> get() = ids.let { selected -> document.pages.filter { it.id in selected }.map { it.id } }

    /** The number of pages selected. */
    public val count: Int get() = pageIds.size

    public fun isSelected(pageId: String): Boolean = pageId in ids && document.pageById(pageId) != null

    /** Enters the selection, with no page selected yet. */
    public fun start() {
        isActive = true
    }

    /** Selects the page [pageId], or deselects it when it is selected, entering the selection. */
    public fun toggle(pageId: String) {
        if (document.pageById(pageId) == null) return
        ids = if (isSelected(pageId)) ids - pageId else pruned() + pageId
        anchor = pageId
        isActive = true
    }

    /** Selects the pages from the last one toggled (the first page without one) to [pageId], entering the selection. */
    public fun selectRange(pageId: String) {
        val pages = document.pages
        val to = pages.indexOfFirst { it.id == pageId }.takeIf { it >= 0 } ?: return
        val from = pages.indexOfFirst { it.id == anchor }.takeIf { it >= 0 } ?: to
        ids = pruned() + pages.subList(minOf(from, to), maxOf(from, to) + 1).map { it.id }
        isActive = true
    }

    /** Selects every page of the document, entering the selection. */
    public fun selectAll() {
        ids = document.pages.map { it.id }.toSet()
        isActive = true
    }

    /** Leaves the selection, no page selected. */
    public fun clear() {
        ids = emptySet()
        anchor = null
        isActive = false
    }

    /** The pages selected still shown. */
    private fun pruned(): Set<String> = pageIds.toSet()
}
