package io.github.valerioisufi.drawview.lecture.ui

import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.PlacementItem
import io.github.valerioisufi.drawview.canvas.controller.PlacementRequest
import io.github.valerioisufi.drawview.canvas.controller.PlacementSpot
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.lecture.LectureAnchors
import io.github.valerioisufi.drawview.lecture.NoteKind
import io.github.valerioisufi.drawview.lecture.NotePlacement
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.lecture.resolvedPageIndex
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.geometry.Rect as MmRect
import io.github.valerioisufi.drawview.model.placement.InMargin
import io.github.valerioisufi.drawview.model.placement.PageOccupancy
import io.github.valerioisufi.drawview.model.placement.PageTextSizes
import io.github.valerioisufi.drawview.model.placement.PlacementBlock
import io.github.valerioisufi.drawview.model.placement.PlacementPolicy
import io.github.valerioisufi.drawview.model.placement.ReadingOrder
import io.github.valerioisufi.drawview.model.placement.TextFlow
import io.github.valerioisufi.drawview.model.placement.TextSizePolicy
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
     * @param handwriting the handwriting of the notebook read so far, by key of block, to find the
     *   words the note quotes ([NoteSuggestion.anchor]).
     * @param onInserted called once the note is in the notebook.
     * @param onCancel called when the placement is cancelled.
     */
    fun startPlacement(
        controller: DrawViewController,
        suggestion: NoteSuggestion,
        lecture: LongRange?,
        handwriting: Map<String, String> = emptyMap(),
        onInserted: () -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        val document = controller.document
        val pageIndex = suggestion.resolvedPageIndex(document) ?: controller.viewport.firstVisiblePageIndex() ?: return false
        val page = document.pages.getOrNull(pageIndex) ?: return false
        val content = contentOf(suggestion)
        val anchor = anchorOf(controller, page, suggestion, lecture, handwriting)
        val policy = NotePlacement.policyOf(suggestion.kind, anchor)
        val (size, spots) = sizedPlaces(sizeOf(controller, page), policy, anchor) { fontSize ->
            controller.placementManager.spotsFor(pageIndex, noteBlock(content, page, suggestion.kind, anchor, fontSize), policy)
        }
        val widthMm = spots.firstOrNull()?.box?.width ?: NotePlacement.widthOf(suggestion.kind, anchor, page.widthMm)
        val note = newText(content, widthMm, measureHeightMm(content, size, widthMm), size)
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
        handwriting: Map<String, String> = emptyMap(),
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
            val pageSize = sizeOf(controller, page)
            for (suggestion in notes.sortedBy { it.atMs }) {
                val content = contentOf(suggestion)
                val anchor = anchorOf(controller, page, suggestion, lecture, handwriting)
                val policy = NotePlacement.policyOf(suggestion.kind, anchor)
                val current = occupancy
                val (size, found) = sizedPlaces(pageSize, policy, anchor) { fontSize ->
                    placement.planner.candidates(current, noteBlock(content, page, suggestion.kind, anchor, fontSize), policy).take(1).map { PlacementSpot(page.index, it.rect) }
                }
                val onPage = found.firstOrNull()
                val spot = if (onPage != null) {
                    occupancy += onPage.box
                    onPage
                } else {
                    val next = placement.planner.candidates(newPage, noteBlock(content, page, suggestion.kind, anchor, size), ReadingOrder).firstOrNull() ?: continue
                    newPage += next.rect
                    PlacementSpot(page.index, next.rect, newPageAfter = true)
                }
                val note = newText(content, spot.box.width, spot.box.height, size)
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

    /**
     * A note of [kind] and [content] to place on [page] next to [anchor]: as wide as its kind wants,
     * its height laid out on its width at [fontSize].
     */
    private fun noteBlock(content: String, page: DrawPage, kind: NoteKind, anchor: MmRect?, fontSize: Float) =
        PlacementBlock(NotePlacement.widthOf(kind, anchor, page.widthMm)) { width -> measureHeightMm(content, fontSize, width) }

    /** The size of a note on [page]: like the texts typed there, the slide, the handwriting, or a new text (see [TextSizePolicy]). */
    private fun sizeOf(controller: DrawViewController, page: DrawPage): Float =
        TextSizePolicy.forNote(PageTextSizes.of(page, controller.placementManager.pdfTextOf(page.index)), controller.textDefaults.fontSize)

    /**
     * The places [placesAt] finds for a note at [size]; for a note in the margin ([InMargin]) whose
     * best place is not beside [anchor], those one size smaller when the best of them is beside it.
     */
    private fun sizedPlaces(size: Float, policy: PlacementPolicy, anchor: MmRect?, placesAt: (Float) -> List<PlacementSpot>): Pair<Float, List<PlacementSpot>> {
        val places = placesAt(size)
        if (policy !is InMargin || anchor == null || places.firstOrNull()?.let { isBeside(it, anchor) } == true) return size to places
        val smaller = TextSizePolicy.smaller(size)
        if (smaller >= size) return size to places
        val smallerPlaces = placesAt(smaller)
        return if (smallerPlaces.firstOrNull()?.let { isBeside(it, anchor) } == true) smaller to smallerPlaces else size to places
    }

    /** Whether [spot] is on its page at the height of [anchor]. */
    private fun isBeside(spot: PlacementSpot, anchor: MmRect) =
        !spot.newPageAfter && spot.box.top < anchor.bottom && spot.box.bottom > anchor.top

    /**
     * What [suggestion] is about on [page]: the words it quotes (typed, handwritten as read in
     * [handwriting], or of the slide), else the writing of the moment it was said during [lecture].
     */
    private fun anchorOf(controller: DrawViewController, page: DrawPage, suggestion: NoteSuggestion, lecture: LongRange?, handwriting: Map<String, String>): MmRect? {
        if (suggestion.anchor.isNotBlank()) {
            val blocks = inkBlocksOf(page.index, page.id, page.strokes).mapNotNull { block -> handwriting[block.key]?.takeIf { it.isNotBlank() }?.let { block.bounds to it } }
            LectureAnchors.quoteAnchor(page, suggestion.anchor, blocks, controller.placementManager.pdfTextOf(page.index))?.let { return it }
        }
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
    fun startReviewPlacement(controller: DrawViewController, markdown: String, startPageIndex: Int?, label: String, onInserted: () -> Unit = {}): Boolean =
        startReviewPlacement(controller, listOf(startPageIndex to markdown), label, onInserted)

    /**
     * Lets the student place the [parts] of a review, each flowed from its page (see the other
     * [startReviewPlacement]; `null`: the end of the notebook): the sections of a review next to
     * the pages they are about. The parts of one page are flowed together, in their order; those
     * of the end go after the last page.
     */
    fun startReviewPlacement(controller: DrawViewController, parts: List<Pair<Int?, String>>, label: String, onInserted: () -> Unit = {}): Boolean {
        val document = controller.document
        if (document.pages.isEmpty()) return false
        val newPage = PageOccupancy(document.defaultWidthMm, document.defaultHeightMm, emptyList(), PageOccupancy.lineSpacingOf(document.defaultBackground))
        val items = mutableListOf<PlacementItem>()
        val byPage = parts.filter { it.second.isNotBlank() }.groupBy({ it.first?.takeIf { index -> index in document.pages.indices } }, { it.second })
        // The parts for the end after the parts of the last page, on the new pages after it
        val atTheEnd = byPage[null].orEmpty()
        val groups = byPage.entries.mapNotNull { (index, texts) -> index?.let { it to texts } }.sortedBy { it.first }
        val last = document.pages.lastIndex
        // A part flowed from a page at the size of that page (on its new pages too: a text does not
        // change size midway), the parts of the end at the size of the notebook
        val notebookSize = TextSizePolicy.nearestPreset(PageTextSizes.ofDocument(document) ?: controller.textDefaults.fontSize)
        if (atTheEnd.isNotEmpty() && groups.none { it.first == last }) items += flowItems(controller, atTheEnd.joinToString("\n\n"), null, last, newPage, notebookSize)
        for ((pageIndex, texts) in groups) {
            val all = if (pageIndex == last) texts + atTheEnd else texts
            items += flowItems(controller, all.joinToString("\n\n"), pageIndex, pageIndex, newPage, sizeOf(controller, document.pages[pageIndex]))
        }
        if (items.isEmpty()) return false
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
                    pages.filterNotNull().minByOrNull { it.index }?.let { showPageTop(controller, it.index) }
                    onInserted()
                }
            )
        )
        return true
    }

    /**
     * [markdown] at [fontSize], flowed from the free space of page [startPageIndex] (none: straight
     * on new pages) onto new pages after [anchorIndex].
     */
    private fun flowItems(controller: DrawViewController, markdown: String, startPageIndex: Int?, anchorIndex: Int, newPage: PageOccupancy, fontSize: Float): List<PlacementItem> {
        val first = startPageIndex?.let { controller.placementManager.occupancyOf(it) }
        return textFlow.flow(markdown, first, newPage) { text, width -> measureHeightMm(text, fontSize, width) }.map { piece ->
            PlacementItem(newText(piece.text, piece.box.width, piece.box.height, fontSize), listOf(PlacementSpot(anchorIndex, piece.box, piece.newPage)))
        }
    }

    /** Shows page [pageIndex] with its top edge near the top of the viewport. */
    private fun showPageTop(controller: DrawViewController, pageIndex: Int) {
        controller.pageManager.jumpToPage(pageIndex)
        val rect = controller.viewport.visiblePages().firstOrNull { it.index == pageIndex }?.rect ?: return
        if (rect.height > controller.viewport.height) controller.viewport.panBy(0f, PAGE_TOP_INSET_PX - rect.top)
    }

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
        const val PAGE_TOP_INSET_PX = 24f

        /** Blue ink of the assistant. */
        const val ASSISTANT_TEXT_COLOR = 0xFF1F5AA6.toInt()

    }
}
