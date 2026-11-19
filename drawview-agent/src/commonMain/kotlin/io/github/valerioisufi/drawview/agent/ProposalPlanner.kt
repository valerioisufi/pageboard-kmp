package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.FractionalIndex
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.PageData
import io.github.valerioisufi.drawview.model.change.TextData
import io.github.valerioisufi.drawview.model.change.TextPlacement
import io.github.valerioisufi.drawview.model.change.toData
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.model.placement.BesideAnchor
import io.github.valerioisufi.drawview.model.placement.FreeSpace
import io.github.valerioisufi.drawview.model.placement.InMargin
import io.github.valerioisufi.drawview.model.placement.PageOccupancy
import io.github.valerioisufi.drawview.model.placement.PageTextSizes
import io.github.valerioisufi.drawview.model.placement.TextFlow
import io.github.valerioisufi.drawview.model.placement.TextSizePolicy
import io.github.valerioisufi.drawview.model.text.TextChains
import io.github.valerioisufi.drawview.model.text.TextSizes
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * A box a plan gives: of a new text, or of a text box or another element as it would be.
 *
 * @param textId the id it has, or would have when accepted.
 * @param markdown its text; empty for an element that is not a text.
 * @param fontSize its size in points; `0` for an element that is not a text.
 * @param pageId the page it is on, or would be on: a page added by the plan has an id of the plan only.
 * @param isOnNewPage whether the plan adds that page.
 * @param next the box of the plan this one goes on into.
 */
public class PlannedPiece(
    public val textId: String,
    public val markdown: String,
    public val pageId: String,
    public val isOnNewPage: Boolean,
    public val box: Rect,
    public val fontSize: Float,
    public val next: String? = null,
    public val color: Int = AGENT_TEXT_COLOR
)

/** What is wrong with where an item would go. None stops it: the user decides. */
public sealed interface PlacementWarning {
    /** The text is [byMm] from what it is about. */
    public data class FarFromAnchor(val byMm: Float) : PlacementWarning

    /** It did not fit on the page asked for, and begins on a new one. */
    public data object MovedToNewPage : PlacementWarning

    /** The text is in [pieces] boxes. */
    public data class Split(val pieces: Int) : PlacementWarning

    /** It is narrower than the [fromMm] the place wanted, for lack of room. */
    public data class Narrowed(val fromMm: Float) : PlacementWarning

    /** It would cover these: ids of elements, [INK] for handwriting, [PDF] for a PDF page. */
    public data class Overlaps(val with: List<String>) : PlacementWarning {
        public companion object {
            public const val INK: String = "ink"
            public const val PDF: String = "pdf"
        }
    }

    /** It is [byMm] higher than the area it was asked in. */
    public data class PastArea(val byMm: Float) : PlacementWarning

    /** It would be partly outside its page. */
    public data object OutsidePage : PlacementWarning

    /** It would pass the bottom margin of its page by [byMm]. */
    public data class PastPageEnd(val byMm: Float) : PlacementWarning

    /** A box of a chain: its part would pass the bottom margin by [byMm], and the chain is not flowed again. */
    public data class ChainOverflow(val byMm: Float) : PlacementWarning
}

/**
 * What an item of a proposal would do.
 *
 * @param isStale what it was made on changed: it would not be applied.
 * @param pieces the boxes of its text, or of the elements it moves or resizes as they would be; empty
 *   for an item that does neither.
 * @param pagesAdded how many pages it adds.
 * @param marks what it does on the pages, for the review to draw it there (see [PlannedMark]).
 */
public class PlannedItem(
    public val isStale: Boolean,
    public val pieces: List<PlannedPiece>,
    public val pagesAdded: Int,
    public val warnings: List<PlacementWarning>,
    internal val newPages: List<AddPage> = emptyList(),
    public val marks: List<PlannedMark> = emptyList()
) {
    /** This item with its [marks]. */
    internal fun marked(marks: List<PlannedMark>): PlannedItem = PlannedItem(isStale, pieces, pagesAdded, warnings, newPages, marks)
}

/**
 * What a list of items would do to a document as it is now.
 *
 * @param isMeasured whether the heights come from an editor of the document; otherwise they are
 *   estimates, those of the texts already there included.
 * @param pageOrder the ids of the pages in their order once the items are applied.
 */
public class ProposalPlan(
    public val isMeasured: Boolean,
    public val items: List<PlannedItem>,
    public val pageOrder: List<String>
)

/**
 * A place the user chose for a piece of a text, which replaces the one of the plan.
 *
 * @param pageId the page chosen; `null`: the page the plan gave it.
 */
public class ChosenPlace(public val pageId: String?, public val box: Rect)

/**
 * Plans a list of items on a document as it is (Pure Fabrication): where each text goes, at what size,
 * and what is wrong with it. A preview and [ProposalApplier.apply] are both this plan, so that they do
 * not disagree when the page has not changed. Every item sees the pages as the items before it leave
 * them.
 *
 * Used on the thread of the document. It changes nothing.
 */
public class ProposalPlanner(private val engines: AgentEngines = AgentEngines()) {
    private val textFlow = TextFlow()

    /**
     * The plan of [items].
     *
     * @param chosen the places chosen by the user, by index of an item and in the order of its pieces.
     */
    public fun plan(document: DrawDocument, items: List<ProposalItem>, chosen: Map<Int, List<ChosenPlace>> = emptyMap()): ProposalPlan {
        val measure = TextMeasure(document.id, engines.editors, engines.pages?.invoke())
        val pages = plannedPages(document)
        val grown = mutableMapOf<String, Rect>()
        val chains = lazy { TextChains.of(document) }
        val added = mutableMapOf<String, String>()
        val planned = items.mapIndexed { index, item ->
            if (isStale(document, pages, item)) return@mapIndexed PlannedItem(true, emptyList(), 0, emptyList())
            when (item) {
                is ProposalItem.ReplaceText -> planReplace(document, pages, item, measure, chains.value, grown)
                is ProposalItem.RemoveText -> {
                    val (page, _) = document.findText(item.text)!!
                    pages.first { it.id == page.id }.excluded += item.text
                    PlannedItem(false, emptyList(), 0, emptyList())
                }
                is ProposalItem.InsertText -> planInsert(document, pages, item, measure, grown, chosen[index], added)
                is ProposalItem.AddPage -> {
                    val page = newPageAfter(pages, pages.anchorOf(item.after)!!, added)
                    PlannedItem(false, emptyList(), 1, emptyList(), listOf(page))
                }
                is ProposalItem.RemoveElements -> {
                    pages.first { it.id == item.page }.excluded += item.elements.map { it.id }
                    PlannedItem(false, emptyList(), 0, emptyList())
                }
                is ProposalItem.RestyleText -> {
                    val (page, text) = document.findText(item.text)!!
                    planMoved(document, pages, page, listOf(moved(text, ElementItems.restyled(text, item), measure)), measure, grown, chains.value)
                }
                is ProposalItem.UpdateText -> {
                    val (page, text) = document.findText(item.text)!!
                    planMoved(document, pages, page, listOf(moved(text, ElementItems.updated(text, item), measure)), measure, grown, chains.value)
                }
                is ProposalItem.PlaceElements -> planPlace(document, pages, item, measure, grown, chains.value)
                is ProposalItem.ReorderElements,
                is ProposalItem.AddStroke, is ProposalItem.RestyleStroke, is ProposalItem.AddImage -> PlannedItem(false, emptyList(), 0, emptyList())
                is ProposalItem.RemovePage -> {
                    // What comes after it does not go on it
                    pages.removeAt(pages.indexOfFirst { it.id == item.page })
                    PlannedItem(false, emptyList(), 0, emptyList())
                }
                is ProposalItem.DuplicatePage -> PlannedItem(false, emptyList(), 1, emptyList())
                is ProposalItem.MovePage, is ProposalItem.SetPageFormat, is ProposalItem.RenameDocument,
                is ProposalItem.SetDocumentDefaults -> PlannedItem(false, emptyList(), 0, emptyList())
                is ProposalItem.RestorePages -> PlannedItem(false, emptyList(), item.pages.distinct().size, emptyList())
            }
        }
        // What each item does on the pages, for the review: from the document as it is and from its plan
        val marked = planned.mapIndexed { index, item -> item.marked(ProposalMarks.of(document, items[index], item, measure)) }
        return ProposalPlan(measure.isMeasured, marked, pages.map { it.id })
    }

    /** The geometry of [page] for a model: how its texts are laid out, and where there is room. */
    internal fun geometryOf(document: DrawDocument, page: DrawPage): PageGeometry {
        val measure = TextMeasure(document.id, engines.editors, engines.pages?.invoke())
        val occupancy = occupancyOf(document, PlannedPage(page.id, page.orderKey, page.widthMm, page.heightMm, page.background, null, page), measure)
        val boxes = page.texts.filter { it.removed == null }.associate { it.id to measure.boundsOf(it) }
        return PageGeometry(measure.isMeasured, boxes, textFlow.planner.freeSpace(occupancy).largestAreas(MAX_FREE_AREAS))
    }

    /** The indexes of [items] that would not be applied to [document] as it is now. */
    public fun staleItems(document: DrawDocument, items: List<ProposalItem>): Set<Int> {
        val pages = plannedPages(document)
        return items.indices.filterTo(mutableSetOf()) { isStale(document, pages, items[it]) }
    }

    private fun isStale(document: DrawDocument, pages: List<PlannedPage>, item: ProposalItem): Boolean = when (item) {
        is ProposalItem.ReplaceText -> document.findText(item.text)?.second?.base() != item.base
        is ProposalItem.RemoveText -> document.findText(item.text)?.second?.base() != item.base
        is ProposalItem.InsertText -> when {
            item.place == InsertPlace.INSIDE -> pages.anchorOf(item.page)?.let { item.area?.isInside(pages[it].widthMm, pages[it].heightMm) } != true
            item.near != null -> document.findText(item.near) == null
            item.nearArea != null -> pages.anchorOf(item.page)?.let { item.nearArea.isInside(pages[it].widthMm, pages[it].heightMm) } != true
            else -> pages.anchorOf(item.page) == null
        }
        is ProposalItem.AddPage -> pages.anchorOf(item.after) == null
        is ProposalItem.RemoveElements -> ElementItems.isStale(document.pageById(item.page), item.elements)
        is ProposalItem.PlaceElements -> ElementItems.isStale(document.pageById(item.page), item.elements) ||
            (item.toPage != null && document.pageById(item.toPage) == null)
        is ProposalItem.ReorderElements -> ElementItems.isStale(document.pageById(item.page), item.elements)
        is ProposalItem.RestyleText -> document.findText(item.text)?.second?.base() != item.base
        is ProposalItem.UpdateText -> document.findText(item.text)?.second?.base() != item.base
        is ProposalItem.AddStroke -> document.pageById(item.page) == null
        is ProposalItem.RestyleStroke -> ElementItems.findStroke(document, item.stroke)?.second?.elementBase() != item.base
        is ProposalItem.AddImage -> item.area == null || pages.anchorOf(item.page)?.let { pages[it].model } == null
        // A document keeps one page at least
        is ProposalItem.RemovePage -> PageItems.isStale(document, item) || pages.none { it.id == item.page } || pages.size <= 1
        is ProposalItem.MovePage, is ProposalItem.DuplicatePage, is ProposalItem.SetPageFormat,
        is ProposalItem.RestorePages -> PageItems.isStale(document, item)
        is ProposalItem.RenameDocument, is ProposalItem.SetDocumentDefaults -> false
    }

    private fun planReplace(
        document: DrawDocument,
        pages: List<PlannedPage>,
        item: ProposalItem.ReplaceText,
        measure: TextMeasure,
        chains: TextChains,
        grown: MutableMap<String, Rect>
    ): PlannedItem {
        val (page, text) = document.findText(item.text)!!
        val planned = pages.first { it.id == page.id }
        val before = measure.boundsOf(text)
        val after = text.getRotatedBounds(measure.height(item.markdown, text.width, text.fontSize))
        val warnings = mutableListOf<PlacementWarning>()
        // What it covered already is not its doing
        val covered = coveredBy(document, planned, setOf(text.id), after, before, measure)
        if (covered.isNotEmpty()) warnings += PlacementWarning.Overlaps(covered)
        val limit = page.heightMm - FreeSpace.DEFAULT_PAGE_MARGIN_MM
        if (after.bottom > limit && after.bottom > before.bottom) {
            warnings += if (chains.isChained(text.id)) PlacementWarning.ChainOverflow(after.bottom - limit) else PlacementWarning.PastPageEnd(after.bottom - limit)
        }
        planned.excluded += text.id
        planned.boxes += after
        grown[text.id] = after
        val piece = PlannedPiece(text.id, item.markdown, page.id, false, after, text.fontSize, text.next)
        return PlannedItem(false, listOf(piece), 0, warnings)
    }

    /** An element as an item would leave it: its box now and after, and its text when it is one. */
    private class Moved(val element: DrawElement, val before: Rect, val after: Rect, val markdown: String = "", val fontSize: Float = 0f, val grows: Boolean = false)

    /** [text] as [data] would leave it. */
    private fun moved(text: DrawText, data: TextData, measure: TextMeasure): Moved {
        val before = measure.boundsOf(text)
        val as_ = DrawText(text.id).also { data.applyTo(it) }
        val after = as_.getRotatedBounds(measure.height(data.text, data.width, data.fontSize))
        return Moved(text, before, after, data.text, data.fontSize, grows = after.bottom > before.bottom)
    }

    private fun planPlace(
        document: DrawDocument,
        pages: List<PlannedPage>,
        item: ProposalItem.PlaceElements,
        measure: TextMeasure,
        grown: MutableMap<String, Rect>,
        chains: TextChains
    ): PlannedItem {
        val page = document.pageById(item.page)!!
        val elements = ElementItems.elementsOf(page, item.elements)!!
        val matrix = ElementItems.matrixOf(page, elements, item) ?: return PlannedItem(false, emptyList(), 0, emptyList())
        val placements = ElementItems.placements(elements, matrix, item.rotation).associateBy { it.id }
        val moved = elements.mapNotNull { element ->
            if (element is DrawText) {
                val placement = placements[element.id] as? TextPlacement ?: return@mapNotNull null
                val data = element.toData().copy(x = placement.x, y = placement.y, width = placement.width, fontSize = placement.fontSize, rotation = placement.rotation)
                moved(element, data, measure).let { Moved(it.element, it.before, it.after, it.markdown, it.fontSize) }
            } else {
                page.boundsOf(element)?.let { Moved(element, it, matrix.mapRect(it)) }
            }
        }
        val target = item.toPage?.let { document.pageById(it) }?.takeIf { it.id != page.id }
            ?: return planMoved(document, pages, page, moved, measure, grown, chains)
        // Gone from their page, they come onto the other one: what they cover there is all new
        pages.first { it.id == page.id }.excluded += moved.map { it.element.id }
        return planMoved(document, pages, target, moved.map { Moved(it.element, Rect(0f, 0f, 0f, 0f), it.after, it.markdown, it.fontSize, it.grows) }, measure, grown, chains, fresh = true)
    }

    /**
     * The elements [moved] of [page] where an item would leave them: what they would newly cover, and
     * whether they would leave the page. The items after see them there.
     *
     * @param fresh they come from another page: nothing of what they cover was covered before.
     */
    private fun planMoved(
        document: DrawDocument,
        pages: List<PlannedPage>,
        page: DrawPage,
        moved: List<Moved>,
        measure: TextMeasure,
        grown: MutableMap<String, Rect>,
        chains: TextChains,
        fresh: Boolean = false
    ): PlannedItem {
        val planned = pages.first { it.id == page.id }
        val ids = moved.mapTo(mutableSetOf()) { it.element.id }
        val covered = linkedSetOf<String>()
        val limit = page.heightMm - FreeSpace.DEFAULT_PAGE_MARGIN_MM
        var past = 0f
        var chained = false
        var outside = false
        for (one in moved) {
            if (fresh || one.after != one.before) covered += coveredBy(document, planned, ids, one.after, one.before.takeIf { !fresh }, measure)
            if (one.after.left < -EDGE_MM || one.after.top < -EDGE_MM || one.after.right > page.widthMm + EDGE_MM) outside = true
            if (one.after.bottom > limit && (fresh || one.after.bottom > one.before.bottom)) {
                past = max(past, one.after.bottom - limit)
                if (one.grows && chains.isChained(one.element.id)) chained = true
            }
        }
        val warnings = buildList {
            if (covered.isNotEmpty()) add(PlacementWarning.Overlaps(covered.toList()))
            if (outside) add(PlacementWarning.OutsidePage)
            if (past > 0f) add(if (chained) PlacementWarning.ChainOverflow(past) else PlacementWarning.PastPageEnd(past))
        }
        planned.excluded += ids
        val pieces = moved.map { one ->
            planned.boxes += one.after
            grown[one.element.id] = one.after
            PlannedPiece(one.element.id, one.markdown, page.id, false, one.after, one.fontSize, (one.element as? DrawText)?.next)
        }
        return PlannedItem(false, pieces, 0, warnings)
    }

    /**
     * What [after], the box of a text or an element, would cover that [before], its box now, did not;
     * [moving]: what moves with it, which it does not cover.
     */
    private fun coveredBy(document: DrawDocument, page: PlannedPage, moving: Set<String>, after: Rect, before: Rect?, measure: TextMeasure): List<String> {
        fun new(box: Rect) = box.overlaps(after) && (before == null || !box.overlaps(before))
        val model = page.model ?: return if (page.boxes.any { new(it) }) listOf(NEW_TEXT) else emptyList()
        val covered = linkedSetOf<String>()
        for (element in model.elements()) {
            when {
                element.id in moving || element.id in page.excluded -> {}
                element is DrawText -> if (new(measure.boundsOf(element))) covered += element.id
                element is DrawStroke -> if (new(element.bounds)) covered += PlacementWarning.Overlaps.INK
                element is DrawPdf -> {
                    val others = model.elements().filter { it.id != element.id }.mapTo(mutableSetOf()) { it.id }
                    val pdf = PageOccupancy.of(model, pdfText = { engines.pdfLayout(document, it) }, pdfInk = { engines.pdfInk(document, it) }, excluded = others)
                    if (pdf.boxes.any { new(it) }) covered += PlacementWarning.Overlaps.PDF
                }
                else -> model.boundsOf(element)?.let { if (new(it)) covered += element.id }
            }
        }
        if (page.boxes.any { new(it) }) covered += NEW_TEXT
        return covered.toList()
    }

    private fun planInsert(
        document: DrawDocument,
        pages: MutableList<PlannedPage>,
        item: ProposalItem.InsertText,
        measure: TextMeasure,
        grown: Map<String, Rect>,
        chosen: List<ChosenPlace>?,
        added: MutableMap<String, String>
    ): PlannedItem {
        val anchorRect = item.near?.let { near ->
            grown[near] ?: document.findText(near)!!.second.let { measure.boundsOf(it) }
        } ?: item.nearArea?.rect
        val pageIndex = item.near?.let { near -> pages.anchorOf(document.findText(near)!!.first.id)!! } ?: pages.anchorOf(item.page)!!
        val page = pages[pageIndex]
        // A box to take the style from that is gone tells nothing: the page does
        val style = item.styleFrom?.let { document.findText(it)?.second }
        val fontSize = item.fontSize ?: style?.fontSize ?: fontSizeFor(document, page)
        val color = item.color?.let { ElementItems.parseColor(it) } ?: style?.color ?: AGENT_TEXT_COLOR
        if (item.place == InsertPlace.INSIDE) return planInside(document, pages, page, item, measure, fontSize, color, chosen)
        val usable = page.widthMm - 2 * FreeSpace.DEFAULT_PAGE_MARGIN_MM
        val asked = item.width?.coerceAtMost(usable)
        val firstPlace = when {
            anchorRect == null -> TextFlow.FirstPlace(BesideAnchor(null), asked ?: usable, asked ?: textFlow.minWidthMm)
            item.place == InsertPlace.MARGIN -> TextFlow.FirstPlace(InMargin(anchorRect), asked ?: minOf(MARGIN_WIDTH_MM, usable), asked ?: MARGIN_MIN_WIDTH_MM)
            else -> {
                val room = page.widthMm - FreeSpace.DEFAULT_PAGE_MARGIN_MM - max(anchorRect.left, FreeSpace.DEFAULT_PAGE_MARGIN_MM)
                TextFlow.FirstPlace(BesideAnchor(anchorRect), asked ?: max(room, textFlow.minWidthMm), asked ?: textFlow.minWidthMm)
            }
        }
        val pieces = textFlow.flow(
            item.markdown,
            // What it is about is not to be covered, even where the model saw more than the boxes tell
            if (item.newPage) null else occupancyOf(document, page, measure).let { taken -> item.nearArea?.let { taken + it.rect } ?: taken },
            emptyOccupancy(page),
            firstPlace,
            asked
        ) { text, width -> measure.height(text, width, fontSize) }

        val ids = pieces.map { newElementId() }
        val newPages = mutableListOf<AddPage>()
        var lastPage: Int? = null
        val created = mutableMapOf<Int, PlannedPage>()
        val targets = pieces.mapIndexed { index, piece ->
            // The place the user chose counts, on a page of the document: a new page only when one is left there
            val place = chosen?.getOrNull(index)
            place?.pageId?.let { id -> pages.firstOrNull { it.id == id } } ?: if (piece.newPage == 0) page else created.getOrPut(piece.newPage) {
                // The first one after its page and what the items before added there, the others after it
                val change = lastPage?.let { newPageAfter(pages, it) } ?: newPageAfter(pages, pageIndex, added)
                newPages += change
                added[page.id] = change.page.id
                pages.first { it.id == change.page.id }.also { lastPage = pages.indexOf(it) }
            }
        }
        val covered = linkedSetOf<String>()
        val planned = pieces.mapIndexed { index, piece ->
            val target = targets[index]
            val place = chosen?.getOrNull(index)
            val box = place?.box ?: piece.box
            // The planner found it free; where the user put it may not be
            if (place != null) covered += coveredBy(document, target, emptySet(), box, null, measure)
            target.boxes += box
            // A piece going on onto a later page goes on from the piece before it: one text across the pages
            val goesOn = pieces.getOrNull(index + 1)?.let { it.newPage > piece.newPage } == true && pages.indexOf(targets[index + 1]) > pages.indexOf(target)
            PlannedPiece(ids[index], piece.text, target.id, target.model == null, box, fontSize, if (goesOn) ids[index + 1] else null, color)
        }

        val warnings = insertWarnings(item, pieces, usable, firstPlace, anchorRect) +
            listOfNotNull(covered.takeIf { it.isNotEmpty() }?.let { PlacementWarning.Overlaps(it.toList()) })
        return PlannedItem(false, planned, newPages.size, warnings, newPages)
    }

    /**
     * The text of [item] in the area it asks for on [page]: at its top-left corner, as wide as the item
     * says or as the area, whatever is there. What it covers and how much higher it is are warned about.
     */
    private fun planInside(
        document: DrawDocument,
        pages: List<PlannedPage>,
        page: PlannedPage,
        item: ProposalItem.InsertText,
        measure: TextMeasure,
        fontSize: Float,
        color: Int,
        chosen: List<ChosenPlace>?
    ): PlannedItem {
        val area = item.area!!.rect
        val width = minOf(item.width ?: area.width, area.width)
        val height = measure.height(item.markdown, width, fontSize)
        val place = chosen?.firstOrNull()
        val target = place?.pageId?.let { id -> pages.firstOrNull { it.id == id } } ?: page
        val box = place?.box ?: Rect(area.left, area.top, area.left + width, area.top + height)
        val warnings = mutableListOf<PlacementWarning>()
        if (place == null && box.bottom > area.bottom + FITS_MARGIN_MM) warnings += PlacementWarning.PastArea(box.bottom - area.bottom)
        val limit = target.heightMm - FreeSpace.DEFAULT_PAGE_MARGIN_MM
        if (box.bottom > limit) warnings += PlacementWarning.PastPageEnd(box.bottom - limit)
        val covered = coveredBy(document, target, emptySet(), box, null, measure)
        if (covered.isNotEmpty()) warnings += PlacementWarning.Overlaps(covered)
        target.boxes += box
        return PlannedItem(false, listOf(PlannedPiece(newElementId(), item.markdown, target.id, target.model == null, box, fontSize, null, color)), 0, warnings)
    }

    private fun insertWarnings(
        item: ProposalItem.InsertText,
        pieces: List<TextFlow.Piece>,
        usable: Float,
        firstPlace: TextFlow.FirstPlace,
        anchor: Rect?
    ): List<PlacementWarning> = buildList {
        val first = pieces.firstOrNull() ?: return@buildList
        if (first.newPage > 0 && !item.newPage) add(PlacementWarning.MovedToNewPage)
        if (anchor != null && first.newPage == 0) {
            val gapX = max(0f, max(anchor.left - first.box.right, first.box.left - anchor.right))
            val gapY = max(0f, max(anchor.top - first.box.bottom, first.box.top - anchor.bottom))
            val distance = hypot(gapX, gapY)
            if (distance > FAR_FROM_ANCHOR_MM) add(PlacementWarning.FarFromAnchor(distance))
        }
        if (pieces.size > 1) add(PlacementWarning.Split(pieces.size))
        val wanted = minOf(firstPlace.widthMm, usable)
        if (first.box.width < wanted - NARROWED_MARGIN_MM) add(PlacementWarning.Narrowed(wanted))
    }

    private fun emptyOccupancy(page: PlannedPage) =
        PageOccupancy(page.widthMm, page.heightMm, emptyList(), PageOccupancy.lineSpacingOf(page.background))

    /**
     * What [page] holds as the items before leave it: the elements of the page, its texts at the height
     * [measure] tells, without what they removed or replaced, and what they added.
     */
    private fun occupancyOf(document: DrawDocument, page: PlannedPage, measure: TextMeasure): PageOccupancy {
        val model = page.model ?: return PageOccupancy(page.widthMm, page.heightMm, page.boxes.toList(), PageOccupancy.lineSpacingOf(page.background))
        val texts = model.texts.filter { it.removed == null }
        val others = PageOccupancy.of(model, pdfText = { engines.pdfLayout(document, it) }, pdfInk = { engines.pdfInk(document, it) }, excluded = texts.mapTo(mutableSetOf()) { it.id } + page.excluded)
        val boxes = texts.filter { it.id !in page.excluded }.map { measure.boundsOf(it) }
        return PageOccupancy(page.widthMm, page.heightMm, others.boxes + boxes + page.boxes, others.lineSpacingMm)
    }

    /**
     * The change adding a page after the page [anchor] of [pages] and the pages the items before added
     * after it ([added]: the last one, by the id of its page), so that they keep the order of their items.
     */
    private fun newPageAfter(pages: MutableList<PlannedPage>, anchor: Int, added: MutableMap<String, String>): AddPage {
        val anchorId = pages[anchor].id
        val last = added[anchorId]?.let { id -> pages.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: anchor
        return newPageAfter(pages, last).also { added[anchorId] = it.page.id }
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
                model = null
            )
        )
        return AddPage(PageData(id, key, template.widthMm, template.heightMm, template.ownBackground))
    }

    private fun fontSizeFor(document: DrawDocument, page: PlannedPage): Float {
        val model = page.model
            ?: return TextSizePolicy.nearestPreset(PageTextSizes.ofDocument(document) ?: engines.defaultTextSize)
        return TextSizePolicy.forNote(textSizesOf(document, model), engines.defaultTextSize)
    }

    /** The sizes of the characters on [page]: typed, of its PDF page, of its handwriting. */
    internal fun textSizesOf(document: DrawDocument, page: DrawPage): PageTextSizes =
        PageTextSizes.of(page, page.pdfs.firstOrNull { it.removed == null }?.let { engines.pdfLayout(document, it) })

    /** The size a text added to [page] takes when its model asks for none. */
    internal fun noteSizeOf(document: DrawDocument, page: DrawPage): Float =
        TextSizePolicy.forNote(textSizesOf(document, page), engines.defaultTextSize)

    private fun plannedPages(document: DrawDocument): MutableList<PlannedPage> =
        document.pages.mapTo(mutableListOf()) { page ->
            PlannedPage(
                id = page.id,
                orderKey = page.orderKey,
                widthMm = page.widthMm,
                heightMm = page.heightMm,
                background = page.background,
                ownBackground = page.background.takeIf { page.hasCustomBackground },
                model = page
            )
        }

    /** The index of the page [id], or of the last page when `null`; `null` when there is no such page. */
    private fun List<PlannedPage>.anchorOf(id: String?): Int? =
        if (id == null) lastIndex.takeIf { it >= 0 } else indexOfFirst { it.id == id }.takeIf { it >= 0 }

    /**
     * A page as the items planned so far leave it: of the document, or new ([model] `null`).
     *
     * @param excluded the elements of the page they removed or replaced.
     * @param boxes the boxes they took on it.
     */
    private class PlannedPage(
        val id: String,
        val orderKey: String,
        val widthMm: Float,
        val heightMm: Float,
        val background: PageBackground,
        val ownBackground: PageBackground?,
        val model: DrawPage?,
        val excluded: MutableSet<String> = mutableSetOf(),
        val boxes: MutableList<Rect> = mutableListOf()
    )

    public companion object {
        /** Named by [PlacementWarning.Overlaps] for a text an earlier item of the proposal placed. */
        public const val NEW_TEXT: String = "new_text"

        private const val MAX_FREE_AREAS = 8
        private const val MARGIN_WIDTH_MM = 60f
        private const val MARGIN_MIN_WIDTH_MM = 30f
        private const val FAR_FROM_ANCHOR_MM = 30f

        /** Past an edge of its page by less than this, a box is still on it. */
        private const val EDGE_MM = 0.5f

        /** Higher than its area by less than this, a text still fits it. */
        private const val FITS_MARGIN_MM = 0.5f

        /** Narrowed by less than this is only the width rounded to the grid of the planner. */
        private const val NARROWED_MARGIN_MM = 4f
    }
}

/**
 * What [ProposalPlanner.geometryOf] tells of a page.
 *
 * @param isMeasured whether the heights come from an editor.
 * @param textBoxes the box of each text shown.
 * @param freeAreas the largest free rectangles, as the placement sees them.
 */
internal class PageGeometry(val isMeasured: Boolean, val textBoxes: Map<String, Rect>, val freeAreas: List<Rect>)

/**
 * The heights of the texts of a document, as well as they can be told: laid out by its editor, or by
 * the [pages] of the application for a document in none, or estimated with the lines they wrap into.
 */
internal class TextMeasure(private val documentId: String, private val editors: AgentEditors?, private val pages: PageEngine? = null) {
    private val inEditor = editors?.isInEditor(documentId) == true
    private var estimated = false

    /** Whether every height told so far was laid out, not estimated. */
    val isMeasured: Boolean get() = (inEditor || pages != null) && !estimated

    /** The height of [markdown] on [widthMm] at [fontSize]. */
    fun height(markdown: String, widthMm: Float, fontSize: Float): Float {
        editors?.textHeight(documentId, markdown, widthMm, fontSize)?.let { return it }
        pages?.textHeight(markdown, widthMm, fontSize)?.let { return it }
        estimated = true
        val lineHeight = TextSizes.estimatedLineHeightMm(fontSize)
        // A character about half as wide as its line is high would be too narrow for most text
        val perLine = max(1f, widthMm / (lineHeight * CHARACTER_WIDTH_IN_LINES))
        return markdown.lines().sumOf { line -> max(1, ceil(line.length / perLine).toInt()) } * lineHeight
    }

    /**
     * The box of [text]: its editor laid it out and it keeps that height; in no editor its height is
     * not stored (see `docs/plans/text-heights.md`), so it is laid out again or estimated.
     */
    fun boundsOf(text: DrawText): Rect =
        if (inEditor) text.getRotatedBounds() else text.getRotatedBounds(height(text.text, text.width, text.fontSize))

    private companion object {
        const val CHARACTER_WIDTH_IN_LINES = 0.42f
    }
}
