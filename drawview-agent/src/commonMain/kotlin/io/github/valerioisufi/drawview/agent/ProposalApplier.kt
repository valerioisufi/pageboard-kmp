package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.FractionalIndex
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.CompositeChange
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.PageData
import io.github.valerioisufi.drawview.model.change.RemoveElements
import io.github.valerioisufi.drawview.model.change.TextData
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.model.placement.PageOccupancy
import io.github.valerioisufi.drawview.model.placement.PageTextSizes
import io.github.valerioisufi.drawview.model.placement.TextFlow
import io.github.valerioisufi.drawview.model.placement.TextSizePolicy
import io.github.valerioisufi.drawview.model.text.TextSizes
import kotlin.math.ceil
import kotlin.math.max

/** The blue of what a model wrote, like the notes of the lecture assistant (see `docs/text-import.md`). */
public const val AGENT_TEXT_COLOR: Int = 0xFF1F5AA6.toInt()

/**
 * What [ProposalApplier.apply] did.
 *
 * @param outcomes the outcome of each item, in their order: accepted or stale.
 * @param inverse the change undoing them all, or `null` when nothing changed.
 */
public class AppliedProposal(public val outcomes: List<ItemOutcome>, public val inverse: DocumentChange?)

/**
 * Turns the items accepted by the user into one [CompositeChange]: one operation, one entry of the
 * history, undone at once. Each item is checked against the document as it is now: what changed since
 * the model read it is reported as stale, never overwritten.
 *
 * Used on the thread of the document.
 */
public class ProposalApplier(private val engines: AgentEngines = AgentEngines()) {
    private val textFlow = TextFlow()

    /** The indexes of [items] that would not be applied to [document] as it is now. */
    public fun staleItems(document: DrawDocument, items: List<ProposalItem>): Set<Int> = buildSet {
        val pages = plannedPages(document)
        for ((index, item) in items.withIndex()) {
            val valid = when (item) {
                is ProposalItem.ReplaceText -> document.findText(item.text)?.second?.base() == item.base
                is ProposalItem.RemoveText -> document.findText(item.text)?.second?.base() == item.base
                is ProposalItem.InsertText -> pages.anchorOf(item.page) != null
                is ProposalItem.AddPage -> pages.anchorOf(item.after) != null
            }
            if (!valid) add(index)
        }
    }

    /** Applies [items] to the document of [session], those still valid. */
    public fun apply(session: DocumentSession, items: List<ProposalItem>): AppliedProposal {
        val document = session.document
        if (session.isReadOnly) return AppliedProposal(items.map { ItemOutcome.STALE }, null)
        val stale = staleItems(document, items)
        val pages = plannedPages(document)
        val changes = mutableListOf<DocumentChange>()
        val outcomes = items.mapIndexed { index, item ->
            if (index in stale) return@mapIndexed ItemOutcome.STALE
            when (item) {
                is ProposalItem.ReplaceText -> {
                    val (page, text) = document.findText(item.text)!!
                    // The versions it was made from are the ones of the text: not a version made apart
                    changes += UpdateTexts(page.id, listOf(text.toData().copy(text = item.markdown)))
                }
                is ProposalItem.RemoveText -> {
                    val (page, _) = document.findText(item.text)!!
                    changes += RemoveElements(page.id, listOf(item.text))
                }
                is ProposalItem.InsertText -> changes += insertText(document, pages, item)
                is ProposalItem.AddPage -> changes += newPageAfter(pages, pages.anchorOf(item.after)!!)
            }
            ItemOutcome.ACCEPTED
        }
        if (changes.isEmpty()) return AppliedProposal(outcomes, null)
        return AppliedProposal(outcomes, session.submit(CompositeChange(changes)))
    }

    /** The changes placing the text of [item] on its page, then on new pages after it. */
    private fun insertText(document: DrawDocument, pages: MutableList<PlannedPage>, item: ProposalItem.InsertText): List<DocumentChange> {
        val anchor = pages.anchorOf(item.page)!!
        val page = pages[anchor]
        val fontSize = fontSizeFor(document, page)
        val newOccupancy = PageOccupancy(page.widthMm, page.heightMm, emptyList(), PageOccupancy.lineSpacingOf(page.background))
        val pieces = textFlow.flow(item.markdown, if (item.newPage) null else page.occupancy, newOccupancy) { text, width ->
            heightOf(document.id, text, width, fontSize)
        }
        val changes = mutableListOf<DocumentChange>()
        val ids = pieces.map { newElementId() }
        // The new pages of this text, one after the other
        var lastPage = anchor
        var pagesMade = 0
        for ((index, piece) in pieces.withIndex()) {
            while (pagesMade < piece.newPage) {
                changes += newPageAfter(pages, lastPage)
                lastPage++
                pagesMade++
            }
            val target = pages[if (piece.newPage == 0) anchor else lastPage]
            // A piece going on onto a new page goes on from the piece before it: one text across the pages
            val goesOn = pieces.getOrNull(index + 1)?.let { it.newPage > piece.newPage } == true
            val data = TextData(
                id = ids[index],
                text = piece.text,
                x = piece.box.left,
                y = piece.box.top,
                width = piece.box.width,
                color = AGENT_TEXT_COLOR,
                fontSize = fontSize,
                next = if (goesOn) ids[index + 1] else null
            )
            changes += AddElements(target.id, listOf(data))
            target.occupancy += piece.box
        }
        return changes
    }

    /** The change adding a page after the page [index] of [pages], and the page added to them. */
    private fun newPageAfter(pages: MutableList<PlannedPage>, index: Int): AddPage {
        val template = pages[index]
        val after = pages.drop(index + 1).firstOrNull { it.orderKey > template.orderKey }?.orderKey
        val key = FractionalIndex.between(template.orderKey, after)
        val id = newElementId()
        pages.add(
            index + 1,
            PlannedPage(
                id = id,
                orderKey = key,
                widthMm = template.widthMm,
                heightMm = template.heightMm,
                background = template.background,
                ownBackground = template.ownBackground,
                model = null,
                occupancy = PageOccupancy(template.widthMm, template.heightMm, emptyList(), PageOccupancy.lineSpacingOf(template.background))
            )
        )
        return AddPage(PageData(id, key, template.widthMm, template.heightMm, template.ownBackground))
    }

    private fun fontSizeFor(document: DrawDocument, page: PlannedPage): Float {
        val model = page.model
            ?: return TextSizePolicy.nearestPreset(PageTextSizes.ofDocument(document) ?: engines.defaultTextSize)
        val layout = model.pdfs.firstOrNull()?.let { engines.editors?.pdfLayout(document.id, it) }
        return TextSizePolicy.forNote(PageTextSizes.of(model, layout), engines.defaultTextSize)
    }

    /**
     * The height of a text as its editor draws it; without an editor, guessed with the lines it wraps
     * into (a character about half as wide as its line is high would be too narrow for most text).
     */
    private fun heightOf(documentId: String, markdown: String, widthMm: Float, fontSize: Float): Float {
        engines.editors?.textHeight(documentId, markdown, widthMm, fontSize)?.let { return it }
        val lineHeight = TextSizes.estimatedLineHeightMm(fontSize)
        val perLine = max(1f, widthMm / (lineHeight * CHARACTER_WIDTH_IN_LINES))
        return markdown.lines().sumOf { line -> max(1, ceil(line.length / perLine).toInt()) } * lineHeight
    }

    private fun plannedPages(document: DrawDocument): MutableList<PlannedPage> =
        document.pages.mapTo(mutableListOf()) { page ->
            PlannedPage(
                id = page.id,
                orderKey = page.orderKey,
                widthMm = page.widthMm,
                heightMm = page.heightMm,
                background = page.background,
                ownBackground = page.background.takeIf { page.hasCustomBackground },
                model = page,
                occupancy = PageOccupancy.of(page, pdfText = { pdf -> engines.editors?.pdfLayout(document.id, pdf) })
            )
        }

    /** The index of the page [id], or of the last page when `null`; `null` when there is no such page. */
    private fun List<PlannedPage>.anchorOf(id: String?): Int? =
        if (id == null) lastIndex.takeIf { it >= 0 } else indexOfFirst { it.id == id }.takeIf { it >= 0 }

    /** A page as the items applied so far leave it: of the document, or new ([model] `null`). */
    private class PlannedPage(
        val id: String,
        val orderKey: String,
        val widthMm: Float,
        val heightMm: Float,
        val background: PageBackground,
        val ownBackground: PageBackground?,
        val model: DrawPage?,
        var occupancy: PageOccupancy
    )

    private companion object {
        const val CHARACTER_WIDTH_IN_LINES = 0.42f
    }
}
