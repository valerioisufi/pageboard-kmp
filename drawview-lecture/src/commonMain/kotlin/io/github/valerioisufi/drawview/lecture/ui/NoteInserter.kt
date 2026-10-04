package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.history.AddTextAction
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.ui.render.PT_TO_MM
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Inserts the content of the assistant in the notebook as Markdown text blocks (undoable, saved
 * like the texts typed by the student), in a color telling them apart from the student's notes.
 *
 * @param measurer text measurer at density 1 (1 sp = 1 px), used to size the blocks.
 */
internal class NoteInserter(private val measurer: TextMeasurer) {

    /**
     * Inserts [suggestion] on its page (the first visible page when it has none): at the center of
     * the visible part of the page, selected so that it can be moved.
     */
    fun insert(controller: DrawViewController, suggestion: NoteSuggestion): Boolean {
        val document = controller.document
        val pageIndex = suggestion.pageIndex?.takeIf { it in document.pages.indices }
            ?: controller.firstVisiblePageIndex()
            ?: return false
        val page = document.pages[pageIndex]
        // The note is placed where the student is looking: on screen first
        if (controller.visiblePages().none { it.index == pageIndex }) controller.pageManager.jumpToPage(pageIndex)

        val text = buildString {
            if (suggestion.title.isNotBlank()) appendLine("**${suggestion.title.trim()}**")
            append(suggestion.content.trim())
        }
        val widthMm = min(NOTE_WIDTH_MM, page.widthMm * 0.45f).coerceAtLeast(min(30f, page.widthMm))
        val heightMm = measureHeightMm(text, NOTE_FONT_PT, widthMm)

        // Center of the part of the page shown on screen
        val pageOnScreen = controller.visiblePages().firstOrNull { it.index == pageIndex }?.rect
        val viewport = Rect(0f, 0f, controller.viewportWidth, controller.viewportHeight)
        val center = pageOnScreen?.intersect(viewport)?.takeIf { it.width > 0f && it.height > 0f }
            ?.let { controller.screenToPageMm(pageIndex, it.center) }
        val x = ((center?.x ?: (MARGIN_MM + widthMm / 2f)) - widthMm / 2f).coerceIn(0f, max(0f, page.widthMm - widthMm))
        val y = ((center?.y ?: (MARGIN_MM + heightMm / 2f)) - heightMm / 2f).coerceIn(0f, max(0f, page.heightMm - heightMm))

        val item = addText(controller, page, text, x, y, widthMm, heightMm, NOTE_FONT_PT)
        controller.selectionManager.selectText(pageIndex, item)
        return true
    }

    /**
     * Appends the notes [markdown] at the end of the notebook, on as many new pages as needed
     * (split between paragraphs), and shows the first one.
     */
    fun insertPages(controller: DrawViewController, markdown: String): Boolean {
        val blocks = markdown.trim().split(PARAGRAPH_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.isEmpty()) return false
        controller.selectionManager.clearSelection()
        val document = controller.document
        val widthMm = document.defaultWidthMm - 2 * MARGIN_MM
        val maxHeightMm = document.defaultHeightMm - 2 * MARGIN_MM
        if (widthMm <= 10f || maxHeightMm <= 10f) return false

        // Fill the pages greedily; a paragraph longer than a page is split between its lines
        val pieces = blocks.flatMap { block ->
            if (measureHeightMm(block, REVIEW_FONT_PT, widthMm) <= maxHeightMm) listOf(block) else block.lines()
        }
        val pages = mutableListOf<String>()
        var current = ""
        for (piece in pieces) {
            val candidate = if (current.isEmpty()) piece else current + "\n\n" + piece
            if (current.isNotEmpty() && measureHeightMm(candidate, REVIEW_FONT_PT, widthMm) > maxHeightMm) {
                pages.add(current)
                current = piece
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) pages.add(current)

        var firstIndex = -1
        for (content in pages) {
            val page = controller.pageManager.addNewPageAtBottom()
            if (firstIndex < 0) firstIndex = page.index
            addText(controller, page, content, MARGIN_MM, MARGIN_MM, widthMm, measureHeightMm(content, REVIEW_FONT_PT, widthMm), REVIEW_FONT_PT)
        }
        if (firstIndex >= 0) showPageTop(controller, firstIndex)
        return true
    }

    /** Shows page [pageIndex] with its top edge near the top of the viewport. */
    private fun showPageTop(controller: DrawViewController, pageIndex: Int) {
        controller.pageManager.jumpToPage(pageIndex)
        val rect = controller.visiblePages().firstOrNull { it.index == pageIndex }?.rect ?: return
        if (rect.height > controller.viewportHeight) controller.panCanvasBy(0f, PAGE_TOP_INSET_PX - rect.top)
    }

    private fun addText(
        controller: DrawViewController,
        page: DrawPage,
        content: String,
        x: Float,
        y: Float,
        widthMm: Float,
        heightMm: Float,
        fontSizePt: Float
    ): DrawText {
        val item = DrawText(zIndex = page.texts.size).apply {
            text = content
            isMarkdownEnabled = true
            this.x = x
            this.y = y
            width = widthMm
            height = heightMm
            color = ASSISTANT_TEXT_COLOR
            fontSize = fontSizePt
            markDirty()
        }
        page.texts.add(item)
        page.markContentChanged()
        controller.historyManager.addHistoryAction(AddTextAction(page, item))
        controller.persist { saveText(page, item) }
        controller.invalidatePageContent(page, null)
        return item
    }

    /** Height in millimeters of [markdown] laid out on [widthMm] (approximated on the plain text). */
    fun measureHeightMm(markdown: String, fontSizePt: Float, widthMm: Float): Float {
        val fontPx = fontSizePt * PT_TO_MM * MEASURE_PX_PER_MM
        val layout = measurer.measure(
            text = plainText(markdown),
            style = TextStyle(fontSize = fontPx.sp),
            softWrap = true,
            constraints = Constraints(maxWidth = ceil(widthMm * MEASURE_PX_PER_MM).toInt().coerceAtLeast(1))
        )
        // Headings and formulas are a little taller than the plain text
        return layout.size.height / MEASURE_PX_PER_MM * 1.15f + 2f
    }

    private fun plainText(markdown: String): String = markdown
        .replace(MARKUP, "")
        .replace(HEADING, "")

    private companion object {
        const val NOTE_WIDTH_MM = 90f
        const val NOTE_FONT_PT = 11f
        const val REVIEW_FONT_PT = 11f
        const val MARGIN_MM = 15f
        const val MEASURE_PX_PER_MM = 8f
        const val PAGE_TOP_INSET_PX = 24f

        /** Blue ink of the assistant. */
        const val ASSISTANT_TEXT_COLOR = 0xFF1F5AA6.toInt()

        val PARAGRAPH_SEPARATOR = Regex("""\n\s*\n""")
        val MARKUP = Regex("""\*\*|__|~~|`|\$""")
        val HEADING = Regex("""(?m)^#+\s+""")
    }
}
