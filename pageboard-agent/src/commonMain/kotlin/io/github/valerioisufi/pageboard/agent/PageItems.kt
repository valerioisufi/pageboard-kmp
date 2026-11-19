package io.github.valerioisufi.pageboard.agent

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.TrashRetention
import io.github.valerioisufi.pageboard.model.change.AddPage
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import io.github.valerioisufi.pageboard.model.change.SetDocumentDefaults as DefaultsChange
import io.github.valerioisufi.pageboard.model.change.MovePages
import io.github.valerioisufi.pageboard.model.change.copyPages
import io.github.valerioisufi.pageboard.model.change.pasteInto
import io.github.valerioisufi.pageboard.model.change.RemovePage as RemovePageChange
import io.github.valerioisufi.pageboard.model.change.RenameDocument as RenameChange
import io.github.valerioisufi.pageboard.model.change.SetPageFormat as FormatChange

/**
 * The items of a proposal on the pages and on the document (Information Expert): whether what they
 * refer to is still as it was read, and the changes they become. See `docs/plans/agent-full-access.md`.
 */
internal object PageItems {
    const val MIN_PAGE_SIDE_MM: Float = 20f
    const val MAX_PAGE_SIDE_MM: Float = 5000f
    const val MAX_TITLE_LENGTH: Int = 200
    val BACKGROUNDS: Set<String> = setOf("plain", "ruled", "grid", "dotted", "document")

    /** Whether [item] would not be applied to [document] as it is now. */
    fun isStale(document: Document, item: ProposalItem): Boolean = when (item) {
        is ProposalItem.RemovePage -> document.pageById(item.page)?.pageBase() != item.base
        is ProposalItem.MovePage -> document.pageById(item.page) == null || (item.after != null && document.pageById(item.after) == null)
        is ProposalItem.DuplicatePage -> document.pageById(item.page) == null || (item.after != null && document.pageById(item.after) == null)
        is ProposalItem.SetPageFormat -> document.pageById(item.page)?.pageBase() != item.base
        is ProposalItem.RestorePages -> item.pages.any { restorable(document, it) == null }
        else -> false
    }

    /** The page [id] of the trash of the pages of [document], still kept there, or `null`. */
    fun restorable(document: Document, id: String, now: Long = currentTimeMillis()): Page? =
        document.removedPages[id]?.takeIf { page -> page.removed.let { it != null && it.time >= TrashRetention.expiredBefore(now) } }

    /** The changes of [item], applied to [document] as it is now (not stale). */
    fun changesOf(document: Document, item: ProposalItem): List<DocumentChange> = when (item) {
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
        is ProposalItem.SetDocumentDefaults -> listOf(
            DefaultsChange(
                item.widthMm ?: document.defaultWidthMm,
                item.heightMm ?: document.defaultHeightMm,
                backgroundFrom(document.defaultBackground, item.background, item.spacingMm, item.backgroundColor)
            )
        )
        // The change undoing their removal, as the editor puts pages back (PageManager.restorePages)
        is ProposalItem.RestorePages -> item.pages.distinct().map { AddPage.of(document.removedPages.getValue(it)) }
        else -> emptyList()
    }

    /** The background [item] gives [page]: `null` for the one of the document. */
    private fun backgroundOf(page: Page, item: ProposalItem.SetPageFormat): PageBackground? {
        val current = if (page.hasCustomBackground) page.background else null
        if (item.background == null && item.spacingMm == null && item.backgroundColor == null) return current
        if (item.background == "document") return null
        return backgroundFrom(current ?: page.background, item.background, item.spacingMm, item.backgroundColor)
    }

    /**
     * [base] of the [kind] given (`null`: its own), with [spacingMm] and [color] when given: what is
     * not given stays as in [base].
     */
    fun backgroundFrom(base: PageBackground, kind: String?, spacingMm: Float?, color: String?): PageBackground {
        val argb = color?.let { ElementItems.parseColor(it) } ?: base.backgroundColor
        return when (kind ?: kindOf(base)) {
            "plain" -> PageBackground.Solid(argb)
            "ruled" -> (base as? PageBackground.Ruled ?: PageBackground.Ruled()).let { it.copy(backgroundColor = argb, spacingMm = spacingMm ?: it.spacingMm) }
            "grid" -> (base as? PageBackground.Grid ?: PageBackground.Grid()).let { it.copy(backgroundColor = argb, spacingMm = spacingMm ?: it.spacingMm) }
            else -> (base as? PageBackground.Dotted ?: PageBackground.Dotted()).let { it.copy(backgroundColor = argb, spacingMm = spacingMm ?: it.spacingMm) }
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
