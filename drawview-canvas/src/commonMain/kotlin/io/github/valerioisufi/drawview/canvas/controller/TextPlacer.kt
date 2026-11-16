package io.github.valerioisufi.drawview.canvas.controller

import io.github.valerioisufi.drawview.canvas.render.tile.TextBlockSize
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.placement.PageOccupancy
import io.github.valerioisufi.drawview.model.placement.PageTextSizes
import io.github.valerioisufi.drawview.model.placement.TextFlow
import io.github.valerioisufi.drawview.model.placement.TextSizePolicy
import io.github.valerioisufi.drawview.model.text.TextCleanup
import io.github.valerioisufi.drawview.model.text.TextSizes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min

/**
 * Places a long Markdown text in the notebook (a review, a text imported): its parts flowed through
 * the free space of their pages, then onto new pages after them (see [TextFlow]), at the size of the
 * characters of the page they start from (see [TextSizePolicy]), shown before they are confirmed
 * (see [PlacementManager]) and undone at once; the sections of a text next to the pages they are
 * about when the application gives the [PageTopics] (see `docs/plans/text-import.md`).
 */
public class TextPlacer internal constructor(private val controller: DrawViewController) {

    private val textFlow = TextFlow(controller.placementManager.planner)

    /** Whether the sections of a text can be placed by topic (the application gives the [PageTopics]). */
    public val canPlaceByTopic: Boolean get() = controller.engines.topics != null

    /**
     * Lets the student place the [parts] of a text, each flowed from its page (`null`: the end of
     * the notebook), in [color]: the parts of one page together, in their order, those of the end
     * after the last page. A part takes the size of its page (on its new pages too: a text does not
     * change size midway), the parts of the end the size of the texts of the notebook; [fontSize]
     * gives another one.
     *
     * @param label what is placed, shown by the bar of the placement.
     * @param measure lays a text out as the canvas draws it (by default the renderer of the canvas).
     * @param onInserted called once the text is in the notebook.
     */
    public fun start(
        parts: List<Pair<Int?, String>>,
        color: Int,
        label: String,
        fontSize: ((DrawPage?) -> Float)? = null,
        measure: (DrawText) -> TextBlockSize? = controller::measureText,
        onInserted: () -> Unit = {}
    ): Boolean {
        val document = controller.document
        if (document.pages.isEmpty()) return false
        val newPage = PageOccupancy(document.defaultWidthMm, document.defaultHeightMm, emptyList(), PageOccupancy.lineSpacingOf(document.defaultBackground))
        val sizeOf = fontSize ?: { page -> page?.let { sizeOfPage(it) } ?: TextSizePolicy.nearestPreset(PageTextSizes.ofDocument(document) ?: controller.textDefaults.fontSize) }
        val items = mutableListOf<PlacementItem>()
        val byPage = parts.filter { it.second.isNotBlank() }.groupBy({ it.first?.takeIf { index -> index in document.pages.indices } }, { it.second })
        // The parts for the end after the parts of the last page, on the new pages after it
        val atTheEnd = byPage[null].orEmpty()
        val groups = byPage.entries.mapNotNull { (index, texts) -> index?.let { it to texts } }.sortedBy { it.first }
        val last = document.pages.lastIndex
        if (atTheEnd.isNotEmpty() && groups.none { it.first == last }) {
            items += flowItems(atTheEnd.joinToString("\n\n"), null, last, newPage, sizeOf(null), color, measure)
        }
        for ((pageIndex, texts) in groups) {
            val all = if (pageIndex == last) texts + atTheEnd else texts
            items += flowItems(all.joinToString("\n\n"), pageIndex, pageIndex, newPage, sizeOf(document.pages[pageIndex]), color, measure)
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
                            for ((index, spot) in spots.withIndex()) pages[index]?.let { place(it, items[index].preview, spot, measure) }
                        }
                    }
                    pages.filterNotNull().minByOrNull { it.index }?.let { showPageTop(it.index) }
                    onInserted()
                }
            )
        )
        return true
    }

    /**
     * Lets the student place the text of [import] (see [start]) at [place], cleaned into the
     * Markdown of the notebook when [clean] (see [TextCleanup]), in the color of a new text, at the
     * size of the page each part starts from. Whether there was something to place.
     *
     * @param label what is placed, shown by the bar of the placement.
     */
    public suspend fun startImport(import: TextImport, place: TextImportPlace, clean: Boolean, label: String, onInserted: () -> Unit = {}): Boolean {
        val markdown = if (clean) TextCleanup.toMarkdown(import.text) else import.text.trim()
        val page = import.pageIndex?.takeIf { it in controller.document.pages.indices } ?: controller.viewport.mostVisiblePageIndex()
        val parts = when (place) {
            TextImportPlace.TOPIC -> partsByTopic(markdown, fallbackPage = page)
            TextImportPlace.PAGE -> listOf(page to markdown)
            TextImportPlace.END -> listOf(null to markdown)
        }
        return start(parts, controller.textDefaults.color, label, onInserted = onInserted)
    }

    /**
     * The sections of [markdown] (see [TextFlow.sectionsOf]), each with the page of the notebook it
     * is about the most ([PageTopics]), or [fallbackPage] (`null`: the end) when none is found or
     * the application gives no [PageTopics].
     */
    public suspend fun partsByTopic(markdown: String, fallbackPage: Int?): List<Pair<Int?, String>> {
        val topics = controller.engines.topics
        val documentId = controller.document.id
        return TextFlow.sectionsOf(markdown).map { section ->
            val pageIds = try {
                topics?.pagesAbout(documentId, section).orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val pages = controller.document.pages
            val pageIndex = pageIds.firstNotNullOfOrNull { id -> pages.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            (pageIndex ?: fallbackPage) to section
        }
    }

    /** The size of a text placed on [page]: like the texts typed there, the slide, the handwriting, or a new text. */
    public fun sizeOfPage(page: DrawPage): Float =
        TextSizePolicy.forNote(PageTextSizes.of(page, controller.placementManager.pdfTextOf(page.index)), controller.textDefaults.fontSize)

    /**
     * [markdown] at [fontSize] in [color], flowed from the free space of page [startPageIndex] (none:
     * straight on new pages) onto new pages after [anchorIndex], the pieces going on onto a new page
     * linked into a chain (see `TextChains`).
     */
    private fun flowItems(
        markdown: String,
        startPageIndex: Int?,
        anchorIndex: Int,
        newPage: PageOccupancy,
        fontSize: Float,
        color: Int,
        measure: (DrawText) -> TextBlockSize?
    ): List<PlacementItem> {
        val first = startPageIndex?.let { controller.placementManager.occupancyOf(it) }
        val pieces = textFlow.flow(markdown, first, newPage) { text, width -> heightOf(text, width, fontSize, color, measure) }
        val items = pieces.map { piece ->
            PlacementItem(newText(piece.text, piece.box.width, piece.box.height, fontSize, color), listOf(PlacementSpot(anchorIndex, piece.box, piece.newPage)))
        }
        // A piece going on at the top of a new page goes on from the piece before it: one text across the pages
        for (index in 1 until pieces.size) {
            if (pieces[index].newPage > pieces[index - 1].newPage) items[index - 1].preview.next = items[index].preview.id
        }
        return items
    }

    /** Adds [text] to [page] in the box of [spot], its height laid out again on its width. */
    private fun place(page: DrawPage, text: DrawText, spot: PlacementSpot, measure: (DrawText) -> TextBlockSize?) {
        text.width = min(spot.box.width, page.widthMm)
        text.height = heightOf(text.text, text.width, text.fontSize, text.color, measure)
        text.x = spot.box.left.coerceIn(0f, max(0f, page.widthMm - text.width))
        text.y = spot.box.top.coerceIn(0f, max(0f, page.heightMm - text.height))
        text.markDirty()
        controller.session.submit(AddElements.of(page, listOf(text)))
    }

    /** Shows page [pageIndex] with its top edge near the top of the viewport. */
    private fun showPageTop(pageIndex: Int) {
        controller.pageManager.jumpToPage(pageIndex)
        val rect = controller.viewport.visiblePages().firstOrNull { it.index == pageIndex }?.rect ?: return
        if (rect.height > controller.viewport.height) controller.viewport.panBy(0f, PAGE_TOP_INSET_PX - rect.top)
    }

    private fun heightOf(markdown: String, widthMm: Float, fontSize: Float, color: Int, measure: (DrawText) -> TextBlockSize?): Float =
        measure(newText(markdown, widthMm, 0f, fontSize, color))?.height ?: (markdown.lines().size * TextSizes.estimatedLineHeightMm(fontSize))

    private fun newText(content: String, widthMm: Float, heightMm: Float, fontSizePt: Float, textColor: Int) = DrawText().apply {
        text = content
        width = widthMm
        height = heightMm
        color = textColor
        fontSize = fontSizePt
        alignment = controller.textDefaults.alignment
        markDirty()
    }

    private companion object {
        const val PAGE_TOP_INSET_PX = 24f
    }
}
