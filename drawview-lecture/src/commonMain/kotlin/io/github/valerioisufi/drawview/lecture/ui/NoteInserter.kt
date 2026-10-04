package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.controller.PlacementRequest
import io.github.valerioisufi.drawview.core.history.AddTextAction
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.lecture.resolvedPageIndex
import io.github.valerioisufi.drawview.ui.editor.MarkdownTextUtil
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
     * Lets the student place [suggestion]: the next tap on a page puts the note there (see
     * [io.github.valerioisufi.drawview.core.controller.PlacementManager]), selected so that it can
     * be moved or resized. The canvas can still be moved to reach any page.
     *
     * @param onInserted called once the note is in the notebook.
     * @param onCancel called when the placement is cancelled.
     */
    fun startPlacement(
        controller: DrawViewController,
        suggestion: NoteSuggestion,
        onInserted: () -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        val document = controller.document
        // Sized for the page of the note (or the visible one), adapted to the page chosen
        val reference = suggestion.resolvedPageIndex(document)?.let { document.pages.getOrNull(it) }
            ?: controller.firstVisiblePageIndex()?.let { document.pages.getOrNull(it) }
            ?: return false
        val content = buildString {
            if (suggestion.title.isNotBlank()) appendLine("**${suggestion.title.trim()}**")
            append(suggestion.content.trim())
        }
        val widthMm = noteWidthMm(reference)
        val note = newText(content, widthMm, measureHeightMm(content, NOTE_FONT_PT, widthMm), NOTE_FONT_PT)

        controller.placementManager.start(
            PlacementRequest(
                preview = note,
                onPlace = { pageIndex, x, y ->
                    val page = document.pages.getOrNull(pageIndex) ?: return@PlacementRequest
                    if (note.width > page.widthMm) {
                        note.width = noteWidthMm(page)
                        note.height = measureHeightMm(content, NOTE_FONT_PT, note.width)
                    }
                    note.x = x.coerceIn(0f, max(0f, page.widthMm - note.width))
                    note.y = y.coerceIn(0f, max(0f, page.heightMm - note.height))
                    addText(controller, page, note)
                    controller.selectionManager.selectText(pageIndex, note)
                    onInserted()
                },
                onCancel = onCancel
            )
        )
        return true
    }

    /**
     * Inserts the notes [markdown] on as many new pages as needed (split between paragraphs):
     * after the page [afterPageIndex], or at the end of the notebook when it is `null`; then shows
     * the first one.
     */
    fun insertPages(controller: DrawViewController, markdown: String, afterPageIndex: Int?): Boolean {
        val blocks = markdown.trim().split(PARAGRAPH_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.isEmpty()) return false
        controller.placementManager.cancel()
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

        var previousIndex = afterPageIndex?.takeIf { it in document.pages.indices }
        var firstIndex = -1
        for (content in pages) {
            val page = previousIndex?.let { controller.pageManager.addNewPageAfter(it) }
                ?: controller.pageManager.addNewPageAtBottom()
            if (previousIndex != null) previousIndex = page.index
            if (firstIndex < 0) firstIndex = page.index
            val text = newText(content, widthMm, measureHeightMm(content, REVIEW_FONT_PT, widthMm), REVIEW_FONT_PT).apply {
                x = MARGIN_MM
                y = MARGIN_MM
            }
            addText(controller, page, text)
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

    private fun noteWidthMm(page: DrawPage): Float =
        min(NOTE_WIDTH_MM, page.widthMm * 0.45f).coerceAtLeast(min(30f, page.widthMm))

    private fun newText(content: String, widthMm: Float, heightMm: Float, fontSizePt: Float) = DrawText().apply {
        text = content
        isMarkdownEnabled = true
        width = widthMm
        height = heightMm
        color = ASSISTANT_TEXT_COLOR
        fontSize = fontSizePt
        markDirty()
    }

    private fun addText(controller: DrawViewController, page: DrawPage, item: DrawText) {
        item.zIndex = page.texts.size
        item.markDirty()
        page.texts.add(item)
        page.markContentChanged()
        controller.historyManager.addHistoryAction(AddTextAction(page, item))
        controller.persist { saveText(page, item) }
        controller.invalidatePageContent(page, null)
    }

    /**
     * Height in millimeters of [markdown] laid out on [widthMm], as the canvas draws it (Markdown
     * styles, formulas rendered by MicroTeX once the engine is ready).
     */
    fun measureHeightMm(markdown: String, fontSizePt: Float, widthMm: Float): Float {
        val fontPx = fontSizePt * PT_TO_MM * MEASURE_PX_PER_MM
        val style = TextStyle(fontSize = fontPx.sp, letterSpacing = 0.sp)
        val ascent = measurer.measure(AnnotatedString("Hg"), style).firstBaseline
        val canvasText = MarkdownTextUtil.toCanvasText(markdown, true, Color.Black, fontPx, ascent) { latex, sizePx ->
            LatexEngine.renderFormula(latex, sizePx, Color.Black.toArgb())?.let {
                MarkdownTextUtil.RenderedFormula(it.bitmap, it.width.toFloat(), it.height.toFloat(), it.baselineRatio)
            }
        }
        val placeholders = canvasText.placeholders.map { spec ->
            AnnotatedString.Range(
                Placeholder(
                    width = spec.widthPx.sp,
                    height = spec.heightPx.sp,
                    placeholderVerticalAlign = if (spec.aboveBaseline) PlaceholderVerticalAlign.AboveBaseline else PlaceholderVerticalAlign.TextTop
                ),
                spec.start,
                spec.end
            )
        }
        val layout = measurer.measure(
            text = canvasText.text,
            style = style,
            softWrap = true,
            placeholders = placeholders,
            constraints = Constraints(maxWidth = ceil(widthMm * MEASURE_PX_PER_MM).toInt().coerceAtLeast(1))
        )
        return layout.size.height / MEASURE_PX_PER_MM + 1f
    }

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
    }
}
