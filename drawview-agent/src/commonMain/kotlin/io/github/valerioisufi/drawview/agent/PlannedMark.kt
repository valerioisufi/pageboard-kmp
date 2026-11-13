package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What an item of a proposal does on a page, for the review to draw it there before the user decides
 * (see `docs/agent-preview.md`). The marks are for the interface: they are not sent to a client.
 *
 * @param pageId the page it is on; a page the plan adds has an id of the plan only ([isOnNewPage]).
 * @param box the place on the page, in millimeters: of what is added, removed or changed, where what
 *   moves is now, the whole page for [Kind.PAGE_REMOVED] and [Kind.PAGE_CHANGED].
 * @param toBox where it goes, for [Kind.MOVED].
 * @param elementId the element it is about; `null` for what is not there yet and for a page.
 * @param markdown the text as it would be, for a text added or whose words or style change; empty otherwise.
 * @param fontSize the size of that text in points.
 * @param color the color of that text or of the stroke added, as ARGB.
 * @param points the points of a stroke added, in millimeters of the page: x and y of each one.
 * @param strokeWidth the width of that stroke in millimeters.
 */
public class PlannedMark(
    public val kind: Kind,
    public val pageId: String,
    public val box: Rect,
    public val toBox: Rect? = null,
    public val elementId: String? = null,
    public val markdown: String = "",
    public val fontSize: Float = 0f,
    public val color: Int = AGENT_TEXT_COLOR,
    public val points: List<Float> = emptyList(),
    public val strokeWidth: Float = 0f,
    public val isOnNewPage: Boolean = false
) {
    /** What happens at a mark. */
    public enum class Kind {
        /** Something new: a text, a stroke, an image. */
        ADDED,

        /** An element that goes. */
        REMOVED,

        /** An element that moves or is resized, from `box` to `toBox`. */
        MOVED,

        /** An element changed where it is: its words, its style, its place in the stack. */
        CHANGED,

        /** The whole page goes to the trash of the pages. */
        PAGE_REMOVED,

        /** The page is moved, copied or given another format. */
        PAGE_CHANGED,

        /** What the item was made on changed, or is gone: nothing would be done. Where it still is. */
        STALE
    }
}

/**
 * Makes the marks of the items of a plan (Pure Fabrication): from the document as it is, and from
 * what the planner found for each item (the boxes of its texts and of what it moves). An element is
 * changed by one item of a proposal only, so its box now is its box before the item.
 */
internal object ProposalMarks {

    /** The marks of [item], planned as [planned], on [document]. */
    fun of(document: DrawDocument, item: ProposalItem, planned: PlannedItem, measure: TextMeasure): List<PlannedMark> {
        if (planned.isStale) return stale(document, item, measure)
        return when (item) {
            is ProposalItem.InsertText -> planned.pieces.map { piece ->
                PlannedMark(PlannedMark.Kind.ADDED, piece.pageId, piece.box, markdown = piece.markdown, fontSize = piece.fontSize, color = piece.color, isOnNewPage = piece.isOnNewPage)
            }
            is ProposalItem.ReplaceText -> changedTexts(document, planned, measure, moves = false) { it.color }
            is ProposalItem.RestyleText -> changedTexts(document, planned, measure, moves = true) { ElementItems.restyled(it, item).color }
            is ProposalItem.UpdateText -> changedTexts(document, planned, measure, moves = true) { ElementItems.updated(it, item).color }
            is ProposalItem.RemoveText -> elementMarks(document, listOf(item.text), PlannedMark.Kind.REMOVED, measure)
            is ProposalItem.RemoveElements -> elementMarks(document, item.elements.map { it.id }, PlannedMark.Kind.REMOVED, measure)
            is ProposalItem.ReorderElements -> elementMarks(document, item.elements.map { it.id }, PlannedMark.Kind.CHANGED, measure)
            is ProposalItem.RestyleStroke -> elementMarks(document, listOf(item.stroke), PlannedMark.Kind.CHANGED, measure)
            is ProposalItem.PlaceElements -> planned.pieces.mapNotNull { piece ->
                val (page, element) = find(document, piece.textId) ?: return@mapNotNull null
                val before = boxOf(page, element, measure) ?: return@mapNotNull null
                PlannedMark(PlannedMark.Kind.MOVED, page.id, before, toBox = piece.box, elementId = element.id)
            }
            is ProposalItem.AddStroke -> listOfNotNull(strokeMark(item))
            is ProposalItem.AddImage -> {
                val page = item.page?.let { document.pageById(it) } ?: document.pages.lastOrNull()
                val box = item.area?.rect
                if (page == null || box == null) emptyList() else listOf(PlannedMark(PlannedMark.Kind.ADDED, page.id, box))
            }
            is ProposalItem.RemovePage -> pageMark(document, item.page, PlannedMark.Kind.PAGE_REMOVED)
            is ProposalItem.MovePage -> pageMark(document, item.page, PlannedMark.Kind.PAGE_CHANGED)
            is ProposalItem.DuplicatePage -> pageMark(document, item.page, PlannedMark.Kind.PAGE_CHANGED)
            is ProposalItem.SetPageFormat -> pageMark(document, item.page, PlannedMark.Kind.PAGE_CHANGED)
            is ProposalItem.AddPage, is ProposalItem.RenameDocument -> emptyList()
        }
    }

    /**
     * The texts an item changes, as the plan leaves them: their new words and style where they would
     * be, and their way there when the item [moves] them (a box only growing with its words does not
     * move).
     */
    private fun changedTexts(document: DrawDocument, planned: PlannedItem, measure: TextMeasure, moves: Boolean, color: (DrawText) -> Int): List<PlannedMark> =
        planned.pieces.flatMap { piece ->
            val (page, text) = document.findText(piece.textId) ?: return@flatMap emptyList()
            val before = measure.boundsOf(text)
            buildList {
                if (moves && isMoved(before, piece.box)) add(PlannedMark(PlannedMark.Kind.MOVED, page.id, before, toBox = piece.box, elementId = text.id))
                add(PlannedMark(PlannedMark.Kind.CHANGED, page.id, piece.box, elementId = text.id, markdown = piece.markdown, fontSize = piece.fontSize, color = color(text)))
            }
        }

    /** Whether a box went elsewhere or changed its width: its height follows its words. */
    private fun isMoved(before: Rect, after: Rect): Boolean =
        abs(before.left - after.left) > MOVED_MM || abs(before.top - after.top) > MOVED_MM || abs(before.width - after.width) > MOVED_MM

    private fun elementMarks(document: DrawDocument, ids: List<String>, kind: PlannedMark.Kind, measure: TextMeasure): List<PlannedMark> = ids.mapNotNull { id ->
        val (page, element) = find(document, id) ?: return@mapNotNull null
        val box = boxOf(page, element, measure) ?: return@mapNotNull null
        PlannedMark(kind, page.id, box, elementId = id)
    }

    private fun pageMark(document: DrawDocument, pageId: String, kind: PlannedMark.Kind): List<PlannedMark> =
        listOfNotNull(document.pageById(pageId)?.let { PlannedMark(kind, it.id, it.rect()) })

    /** The stroke [item] draws: its points as they are given, in the box holding them. */
    private fun strokeMark(item: ProposalItem.AddStroke): PlannedMark? {
        val points = item.points.filter { it.size >= 2 }
        if (points.isEmpty()) return null
        val width = item.width ?: ElementItems.DEFAULT_STROKE_WIDTH
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        for (point in points) {
            left = min(left, point[0])
            right = max(right, point[0])
            top = min(top, point[1])
            bottom = max(bottom, point[1])
        }
        return PlannedMark(
            PlannedMark.Kind.ADDED,
            item.page,
            Rect(left - width / 2f, top - width / 2f, right + width / 2f, bottom + width / 2f),
            color = item.color?.let { ElementItems.parseColor(it) } ?: AGENT_TEXT_COLOR,
            points = points.flatMap { listOf(it[0], it[1]) },
            strokeWidth = width
        )
    }

    /** What a stale item was about, where it still is: nothing when it is gone. */
    private fun stale(document: DrawDocument, item: ProposalItem, measure: TextMeasure): List<PlannedMark> {
        val pageId = when (item) {
            is ProposalItem.RemovePage -> item.page
            is ProposalItem.MovePage -> item.page
            is ProposalItem.DuplicatePage -> item.page
            is ProposalItem.SetPageFormat -> item.page
            else -> null
        }
        if (pageId != null) return pageMark(document, pageId, PlannedMark.Kind.STALE)
        return elementMarks(document, ElementItems.targetsOf(item), PlannedMark.Kind.STALE, measure)
    }

    /** The element [id] shown in [document], with its page. */
    private fun find(document: DrawDocument, id: String): Pair<DrawPage, DrawElement>? {
        for (page in document.pages) {
            val element = page.elements().firstOrNull { it.id == id && it.removed == null }
            if (element != null) return page to element
        }
        return null
    }

    /** The box of [element] on [page]: a text at the height it is laid out at. */
    private fun boxOf(page: DrawPage, element: DrawElement, measure: TextMeasure): Rect? =
        if (element is DrawText) measure.boundsOf(element) else page.boundsOf(element)

    /** Boxes closer than this are the same place. */
    private const val MOVED_MM = 0.05f
}
