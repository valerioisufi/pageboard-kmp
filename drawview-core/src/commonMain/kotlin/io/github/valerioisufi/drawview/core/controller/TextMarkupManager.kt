package io.github.valerioisufi.drawview.core.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.valerioisufi.drawview.core.geometry.toModel
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.ChangeEffects
import io.github.valerioisufi.drawview.model.change.MarkupStyle
import io.github.valerioisufi.drawview.model.change.RemoveElements
import io.github.valerioisufi.drawview.model.change.RestyleMarkups
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.pdfPointsToPageMm
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Selection and marking (highlights, underlines, strikeouts) of the text of the PDF pages.
 *
 * - The [Tool.TEXT_MARKUP] tool marks the text swept by the pointer with its active preset
 *   ([beginMarking], [updateMarking], [finishMarking]).
 * - A long press on the text selects a word ([selectAt]), whose ends can be dragged
 *   ([beginHandleDrag], [dragHandleTo]): the selected text can be copied or marked
 *   ([markSelection]).
 * - A long press on a mark, or a tap with the tool, selects it: its text can be copied, its
 *   style changed ([restyleSelectedMarkup]) and the mark deleted ([deleteSelectedMarkup]).
 */
class TextMarkupManager(private val controller: DrawViewController) {

    /**
     * The characters of the text of [pdf] (shown on [page]) from [anchor] to [focus], both
     * included: [anchor] stays where the selection started, [focus] follows the pointer.
     */
    class TextRange(
        val page: DrawPage,
        val pdf: DrawPdf,
        val layout: PdfTextLayout,
        val anchor: Int,
        val focus: Int
    ) {
        val start: Int get() = min(anchor, focus)

        /** Exclusive. */
        val end: Int get() = max(anchor, focus) + 1

        /** Lines of the range, in points of the PDF page. */
        val rects: List<Rect> by lazy { layout.lineRects(start, end) }

        val text: String get() = layout.text(start, end).trim()

        /** Transformation from the points of the PDF page to the millimeters of [page]. */
        fun pointsToPageMm(): Matrix2D = pdfPointsToPageMm(layout.widthPt, layout.heightPt, page.widthMm, page.heightMm)

        fun withFocus(focus: Int) = TextRange(page, pdf, layout, anchor, focus)
    }

    /**
     * A passage being marked with the tool, shown (once the pointer has moved, [isShown]) with
     * the style of the mark it will become.
     */
    class Marking(val range: TextRange, val kind: TextMarkupKind, val color: Int, val isShown: Boolean)

    /** A mark of [page] selected to be copied, restyled or deleted. */
    class SelectedMarkup(val page: DrawPage, val markup: DrawTextMarkup)

    enum class Handle { START, END }

    /** Text selected with a long press, or `null`. */
    var textSelection: TextRange? by mutableStateOf(null)
        private set

    /** Mark selected with a long press or a tap of the tool, or `null`. */
    var selectedMarkup: SelectedMarkup? by mutableStateOf(null)
        private set

    /** Passage being marked with the tool, or `null`. */
    var marking: Marking? by mutableStateOf(null)
        private set

    val hasSelection: Boolean get() = textSelection != null || selectedMarkup != null

    /** Text of the selection or of the selected mark. */
    val selectedText: String?
        get() = textSelection?.text ?: selectedMarkup?.markup?.text

    /** Offset from the pointer to the end of the selection it drags, in screen pixels. */
    private var handleDragOffset = Offset.Zero

    private val layoutsLock = SyncLock()

    /** Text layouts of the PDF pages, by "resource path#page" (`null`: not readable). */
    private val layouts = LinkedHashMap<String, PdfTextLayout?>()

    // ==========================================================
    // Text layouts
    // ==========================================================

    /**
     * Text layout of the PDF page shown by [pdf], read once (and kept for the last pages used),
     * or `null` when PDFs are not supported or the page cannot be read.
     */
    fun layoutOf(pdf: DrawPdf): PdfTextLayout? {
        val adapter = controller.tileManager.tileRenderer.pdfAdapter ?: return null
        val resource = controller.document.findResource(pdf.resourceId) ?: return null
        if (resource.type != DrawResource.ResourceType.PDF) return null
        val key = resource.path + "#" + pdf.pdfPageIndex
        syncLock(layoutsLock) {
            if (layouts.containsKey(key)) {
                val layout = layouts.remove(key)
                layouts[key] = layout
                return layout
            }
        }
        val layout = try {
            adapter.getTextLayout(resource.path, pdf.pdfPageIndex)
        } catch (_: Throwable) {
            null
        }
        syncLock(layoutsLock) {
            layouts[key] = layout
            while (layouts.size > MAX_CACHED_LAYOUTS) layouts.remove(layouts.keys.first())
        }
        return layout
    }

    /**
     * Reads in background the text of the PDF pages on screen, so that the first touch of the
     * tool does not wait for it.
     */
    fun prefetchVisiblePages() {
        val pdfs = controller.visiblePages().flatMap { controller.document.pages.getOrNull(it.index)?.pdfs.orEmpty() }
        if (pdfs.isEmpty()) return
        controller.coroutineScope.launch(Dispatchers.Default) {
            pdfs.forEach { layoutOf(it) }
        }
    }

    // ==========================================================
    // Marking with the tool
    // ==========================================================

    /**
     * Starts marking the text at [screen] with the active preset of the tool.
     *
     * @param tolerancePx how far from the text the pointer may be, in screen pixels.
     * @return `false` when there is no text there (a message tells when the PDF of the page has
     * no text at all, like a scanned page).
     */
    fun beginMarking(screen: Offset, tolerancePx: Float): Boolean {
        val preset = controller.toolManager.activePreset(Tool.TEXT_MARKUP) ?: return false
        val hit = textAt(screen, tolerancePx)
        if (hit == null) {
            if (isPdfWithoutTextAt(screen)) controller.emitMessage(DrawViewController.Message.PDF_PAGE_WITHOUT_TEXT)
            return false
        }
        clearSelection()
        marking = Marking(hit, preset.markup, preset.color, isShown = false)
        return true
    }

    /** Extends the passage being marked to the text at [screen] (the pointer has moved). */
    fun updateMarking(screen: Offset) {
        val current = marking ?: return
        val focus = focusAt(current.range, screen) ?: current.range.focus
        if (focus != current.range.focus || !current.isShown) {
            marking = Marking(current.range.withFocus(focus), current.kind, current.color, isShown = true)
        }
    }

    /** Marks the passage swept by the tool (nothing when the pointer has not moved). */
    fun finishMarking(): DrawTextMarkup? {
        val current = marking ?: return null
        marking = null
        if (!current.isShown) return null
        return addMarkup(current.range, current.kind, current.color)
    }

    fun cancelMarking() {
        marking = null
    }

    // ==========================================================
    // Selection
    // ==========================================================

    /**
     * Selects the mark at [screen], or else the word of the PDF text at [screen] (long press).
     *
     * @return `false` when there is neither a mark nor text there.
     */
    fun selectAt(screen: Offset, tolerancePx: Float): Boolean {
        if (selectMarkupAt(screen, tolerancePx)) return true
        val hit = textAt(screen, tolerancePx) ?: return false
        val word = hit.layout.wordRange(hit.anchor)
        if (word.isEmpty()) return false
        controller.selectionManager.clearSelection()
        selectedMarkup = null
        textSelection = TextRange(hit.page, hit.pdf, hit.layout, word.first, word.last)
        return true
    }

    /**
     * Selects the mark at [screen].
     *
     * @return `false` when there is no mark there.
     */
    fun selectMarkupAt(screen: Offset, tolerancePx: Float): Boolean {
        val pageIndex = controller.findPageIndexAtScreen(screen) ?: return false
        val page = controller.document.pages.getOrNull(pageIndex) ?: return false
        val mm = controller.screenToPageMm(pageIndex, screen) ?: return false
        val toleranceMm = tolerancePx / controller.pixelsPerMm(pageIndex).coerceAtLeast(0.01f)
        val markup = page.markups.lastOrNull { it.containsPoint(page, mm.toModel(), toleranceMm) } ?: return false
        controller.selectionManager.clearSelection()
        textSelection = null
        selectedMarkup = SelectedMarkup(page, markup)
        return true
    }

    /** Drops the text selection and the selected mark. */
    fun clearSelection() {
        textSelection = null
        selectedMarkup = null
    }

    /** Drops the selections that changes made on another device made stale. */
    internal fun onChange(effects: ChangeEffects) {
        val markup = selectedMarkup?.markup
        if (markup != null && (markup in effects.deletedElements || markup in effects.savedElements)) selectedMarkup = null
        val page = textSelection?.page
        if (page != null && page in effects.removedPages) textSelection = null
    }

    /**
     * End of the text selection whose handle is at [screen] (at most [radiusPx] away), or `null`.
     */
    fun handleAt(screen: Offset, radiusPx: Float): Handle? {
        val (start, end) = handleKnobs() ?: return null
        val toStart = (screen - start).getDistance()
        val toEnd = (screen - end).getDistance()
        return when {
            toEnd <= radiusPx && toEnd <= toStart -> Handle.END
            toStart <= radiusPx -> Handle.START
            else -> null
        }
    }

    /** Radius of the knobs of the handles of the text selection, in screen pixels. */
    val handleKnobRadiusPx: Float get() = HANDLE_KNOB_RADIUS_DP * controller.density

    /**
     * Screen positions of the centers of the knobs of the handles of the text selection (start,
     * end), hanging below its first and last line, or `null` without a selection on screen.
     */
    fun handleKnobs(): Pair<Offset, Offset>? {
        val selection = textSelection ?: return null
        val toScreen = pointsToScreen(selection.page, selection.pointsToPageMm()) ?: return null
        val first = selection.rects.firstOrNull()?.let { toScreen.mapRect(it) } ?: return null
        val last = toScreen.mapRect(selection.rects.last())
        val radius = handleKnobRadiusPx
        return Offset(first.left, first.bottom + radius) to Offset(last.right, last.bottom + radius)
    }

    /** Starts dragging the [handle] of the text selection with the pointer at [screen]. */
    fun beginHandleDrag(handle: Handle, screen: Offset) {
        val selection = textSelection ?: return
        val toScreen = pointsToScreen(selection.page, selection.pointsToPageMm()) ?: return
        val rects = selection.rects
        if (rects.isEmpty()) return
        // The dragged end follows the pointer, the other one stays
        val edge = if (handle == Handle.START) {
            textSelection = TextRange(selection.page, selection.pdf, selection.layout, selection.end - 1, selection.start)
            toScreen.mapRect(rects.first()).let { Offset(it.left, it.center.y) }
        } else {
            textSelection = TextRange(selection.page, selection.pdf, selection.layout, selection.start, selection.end - 1)
            toScreen.mapRect(rects.last()).let { Offset(it.right, it.center.y) }
        }
        handleDragOffset = edge - screen
    }

    /** Moves the dragged end of the text selection to the text under the pointer at [screen]. */
    fun dragHandleTo(screen: Offset) {
        val selection = textSelection ?: return
        val focus = focusAt(selection, screen + handleDragOffset) ?: return
        if (focus != selection.focus) textSelection = selection.withFocus(focus)
    }

    /** Marks the selected text as [kind] with [color], and drops the selection. */
    fun markSelection(kind: TextMarkupKind, color: Int): DrawTextMarkup? {
        val selection = textSelection ?: return null
        textSelection = null
        return addMarkup(selection, kind, color)
    }

    /** Changes the kind and the color of the selected mark (undoable). */
    fun restyleSelectedMarkup(kind: TextMarkupKind, color: Int) {
        val selected = selectedMarkup ?: return
        val markup = selected.markup
        if (markup.kind == kind && markup.color == color) return
        controller.session.submit(RestyleMarkups(selected.page.id, listOf(MarkupStyle(markup.id, kind, color))))
        // The action bar shows the new style
        selectedMarkup = SelectedMarkup(selected.page, markup)
    }

    /** Deletes the selected mark (undoable). */
    fun deleteSelectedMarkup() {
        val selected = selectedMarkup ?: return
        selectedMarkup = null
        controller.session.submit(RemoveElements.of(selected.page, listOf(selected.markup)))
    }

    // ==========================================================
    // Geometry
    // ==========================================================

    /**
     * Transformation from the points of a PDF page to the screen, through [pointsToPageMm] (its
     * points to the millimeters of [page]), or `null` when the page is not laid out.
     */
    fun pointsToScreen(page: DrawPage, pointsToPageMm: Matrix2D): Matrix2D? {
        val pageIndex = controller.document.pages.indexOf(page)
        if (pageIndex < 0) return null
        val mmToScreen = controller.pageMmToScreen(pageIndex) ?: return null
        return Matrix2D(pointsToPageMm).postConcat(mmToScreen)
    }

    // ==========================================================
    // Internals
    // ==========================================================

    /** The character of the text of a PDF at [screen] (at most [tolerancePx] away), as an empty range. */
    private fun textAt(screen: Offset, tolerancePx: Float): TextRange? {
        val pageIndex = controller.findPageIndexAtScreen(screen) ?: return null
        val page = controller.document.pages.getOrNull(pageIndex) ?: return null
        val mm = controller.screenToPageMm(pageIndex, screen) ?: return null
        for (pdf in page.pdfs.asReversed()) {
            val layout = layoutOf(pdf) ?: continue
            if (!layout.hasText) continue
            val pointsToMm = pdfPointsToPageMm(layout.widthPt, layout.heightPt, page.widthMm, page.heightMm)
            val mmToPoints = Matrix2D()
            if (!pointsToMm.invert(mmToPoints)) continue
            val point = mmToPoints.mapPoint(mm.x, mm.y)
            val pixelsPerPoint = controller.pixelsPerMm(pageIndex) * pointsToMm.scaleFactor
            val index = layout.charIndexNear(point.x, point.y, tolerancePx / pixelsPerPoint.coerceAtLeast(0.001f))
            if (index >= 0) return TextRange(page, pdf, layout, index, index)
        }
        return null
    }

    /** Whether the page at [screen] shows a PDF whose text cannot be selected. */
    private fun isPdfWithoutTextAt(screen: Offset): Boolean {
        val pageIndex = controller.findPageIndexAtScreen(screen) ?: return false
        val page = controller.document.pages.getOrNull(pageIndex) ?: return false
        return page.pdfs.isNotEmpty() && page.pdfs.none { layoutOf(it)?.hasText == true }
    }

    /** Character of [range]'s text a selection extends to with the pointer at [screen]. */
    private fun focusAt(range: TextRange, screen: Offset): Int? {
        val pageIndex = controller.document.pages.indexOf(range.page)
        if (pageIndex < 0) return null
        val mm = controller.screenToPageMm(pageIndex, screen) ?: return null
        val mmToPoints = Matrix2D()
        if (!range.pointsToPageMm().invert(mmToPoints)) return null
        val point = mmToPoints.mapPoint(mm.x, mm.y)
        return range.layout.charIndexForSelection(point.x, point.y).takeIf { it >= 0 }
    }

    private fun addMarkup(range: TextRange, kind: TextMarkupKind, color: Int): DrawTextMarkup? {
        val page = range.page
        val pageIndex = controller.document.pages.indexOf(page)
        val rects = range.rects
        if (pageIndex < 0 || rects.isEmpty()) return null
        val markup = DrawTextMarkup(
            resourceId = range.pdf.resourceId,
            pdfPageIndex = range.pdf.pdfPageIndex,
            pdfWidthPt = range.layout.widthPt,
            pdfHeightPt = range.layout.heightPt,
            startIndex = range.start,
            endIndex = range.end,
            text = range.text,
            rects = rects,
            kind = kind,
            color = color
        )
        val change = AddElements.of(page, listOf(markup))
        // Atomic hand-off from the live layer to the tiles (no missing frame)
        val pageWorldRect = controller.calcPage.pagesRectOnWindow.getOrNull(pageIndex)
        if (pageWorldRect != null) {
            controller.tileManager.bakeMarkupsDirectlySync(page, pageWorldRect, listOf(markup)) { controller.submitAlreadyDrawn(change) }
        } else {
            controller.session.submit(change)
        }
        return markup
    }

    private companion object {
        const val MAX_CACHED_LAYOUTS = 32
        const val HANDLE_KNOB_RADIUS_DP = 8f
    }
}
