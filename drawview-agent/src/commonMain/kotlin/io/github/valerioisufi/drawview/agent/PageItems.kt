package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.MovePages
import io.github.valerioisufi.drawview.model.change.copyPages
import io.github.valerioisufi.drawview.model.change.pasteInto
import io.github.valerioisufi.drawview.model.change.RemovePage as RemovePageChange
import io.github.valerioisufi.drawview.model.change.RenameDocument as RenameChange
import io.github.valerioisufi.drawview.model.change.SetPageFormat as FormatChange

/**
 * The items of a proposal on the pages and on the document (Information Expert): whether what they
 * refer to is still as it was read, and the changes they become. See `docs/agent-full-access.md`.
 */
internal object PageItems {
    const val MIN_PAGE_SIDE_MM: Float = 20f
    const val MAX_PAGE_SIDE_MM: Float = 5000f
    const val MAX_TITLE_LENGTH: Int = 200
    val BACKGROUNDS: Set<String> = setOf("plain", "ruled", "grid", "dotted", "document")

    /** Whether [item] would not be applied to [document] as it is now. */
    fun isStale(document: DrawDocument, item: ProposalItem): Boolean = when (item) {
        is ProposalItem.RemovePage -> document.pageById(item.page)?.pageBase() != item.base
        is ProposalItem.MovePage -> document.pageById(item.page) == null || (item.after != null && document.pageById(item.after) == null)
        is ProposalItem.DuplicatePage -> document.pageById(item.page) == null || (item.after != null && document.pageById(item.after) == null)
        is ProposalItem.SetPageFormat -> document.pageById(item.page)?.pageBase() != item.base
        else -> false
    }

    /** The changes of [item], applied to [document] as it is now (not stale). */
    fun changesOf(document: DrawDocument, item: ProposalItem): List<DocumentChange> = when (item) {
        is ProposalItem.RemovePage -> listOf(RemovePageChange(item.page))
        is ProposalItem.MovePage -> {
            val others = document.pages.map { it.id }.filter { it != item.page }
            val at = if (item.after == null) 0 else others.indexOf(item.after) + 1
            listOfNotNull(MovePages.toOrder(document, others.take(at) + item.page + others.drop(at)))
        }
        is ProposalItem.DuplicatePage -> {
            val page = document.pageById(item.page)!!
            val after = document.pageById(item.after ?: item.page)!!
            listOfNotNull(document.copyPages(listOf(page)).pasteInto(document, document.pages.indexOf(after) + 1).change)
        }
        is ProposalItem.SetPageFormat -> {
            val page = document.pageById(item.page)!!
            listOf(FormatChange(page.id, item.widthMm ?: page.widthMm, item.heightMm ?: page.heightMm, backgroundOf(page, item)))
        }
        is ProposalItem.RenameDocument -> listOf(RenameChange(item.title.trim()))
        else -> emptyList()
    }

    /** The background [item] gives [page]: `null` for the one of the document. */
    private fun backgroundOf(page: DrawPage, item: ProposalItem.SetPageFormat): PageBackground? {
        val current = if (page.hasCustomBackground) page.background else null
        if (item.background == null && item.spacingMm == null && item.backgroundColor == null) return current
        if (item.background == "document") return null
        val base = current ?: page.background
        val color = item.backgroundColor?.let { ElementItems.parseColor(it) } ?: base.backgroundColor
        val kind = item.background ?: kindOf(base)
        return when (kind) {
            "plain" -> PageBackground.Solid(color)
            "ruled" -> (base as? PageBackground.Ruled ?: PageBackground.Ruled()).let { it.copy(backgroundColor = color, spacingMm = item.spacingMm ?: it.spacingMm) }
            "grid" -> (base as? PageBackground.Grid ?: PageBackground.Grid()).let { it.copy(backgroundColor = color, spacingMm = item.spacingMm ?: it.spacingMm) }
            else -> (base as? PageBackground.Dotted ?: PageBackground.Dotted()).let { it.copy(backgroundColor = color, spacingMm = item.spacingMm ?: it.spacingMm) }
        }
    }

    /** The name a model gives the kind of [background]. */
    fun kindOf(background: PageBackground): String = when (background) {
        is PageBackground.Solid -> "plain"
        is PageBackground.Ruled -> "ruled"
        is PageBackground.Grid -> "grid"
        is PageBackground.Dotted -> "dotted"
    }

    /** The page [item] changes, to change each page by one item only. */
    fun targetOf(item: ProposalItem): String? = when (item) {
        is ProposalItem.RemovePage -> item.page
        is ProposalItem.MovePage -> item.page
        is ProposalItem.SetPageFormat -> item.page
        else -> null
    }
}
