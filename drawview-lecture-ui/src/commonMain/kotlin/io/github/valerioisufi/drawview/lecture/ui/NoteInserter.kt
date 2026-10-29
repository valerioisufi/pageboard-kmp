package io.github.valerioisufi.drawview.lecture.ui

import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.PlacementItem
import io.github.valerioisufi.drawview.canvas.controller.PlacementRequest
import io.github.valerioisufi.drawview.canvas.controller.PlacementSpot
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.lecture.LectureAnchors
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.lecture.resolvedPageIndex
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.geometry.Rect as MmRect
import io.github.valerioisufi.drawview.model.placement.BesideAnchor
import io.github.valerioisufi.drawview.model.placement.PageOccupancy
import io.github.valerioisufi.drawview.model.placement.PlacementBlock
import io.github.valerioisufi.drawview.model.placement.ReadingOrder
import io.github.valerioisufi.drawview.model.placement.TextFlow
import io.github.valerioisufi.drawview.model.text.TextSizes
import kotlin.math.max
import kotlin.math.min

/**
 * Inserts the content of the assistant in the notebook as Markdown text blocks (undoable, saved
 * like the texts typed by the student), in a color telling them apart from the student's notes.
 *
 * @param textAdapter lays the blocks out as the canvas draws them, to size them.
 */
internal class NoteInserter(private val textAdapter: TextTileAdapter) {

    private val textFlow = TextFlow()

    /**
     * Lets the student place [suggestion]: the note is shown in the best free place of its page
     * (or of the page shown), next to what the student was writing when it was said, the other
     * places found a key or a button away; a tap on it confirms, a drag moves it, a tap elsewhere
     * puts it there (see [io.github.valerioisufi.drawview.canvas.controller.PlacementManager]).
     * Placed, it is selected so that it can be moved or resized.
     *
     * @param lecture when the lecture was recorded (wall clock), to find what the note is about.
     * @param onInserted called once the note is in the notebook.
     * @param onCancel called when the placement is cancelled.
     */
    fun startPlacement(
        controller: DrawViewController,
        suggestion: NoteSuggestion,
        lecture: LongRange?,
        onInserted: () -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        val document = controller.document
        val pageIndex = suggestion.resolvedPageIndex(document) ?: controller.viewport.firstVisiblePageIndex() ?: return false
        val page = document.pages.getOrNull(pageIndex) ?: return false
        val content = contentOf(suggestion)
        val block = noteBlock(content, page)
        val spots = controller.placementManager.spotsFor(pageIndex, block, BesideAnchor(anchorOf(page, suggestion, lecture)))
        val first = spots.firstOrNull()?.box
        val widthMm = first?.width ?: block.preferredWidthMm
        val note = newText(content, widthMm, measureHeightMm(content, NOTE_FONT_PT, widthMm), NOTE_FONT_PT)
        if (spots.isEmpty()) controller.pageManager.jumpToPage(pageIndex)

        controller.placementManager.start(
            PlacementRequest(
                preview = note,
                spots = spots,
                label = suggestion.title.ifBlank { suggestion.content.lineSequence().first() }.trim(),
                onPlace = { spot ->
                    controller.historyManager.group {
                        val target = controller.placementManager.pagesFor(listOf(spot)).single() ?: return@group
                        place(controller, target, note, spot)
                        controller.selectionManager.selectText(target.index, note)
                    }
                    onInserted()
                },
                onCancel = onCancel
            )
        )
        return true
    }

    /**
     * Lets the student place every note of [suggestions] at once: each in the best free place of
     * its page once the previous ones are there, next to what was being written when it was said;
     * the notes that find no room go on a new page after their page. Confirmed together, undone
     * together.
     *
     * @param onInserted called with the notes put in the notebook (those that fit nowhere stay out).
     */
    fun startPlacementAll(
        controller: DrawViewController,
        suggestions: List<NoteSuggestion>,
        lecture: LongRange?,
        label: String,
        onInserted: (List<NoteSuggestion>) -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        val document = controller.document
        val placement = controller.placementManager
        val visible = controller.viewport.firstVisiblePageIndex()
        val items = mutableListOf<PlacementItem>()
        val placed = mutableListOf<NoteSuggestion>()
        val byPage = suggestions.groupBy { it.resolvedPageIndex(document) ?: visible }
        for ((pageIndex, notes) in byPage.entries.sortedBy { it.key ?: Int.MAX_VALUE }) {
            val page = pageIndex?.let { document.pages.getOrNull(it) } ?: continue
            var occupancy = placement.occupancyOf(page.index)
            var newPage = PageOccupancy(page.widthMm, page.heightMm, emptyList(), PageOccupancy.lineSpacingOf(page.background))
            for (suggestion in notes.sortedBy { it.atMs }) {
                val content = contentOf(suggestion)
                val block = noteBlock(content, page)
                val onPage = placement.planner.candidates(occupancy, block, BesideAnchor(anchorOf(page, suggestion, lecture))).firstOrNull()
                val spot = if (onPage != null) {
                    occupancy += onPage.rect
                    PlacementSpot(page.index, onPage.rect)
                } else {
                    val next = placement.planner.candidates(newPage, block, ReadingOrder).firstOrNull() ?: continue
                    newPage += next.rect
                    PlacementSpot(page.index, next.rect, newPageAfter = true)
                }
                val note = newText(content, spot.box.width, spot.box.height, NOTE_FONT_PT)
                items += PlacementItem(note, listOf(spot))
                placed += suggestion
            }
        }
        if (items.isEmpty()) return false

        placement.start(
            PlacementRequest(
                items = items,
                label = label,
                onPlace = { spots ->
                    controller.historyManager.group {
                        val pages = placement.pagesFor(spots)
                        for ((index, spot) in spots.withIndex()) {
                            val target = pages[index] ?: continue
                            place(controller, target, items[index].preview, spot)
                        }
                    }
                    onInserted(placed)
                },
                onCancel = onCancel
            )
        )
        return true
    }

    /** The Markdown of [suggestion] in the notebook: its title in bold, then its content. */
    private fun contentOf(suggestion: NoteSuggestion) = buildString {
        if (suggestion.title.isNotBlank()) appendLine("**${suggestion.title.trim()}**")
        append(suggestion.content.trim())
    }

    /** A note of [content] to place on [page]: as wide as a note, its height laid out on its width. */
    private fun noteBlock(content: String, page: DrawPage) =
        PlacementBlock(noteWidthMm(page)) { width -> measureHeightMm(content, NOTE_FONT_PT, width) }

    /** What [suggestion] is about on [page]: the writing of the moment it was said during [lecture]. */
    private fun anchorOf(page: DrawPage, suggestion: NoteSuggestion, lecture: LongRange?): MmRect? {
        if (lecture == null || suggestion.fromFinalReview) return null
        return LectureAnchors.anchorOf(page, lecture.first + suggestion.atMs, lecture.first, lecture.last)
    }

    /** Adds [note] to [page] in the box of [spot], its height laid out again on its width. */
    private fun place(controller: DrawViewController, page: DrawPage, note: DrawText, spot: PlacementSpot) {
        note.width = min(spot.box.width, page.widthMm)
        note.height = measureHeightMm(note.text, note.fontSize, note.width)
        note.x = spot.box.left.coerceIn(0f, max(0f, page.widthMm - note.width))
        note.y = spot.box.top.coerceIn(0f, max(0f, page.heightMm - note.height))
        note.markDirty()
        addText(controller, page, note)
    }

    /**
     * Lets the student place the notes [markdown] (the review of a lecture): flowed through the
     * free space of page [startPageIndex], then onto as many new pages after it as needed (on the
     * format of the document, which the text is laid out on), split between paragraphs, never in
     * a formula, a code block or a list item; at the end of the notebook when [startPageIndex] is
     * `null`. Shown before it is confirmed, undone at once; the first piece shown once inserted.
     */
    fun startReviewPlacement(controller: DrawViewController, markdown: String, startPageIndex: Int?, label: String, onInserted: () -> Unit = {}): Boolean {
        val document = controller.document
        if (document.pages.isEmpty()) return false
        val start = startPageIndex?.takeIf { it in document.pages.indices }
        val anchorIndex = start ?: document.pages.lastIndex
        val first = start?.let { controller.placementManager.occupancyOf(it) }
        val newPage = PageOccupancy(document.defaultWidthMm, document.defaultHeightMm, emptyList(), PageOccupancy.lineSpacingOf(document.defaultBackground))
        val pieces = textFlow.flow(markdown, first, newPage) { text, width -> measureHeightMm(text, REVIEW_FONT_PT, width) }
        if (pieces.isEmpty()) return false
        val items = pieces.map { piece ->
            PlacementItem(newText(piece.text, piece.box.width, piece.box.height, REVIEW_FONT_PT), listOf(PlacementSpot(anchorIndex, piece.box, piece.newPage)))
        }
        controller.selectionManager.clearSelection()
        controller.placementManager.start(
            PlacementRequest(
                items = items,
                label = label,
                onPlace = { spots ->
                    val pages = controller.historyManager.group {
                        controller.placementManager.pagesFor(spots, template = { null }).also { pages ->
                            for ((index, spot) in spots.withIndex()) pages[index]?.let { place(controller, it, items[index].preview, spot) }
                        }
                    }
                    pages.firstOrNull()?.let { showPageTop(controller, it.index) }
                    onInserted()
                }
            )
        )
        if (start == null) controller.pageManager.jumpToPage(anchorIndex)
        return true
    }

    /** Shows page [pageIndex] with its top edge near the top of the viewport. */
    private fun showPageTop(controller: DrawViewController, pageIndex: Int) {
        controller.pageManager.jumpToPage(pageIndex)
        val rect = controller.viewport.visiblePages().firstOrNull { it.index == pageIndex }?.rect ?: return
        if (rect.height > controller.viewport.height) controller.viewport.panBy(0f, PAGE_TOP_INSET_PX - rect.top)
    }

    private fun noteWidthMm(page: DrawPage): Float =
        min(NOTE_WIDTH_MM, page.widthMm * 0.45f).coerceAtLeast(min(30f, page.widthMm))

    private fun newText(content: String, widthMm: Float, heightMm: Float, fontSizePt: Float) = DrawText().apply {
        text = content
        width = widthMm
        height = heightMm
        color = ASSISTANT_TEXT_COLOR
        fontSize = fontSizePt
        markDirty()
    }

    private fun addText(controller: DrawViewController, page: DrawPage, item: DrawText) {
        controller.session.submit(AddElements.of(page, listOf(item)))
    }

    /** Height in millimeters of [markdown] laid out on [widthMm], as the canvas draws it. */
    fun measureHeightMm(markdown: String, fontSizePt: Float, widthMm: Float): Float {
        val probe = newText(markdown, widthMm, 0f, fontSizePt)
        return textAdapter.measure(probe)?.height ?: (markdown.lines().size * TextSizes.estimatedLineHeightMm(fontSizePt))
    }

    private companion object {
        const val NOTE_WIDTH_MM = 90f
        const val NOTE_FONT_PT = 11f
        const val REVIEW_FONT_PT = 11f
        const val PAGE_TOP_INSET_PX = 24f

        /** Blue ink of the assistant. */
        const val ASSISTANT_TEXT_COLOR = 0xFF1F5AA6.toInt()

    }
}
