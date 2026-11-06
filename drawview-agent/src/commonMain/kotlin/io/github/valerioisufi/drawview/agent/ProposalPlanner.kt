package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.FractionalIndex
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.PageData
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
 * A box of text a plan gives: new, or a text box as it would be.
 *
 * @param textId the id it has, or would have when accepted.
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
    public val next: String? = null
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

    /** It would pass the bottom margin of its page by [byMm]. */
    public data class PastPageEnd(val byMm: Float) : PlacementWarning

    /** A box of a chain: its part would pass the bottom margin by [byMm], and the chain is not flowed again. */
    public data class ChainOverflow(val byMm: Float) : PlacementWarning
}

/**
 * What an item of a proposal would do.
 *
 * @param isStale what it was made on changed: it would not be applied.
 * @param pieces the boxes of its text, empty for an item without text.
 * @param pagesAdded how many pages it adds.
 */
public class PlannedItem(
    public val isStale: Boolean,
    public val pieces: List<PlannedPiece>,
    public val pagesAdded: Int,
    public val warnings: List<PlacementWarning>,
    internal val newPages: List<AddPage> = emptyList()
)

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
        val measure = TextMeasure(document.id, engines.editors)
        val pages = plannedPages(document)
        val grown = mutableMapOf<String, Rect>()
        val chains = lazy { TextChains.of(document) }
        val planned = items.mapIndexed { index, item ->
            if (isStale(document, pages, item)) return@mapIndexed PlannedItem(true, emptyList(), 0, emptyList())
            when (item) {
                is ProposalItem.ReplaceText -> planReplace(document, pages, item, measure, chains.value, grown)
                is ProposalItem.RemoveText -> {
                    val (page, _) = document.findText(item.text)!!
                    pages.first { it.id == page.id }.excluded += item.text
                    PlannedItem(false, emptyList(), 0, emptyList())
                }
                is ProposalItem.InsertText -> planInsert(document, pages, item, measure, grown, chosen[index])
                is ProposalItem.AddPage -> {
                    val page = newPageAfter(pages, pages.anchorOf(item.after)!!)
                    PlannedItem(false, emptyList(), 1, emptyList(), listOf(page))
                }
                is ProposalItem.RemoveElements -> {
                    pages.first { it.id == item.page }.excluded += item.elements.map { it.id }
                    PlannedItem(false, emptyList(), 0, emptyList())
                }
                is ProposalItem.PlaceElements, is ProposalItem.ReorderElements, is ProposalItem.RestyleText,
                is ProposalItem.AddStroke, is ProposalItem.RestyleStroke, is ProposalItem.AddImage -> PlannedItem(false, emptyList(), 0, emptyList())
            }
        }
        return ProposalPlan(measure.isMeasured, planned, pages.map { it.id })
    }

    /** The geometry of [page] for a model: how its texts are laid out, and where there is room. */
    internal fun geometryOf(document: DrawDocument, page: DrawPage): PageGeometry {
        val measure = TextMeasure(document.id, engines.editors)
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
            item.near != null -> document.findText(item.near) == null
            item.nearArea != null -> pages.anchorOf(item.page)?.let { item.nearArea.isInside(pages[it].widthMm, pages[it].heightMm) } != true
            else -> pages.anchorOf(item.page) == null
        }
        is ProposalItem.AddPage -> pages.anchorOf(item.after) == null
        is ProposalItem.RemoveElements -> ElementItems.isStale(document.pageById(item.page), item.elements)
        is ProposalItem.PlaceElements -> ElementItems.isStale(document.pageById(item.page), item.elements)
        is ProposalItem.ReorderElements -> ElementItems.isStale(document.pageById(item.page), item.elements)
        is ProposalItem.RestyleText -> document.findText(item.text)?.second?.base() != item.base
        is ProposalItem.AddStroke -> document.pageById(item.page) == null
        is ProposalItem.RestyleStroke -> ElementItems.findStroke(document, item.stroke)?.second?.elementBase() != item.base
        is ProposalItem.AddImage -> item.area == null || pages.anchorOf(item.page)?.let { pages[it].model } == null
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
        val covered = coveredBy(document, planned, text, after, before, measure)
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

    /** What [after], the new box of [text], would cover that [before], its box now, did not. */
    private fun coveredBy(document: DrawDocument, page: PlannedPage, text: DrawText, after: Rect, before: Rect, measure: TextMeasure): List<String> {
        val model = page.model ?: return emptyList()
        fun new(box: Rect) = box.overlaps(after) && !box.overlaps(before)
        val covered = linkedSetOf<String>()
        for (element in model.elements()) {
            when {
                element.id == text.id || element.id in page.excluded -> {}
                element is DrawText -> if (new(measure.boundsOf(element))) covered += element.id
                element is DrawStroke -> if (new(element.bounds)) covered += PlacementWarning.Overlaps.INK
                element is DrawPdf -> {
                    val others = model.elements().filter { it.id != element.id }.mapTo(mutableSetOf()) { it.id }
                    val pdf = PageOccupancy.of(model, pdfText = { engines.editors?.pdfLayout(document.id, it) }, excluded = others)
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
        chosen: List<ChosenPlace>?
    ): PlannedItem {
        val anchorRect = item.near?.let { near ->
            grown[near] ?: document.findText(near)!!.second.let { measure.boundsOf(it) }
        } ?: item.nearArea?.rect
        val pageIndex = item.near?.let { near -> pages.anchorOf(document.findText(near)!!.first.id)!! } ?: pages.anchorOf(item.page)!!
        val page = pages[pageIndex]
        val fontSize = fontSizeFor(document, page)
        val usable = page.widthMm - 2 * FreeSpace.DEFAULT_PAGE_MARGIN_MM
        val firstPlace = when {
            anchorRect == null -> TextFlow.FirstPlace(BesideAnchor(null), usable, textFlow.minWidthMm)
            item.place == InsertPlace.MARGIN -> TextFlow.FirstPlace(InMargin(anchorRect), minOf(MARGIN_WIDTH_MM, usable), MARGIN_MIN_WIDTH_MM)
            else -> {
                val room = page.widthMm - FreeSpace.DEFAULT_PAGE_MARGIN_MM - max(anchorRect.left, FreeSpace.DEFAULT_PAGE_MARGIN_MM)
                TextFlow.FirstPlace(BesideAnchor(anchorRect), max(room, textFlow.minWidthMm), textFlow.minWidthMm)
            }
        }
        val pieces = textFlow.flow(
            item.markdown,
            // What it is about is not to be covered, even where the model saw more than the boxes tell
            if (item.newPage) null else occupancyOf(document, page, measure).let { taken -> item.nearArea?.let { taken + it.rect } ?: taken },
            emptyOccupancy(page),
            firstPlace
        ) { text, width -> measure.height(text, width, fontSize) }

        val ids = pieces.map { newElementId() }
        val newPages = mutableListOf<AddPage>()
        var lastPage = pageIndex
        val created = mutableMapOf<Int, PlannedPage>()
        val targets = pieces.mapIndexed { index, piece ->
            // The place the user chose counts, on a page of the document: a new page only when one is left there
            val place = chosen?.getOrNull(index)
            place?.pageId?.let { id -> pages.firstOrNull { it.id == id } } ?: if (piece.newPage == 0) page else created.getOrPut(piece.newPage) {
                newPages += newPageAfter(pages, lastPage)
                lastPage++
                pages[lastPage]
            }
        }
        val planned = pieces.mapIndexed { index, piece ->
            val target = targets[index]
            val box = chosen?.getOrNull(index)?.box ?: piece.box
            target.boxes += box
            // A piece going on onto a later page goes on from the piece before it: one text across the pages
            val goesOn = pieces.getOrNull(index + 1)?.let { it.newPage > piece.newPage } == true && pages.indexOf(targets[index + 1]) > pages.indexOf(target)
            PlannedPiece(ids[index], piece.text, target.id, target.model == null, box, fontSize, if (goesOn) ids[index + 1] else null)
        }

        return PlannedItem(false, planned, newPages.size, insertWarnings(item, pieces, usable, firstPlace, anchorRect), newPages)
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
        val others = PageOccupancy.of(model, pdfText = { engines.editors?.pdfLayout(document.id, it) }, excluded = texts.mapTo(mutableSetOf()) { it.id } + page.excluded)
        val boxes = texts.filter { it.id !in page.excluded }.map { measure.boundsOf(it) }
        return PageOccupancy(page.widthMm, page.heightMm, others.boxes + boxes + page.boxes, others.lineSpacingMm)
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
        val layout = model.pdfs.firstOrNull()?.let { engines.editors?.pdfLayout(document.id, it) }
        return TextSizePolicy.forNote(PageTextSizes.of(model, layout), engines.defaultTextSize)
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
 * The heights of the texts of a document, as well as they can be told: laid out by its editor, or
 * estimated with the lines they wrap into.
 */
internal class TextMeasure(private val documentId: String, private val editors: AgentEditors?) {
    private val inEditor = editors?.isInEditor(documentId) == true
    private var estimated = false

    /** Whether every height told so far comes from an editor. */
    val isMeasured: Boolean get() = inEditor && !estimated

    /** The height of [markdown] on [widthMm] at [fontSize]. */
    fun height(markdown: String, widthMm: Float, fontSize: Float): Float {
        editors?.textHeight(documentId, markdown, widthMm, fontSize)?.let { return it }
        estimated = true
        val lineHeight = TextSizes.estimatedLineHeightMm(fontSize)
        // A character about half as wide as its line is high would be too narrow for most text
        val perLine = max(1f, widthMm / (lineHeight * CHARACTER_WIDTH_IN_LINES))
        return markdown.lines().sumOf { line -> max(1, ceil(line.length / perLine).toInt()) } * lineHeight
    }

    /** The box of [text]: the height its editor laid out keeps it, without one it is estimated. */
    fun boundsOf(text: DrawText): Rect =
        if (inEditor) text.getRotatedBounds() else text.getRotatedBounds(height(text.text, text.width, text.fontSize))

    private companion object {
        const val CHARACTER_WIDTH_IN_LINES = 0.42f
    }
}
