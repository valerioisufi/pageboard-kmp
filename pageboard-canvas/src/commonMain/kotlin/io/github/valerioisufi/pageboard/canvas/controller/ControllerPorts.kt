package io.github.valerioisufi.pageboard.canvas.controller

import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.pageboard.canvas.port.EditorDocument
import io.github.valerioisufi.pageboard.canvas.port.EditorEdits
import io.github.valerioisufi.pageboard.canvas.port.EditorPages
import io.github.valerioisufi.pageboard.canvas.port.EditorPlacement
import io.github.valerioisufi.pageboard.canvas.port.EditorPorts
import io.github.valerioisufi.pageboard.canvas.port.EditorRendering
import io.github.valerioisufi.pageboard.canvas.render.DrawViewEngines
import io.github.valerioisufi.pageboard.canvas.render.tile.TextBlockSize
import io.github.valerioisufi.pageboard.model.DrawCustomElement
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.DrawPage
import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.ElementType
import io.github.valerioisufi.pageboard.model.PageContentSnapshot
import io.github.valerioisufi.pageboard.model.PdfTextLayout
import io.github.valerioisufi.pageboard.model.change.ChangeObserver
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.placement.PageOccupancy
import io.github.valerioisufi.pageboard.model.placement.PlacementBlock
import io.github.valerioisufi.pageboard.model.placement.PlacementPlanner
import io.github.valerioisufi.pageboard.model.placement.PlacementPolicy

/** The ports of [controller], each answered by the manager that holds what it gives. */
internal fun portsOf(controller: DrawViewController): EditorPorts = EditorPorts(
    document = DocumentPort(controller),
    pages = PagesPort(controller),
    edits = EditsPort(controller),
    placement = PlacementPort(controller.placementManager, controller.textPlacer),
    rendering = RenderingPort(controller),
    presence = controller.presence
)

/** The document and its session, with the history grouping the changes. */
private class DocumentPort(private val controller: DrawViewController) : EditorDocument {
    override val document: DrawDocument get() = controller.document
    override val isReadOnly: Boolean get() = controller.isReadOnly
    override fun submit(change: DocumentChange): DocumentChange? = controller.session.submit(change)
    override fun <T> group(block: () -> T): T = controller.historyManager.group(block)
    override fun addObserver(observer: ChangeObserver) = controller.session.addObserver(observer)
    override fun removeObserver(observer: ChangeObserver) = controller.session.removeObserver(observer)
}

/** The pages by the page manager, laid out by the viewport. */
private class PagesPort(private val controller: DrawViewController) : EditorPages {
    override val selection: PageSelection get() = controller.pageManager.selection
    override fun visiblePageIndices(): List<Int> = controller.viewport.visiblePages().map { it.index }
    override fun firstVisiblePageIndex(): Int? = controller.viewport.firstVisiblePageIndex()
    override fun mostVisiblePageIndex(): Int? = controller.viewport.mostVisiblePageIndex()
    override fun jumpToPage(pageIndex: Int, bounds: Rect?) = controller.pageManager.jumpToPage(pageIndex, bounds)
    override fun pageMmToScreen(pageIndex: Int): Matrix2D? = controller.viewport.pageMmToScreen(pageIndex)
    override val frames: Int get() = controller.version + controller.viewport.camera.moveCount
}

/** The edits, by the managers of the elements, the selection and the text being written. */
private class EditsPort(private val controller: DrawViewController) : EditorEdits {
    override fun <T : Any> addCustomElement(type: ElementType<T>, value: T, pageIndex: Int, box: Rect, select: Boolean): DrawCustomElement? =
        controller.customElementManager.add(type, value, pageIndex, box, select)

    override fun selectText(pageIndex: Int, text: DrawText) = controller.selectionManager.selectText(pageIndex, text)
    override fun clearSelection() = controller.selectionManager.clearSelection()
    override val isEditingText: Boolean get() = controller.textEditorManager.isEditing
    override fun finishEditingText() = controller.textEditorManager.finishEditing()
    override var pendingTextImport: TextImport?
        get() = controller.pendingTextImport
        set(value) {
            controller.pendingTextImport = value
        }
}

/** The placement of elements and of texts by topic. */
private class PlacementPort(private val placement: PlacementManager, private val texts: TextPlacer) : EditorPlacement {
    override fun start(request: PlacementRequest) = placement.start(request)
    override fun cancel() = placement.cancel()
    override fun spotsFor(pageIndex: Int, block: PlacementBlock, policy: PlacementPolicy): List<PlacementSpot> = placement.spotsFor(pageIndex, block, policy)
    override fun pagesFor(spots: List<PlacementSpot>, template: (DrawPage) -> DrawPage?): List<DrawPage?> = placement.pagesFor(spots, template)
    override fun occupancyOf(pageIndex: Int, excluded: Set<String>): PageOccupancy = placement.occupancyOf(pageIndex, excluded)
    override val planner: PlacementPlanner get() = placement.planner
    override fun pdfTextOf(pageIndex: Int): PdfTextLayout? = placement.pdfTextOf(pageIndex)
    override val canPlaceByTopic: Boolean get() = texts.canPlaceByTopic

    override fun placeTexts(
        parts: List<Pair<Int?, String>>,
        color: Int,
        label: String,
        measure: ((DrawText) -> TextBlockSize?)?,
        onInserted: () -> Unit
    ): Boolean =
        if (measure == null) texts.start(parts, color, label, onInserted = onInserted)
        else texts.start(parts, color, label, measure = measure, onInserted = onInserted)

    override suspend fun partsByTopic(markdown: String, fallbackPage: Int?): List<Pair<Int?, String>> = texts.partsByTopic(markdown, fallbackPage)
    override fun sizeOfPage(page: DrawPage): Float = texts.sizeOfPage(page)
}

/** The rendering by the renderer of the tiles and its engines. */
private class RenderingPort(private val controller: DrawViewController) : EditorRendering {
    override val engines: DrawViewEngines get() = controller.engines
    override fun measureText(text: DrawText): TextBlockSize? = controller.measureText(text)

    override fun renderThumbnail(document: DrawDocument, content: PageContentSnapshot, widthPx: Int): ImageBitmap =
        controller.tileManager.tileRenderer.renderThumbnail(document, content, widthPx)

    override fun renderPageContent(canvas: Canvas, document: DrawDocument, content: PageContentSnapshot, mmToCanvas: Matrix2D, clipBounds: ComposeRect) =
        controller.tileManager.tileRenderer.renderPageContent(canvas, document, content, mmToCanvas, clipBounds)

    override fun pdfPageText(resourcePath: String, pdfPageIndex: Int): String? =
        controller.tileManager.tileRenderer.pdfAdapter?.getPageText(resourcePath, pdfPageIndex)
}
