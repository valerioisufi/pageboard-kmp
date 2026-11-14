package io.github.valerioisufi.drawview.canvas.port

import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.canvas.controller.CollaboratorPresence
import io.github.valerioisufi.drawview.canvas.controller.PageSelection
import io.github.valerioisufi.drawview.canvas.controller.PlacementRequest
import io.github.valerioisufi.drawview.canvas.controller.PlacementSpot
import io.github.valerioisufi.drawview.canvas.controller.TextImport
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.canvas.render.tile.TextBlockSize
import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.ElementType
import io.github.valerioisufi.drawview.model.PageContentSnapshot
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.model.change.ChangeObserver
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.placement.PageOccupancy
import io.github.valerioisufi.drawview.model.placement.PlacementBlock
import io.github.valerioisufi.drawview.model.placement.PlacementPlanner
import io.github.valerioisufi.drawview.model.placement.PlacementPolicy

/**
 * What the editor of one document offers to the code added to it (the extensions of the UI): the
 * document and its changes, the pages and the view, the edits, the placement, the rendering and
 * the presence of the others. Given by the controller (`DrawViewController.ports`); the editor
 * implements the ports, so a member added to them breaks no one.
 */
public class EditorPorts(
    public val document: EditorDocument,
    public val pages: EditorPages,
    public val edits: EditorEdits,
    public val placement: EditorPlacement,
    public val rendering: EditorRendering,
    public val presence: CollaboratorPresence
)

/** The document of an editor and its changes. */
public interface EditorDocument {
    /** The document shown. */
    public val document: DrawDocument

    /** Whether the document cannot be changed here (a reader of a shared note). */
    public val isReadOnly: Boolean

    /** Applies [change] as the user would, undoable: the change undoing it, `null` when it changed nothing. */
    public fun submit(change: DocumentChange): DocumentChange?

    /** Runs [block]: the changes it submits are undone together. */
    public fun <T> group(block: () -> T): T

    /** Calls [observer] after each change of the document, from here or from another device. */
    public fun addObserver(observer: ChangeObserver)

    /** Stops calling [observer]. */
    public fun removeObserver(observer: ChangeObserver)
}

/** The pages of an editor as they are shown. */
public interface EditorPages {
    /** The pages selected in the page grid. */
    public val selection: PageSelection

    /** The indices of the pages on screen, from the first. */
    public fun visiblePageIndices(): List<Int>

    /** The index of the first page on screen, `null` before the first layout. */
    public fun firstVisiblePageIndex(): Int?

    /** The index of the page that fills the screen the most, `null` before the first layout. */
    public fun mostVisiblePageIndex(): Int?

    /** Shows the page [pageIndex], centered on [bounds] (millimeters of the page) when given. */
    public fun jumpToPage(pageIndex: Int, bounds: Rect? = null)

    /** The matrix from the millimeters of the page [pageIndex] to the pixels of the screen, `null` when not laid out. */
    public fun pageMmToScreen(pageIndex: Int): Matrix2D?

    /**
     * A number that changes each time the canvas is drawn again or moves: read in a composition,
     * what is drawn over the pages follows them.
     */
    public val frames: Int
}

/** What the user does in the editor, done by code. */
public interface EditorEdits {
    /** Adds an element of [type] holding [value] in [box] (millimeters) of the page [pageIndex], selected when [select]. */
    public fun <T : Any> addCustomElement(type: ElementType<T>, value: T, pageIndex: Int, box: Rect, select: Boolean = true): DrawCustomElement?

    /** Selects [text], on the page [pageIndex]. */
    public fun selectText(pageIndex: Int, text: DrawText)

    /** Clears the selection of elements. */
    public fun clearSelection()

    /** Whether a text is being written. */
    public val isEditingText: Boolean

    /** Ends the writing of the text being written. */
    public fun finishEditingText()

    /** A text long enough to be imported (pasted, dropped), waiting for the user to choose how; `null` without one. */
    public var pendingTextImport: TextImport?
}

/** Elements and texts placed in the free space of the pages, as the user chooses among spots. */
public interface EditorPlacement {
    /** Offers the spots of [request] to the user. */
    public fun start(request: PlacementRequest)

    /** Ends the placement shown, without placing. */
    public fun cancel()

    /** The spots of the page [pageIndex] where [block] fits, by [policy]. */
    public fun spotsFor(pageIndex: Int, block: PlacementBlock, policy: PlacementPolicy): List<PlacementSpot>

    /** The pages of [spots], the new ones added (from [template]); `null` for a page that cannot be added. */
    public fun pagesFor(spots: List<PlacementSpot>, template: (DrawPage) -> DrawPage? = { it }): List<DrawPage?>

    /** What is on the page [pageIndex], as the placement sees it, without the elements [excluded]. */
    public fun occupancyOf(pageIndex: Int, excluded: Set<String> = emptySet()): PageOccupancy

    /** The planner of the spots, for several items placed together on what [occupancyOf] gives. */
    public val planner: PlacementPlanner

    /** The text of the PDF page under the page [pageIndex], with its layout, when known. */
    public fun pdfTextOf(pageIndex: Int): PdfTextLayout?

    /** Whether texts can be placed by topic (the application gives the topics of the pages). */
    public val canPlaceByTopic: Boolean

    /**
     * Offers to place [parts] (each for a page index, or the end) as texts of [color], labeled
     * [label], measured by [measure]: `false` when the document has no page.
     */
    public fun placeTexts(
        parts: List<Pair<Int?, String>>,
        color: Int,
        label: String,
        measure: ((DrawText) -> TextBlockSize?)? = null,
        onInserted: () -> Unit = {}
    ): Boolean

    /** [markdown] split by the pages it is about (`null`: none), [fallbackPage] for what is about none. */
    public suspend fun partsByTopic(markdown: String, fallbackPage: Int?): List<Pair<Int?, String>>

    /** The size (points) of a text placed on [page]. */
    public fun sizeOfPage(page: DrawPage): Float
}

/** How an editor draws: its engines, its measures, its pictures of pages. */
public interface EditorRendering {
    /** The engines of the editor (PDF, formulas, code, topics). */
    public val engines: DrawViewEngines

    /** The size of [text] laid out, `null` before the text engine is given. */
    public fun measureText(text: DrawText): TextBlockSize?

    /** [content] of a page of [document] drawn [widthPx] wide (on any thread). */
    public fun renderThumbnail(document: DrawDocument, content: PageContentSnapshot, widthPx: Int): ImageBitmap

    /** [content] of a page of [document] drawn on [canvas] by [mmToCanvas], within [clipBounds]. */
    public fun renderPageContent(canvas: Canvas, document: DrawDocument, content: PageContentSnapshot, mmToCanvas: Matrix2D, clipBounds: ComposeRect)

    /** The text of the page [pdfPageIndex] of the PDF file [resourcePath], when the PDF engine reads it. */
    public fun pdfPageText(resourcePath: String, pdfPageIndex: Int): String?
}
