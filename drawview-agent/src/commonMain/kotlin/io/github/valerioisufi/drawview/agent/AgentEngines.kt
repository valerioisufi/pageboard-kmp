package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.PageContentSnapshot
import io.github.valerioisufi.drawview.model.PdfInk
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.model.geometry.Rect

/**
 * The editors of an application, as the proposals need them. Called on the thread of the documents.
 */
public interface AgentEditors {
    /** Asks the application to open the document [documentId] for its review. */
    public fun openForReview(documentId: String)

    /**
     * Makes the editor of [documentId] ready for a change it did not make: the text being written is
     * finished (so what was written is a change a proposal sees as newer) and the selection cleared.
     *
     * @return `false` when the document is in no editor: there would be nothing to undo it with.
     */
    public fun settle(documentId: String): Boolean

    /** Whether the document [documentId] is in an editor: what is measured is then as it is drawn. */
    public fun isInEditor(documentId: String): Boolean

    /**
     * The height in millimeters of [markdown] laid out on [widthMm] at [fontSize], as the editor of
     * [documentId] draws it, or `null` when it cannot be told.
     */
    public fun textHeight(documentId: String, markdown: String, widthMm: Float, fontSize: Float): Float?

    /** The text of the PDF page [pdf] as the editor of [documentId] knows it, or `null`. */
    public fun pdfLayout(documentId: String, pdf: DrawPdf): PdfTextLayout?
}

/**
 * The pages of a document in no editor, as the tools need them (Protected Variations): what an editor
 * tells of its own document (see [AgentEditors]), told without one, so that a model is answered the
 * same on a document open or not. Called on the thread of the documents, but [renderPage].
 */
public interface PageEngine {
    /** The height in millimeters of [markdown] laid out on [widthMm] at [fontSize], as a page draws it, or `null`. */
    public fun textHeight(markdown: String, widthMm: Float, fontSize: Float): Float?

    /** The text of the page [pageIndex] of the PDF file at [path], or `null` when it cannot be read. */
    public fun pdfLayout(path: String, pageIndex: Int): PdfTextLayout?

    /** What the page [pageIndex] of the PDF file at [path] draws, text or not, or `null` when it cannot be told. */
    public fun pdfInk(path: String, pageIndex: Int): PdfInk? = null

    /**
     * The page [page] as a PNG, at most [maxSidePx] on its longest side, or `null`.
     *
     * @param resources the files of its document its elements are drawn from.
     * @param area only this part of the page, in millimeters; `null`: all of it.
     * @param marks boxes outlined over it, each with its label.
     */
    public suspend fun renderPage(
        resources: List<DrawResource>,
        page: PageContentSnapshot,
        maxSidePx: Int,
        area: Rect? = null,
        marks: List<PageMark> = emptyList()
    ): ByteArray?
}

/**
 * A box outlined over a page drawn for a model, to tell it what the application sees there.
 *
 * @param box in millimeters from the top-left corner of the page.
 * @param label a few characters written at its corner.
 * @param color of its outline and its label, as ARGB.
 */
public class PageMark(public val box: Rect, public val label: String, public val color: Int) {
    public companion object {
        /** A text box. */
        public const val TEXT_COLOR: Int = 0xFFD32F2F.toInt()

        /** A free area. */
        public const val FREE_COLOR: Int = 0xFF2E7D32.toInt()
    }
}

/** A passage found by the search of an application, for a model. */
public data class AgentSearchHit(
    val documentId: String,
    val title: String,
    val pageId: String?,
    val snippet: String
)

/** An image file read by the application: where it is, its content, its size in pixels. */
public class AgentImage(public val path: String, public val hash: String, public val widthPx: Int, public val heightPx: Int)

/** The image files a client names, read by the application. */
public interface AgentImages {
    /** The image file at [path] as it is, or `null` when it is not an image the application reads. */
    public suspend fun read(path: String): AgentImage?

    /**
     * The image file at [path] kept among the files of the documents (the same content is kept once),
     * or `null` when it is not an image the application reads.
     */
    public suspend fun keep(path: String): AgentImage?

    /**
     * The bytes of the file at [path], a file of the images of the documents, or `null` when it cannot
     * be read or is larger than [maxBytes] (told before it is read).
     */
    public suspend fun bytes(path: String, maxBytes: Int): ByteArray? = null
}

/** A PDF file read by the application: where it is, its content, the size of each of its pages in millimeters. */
public class AgentPdf(public val path: String, public val hash: String, public val pageSizesMm: List<Pair<Float, Float>>)

/** The files a client names to bring into the notes, read by the application. */
public interface AgentFiles {
    /** The PDF file at [path] as it is, or `null` when it is not a PDF the application reads, or is too large. */
    public suspend fun readPdf(path: String): AgentPdf?

    /**
     * The PDF file at [path] kept among the files of the documents (the same content is kept once), or
     * `null` when it is not a PDF the application reads, or is too large.
     */
    public suspend fun keepPdf(path: String): AgentPdf?

    /**
     * The archive of the application at [path] brought into the library, in a new folder in [parentId]
     * (`null`: the root): the id of that folder, or `null` when it is not an archive the application
     * reads (nothing is added then).
     */
    public suspend fun importArchive(path: String, parentId: String?): String?
}

/**
 * What a client asks to export: the documents [documentIds] (the pages [pageIds] of one of them,
 * all of them when `null`), or the notes of the folder [folderId] and of the folders under it.
 *
 * @param format `pdf`, `png` or `markdown` for one document; `pdf_per_document` or `combined_pdf` for
 *   several; `drawview` (an archive of the application) for any.
 */
public class AgentExportRequest(
    public val documentIds: List<String>,
    public val folderId: String?,
    public val pageIds: Set<String>?,
    public val format: String
)

/** A file exported for a client: where it is, how large it is, what it holds. */
public class AgentExported(public val path: String, public val bytes: Long, public val documents: Int, public val pages: Int)

/** The export of the application, for the clients (Indirection: `drawview-agent` does not depend on `drawview-export`). */
public interface AgentExports {
    /**
     * Exports [request] into a new file of the folder of the exports of the agents, never over another
     * file; fails with an [AgentToolException] telling why.
     */
    public suspend fun export(request: AgentExportRequest): AgentExported
}

/**
 * What the tools need from the application, each optional: without one the tool that needs it is not
 * offered, or says that it cannot tell (rule 1 of the libraries, see `docs/plans/modularization.md`).
 *
 * @param editors the editors, for the review and for the placement of a text as it is drawn.
 * @param search searches [documentIds] only.
 * @param pdfText the text of the page `pageIndex` of the PDF file at `path`, or `null` when it has none.
 * @param handwriting the transcription of the handwriting of a page, or `null` when there is none.
 * @param renderPage the page as a PNG, at most `maxSidePx` on its longest side, or `null`: by an editor
 *   of its document; without one the page is drawn by [pages].
 * @param pages gives the pages of a document in no editor, asked each time: an application may have it only
 *   once its window is there. Without it the heights of such a document are estimates, the text of
 *   its PDF pages is not known, and its pages are not drawn.
 * @param images the image files a client adds (`add_image`), read by the application.
 * @param defaultTextSize the size of a text on a page that tells nothing about it.
 * @param files the PDF files and the archives a client brings into the notes (`import_pdf`, `import_archive`).
 * @param exports the export of the documents into files (`export`).
 * @param show asks the application to show the page `pageId` (`null`: where it was) of the document
 *   `documentId` in its editor, opening it: whether it could.
 */
public class AgentEngines(
    public val editors: AgentEditors? = null,
    public val search: (suspend (query: String, documentIds: Set<String>, limit: Int) -> List<AgentSearchHit>)? = null,
    public val pdfText: (suspend (path: String, pageIndex: Int) -> String?)? = null,
    public val handwriting: (suspend (documentId: String, pageId: String) -> String?)? = null,
    public val renderPage: (suspend (documentId: String, pageId: String, maxSidePx: Int) -> ByteArray?)? = null,
    public val images: AgentImages? = null,
    public val defaultTextSize: Float = 16f,
    public val pages: (() -> PageEngine?)? = null,
    public val files: AgentFiles? = null,
    public val exports: AgentExports? = null,
    public val show: (suspend (documentId: String, pageId: String?) -> Boolean)? = null
) {
    /** The text of the PDF page [pdf] of [document]: as its editor knows it, or read by [pages]. */
    internal fun pdfLayout(document: DrawDocument, pdf: DrawPdf): PdfTextLayout? {
        editors?.pdfLayout(document.id, pdf)?.let { return it }
        val resource = document.findResource(pdf.resourceId)?.takeIf { it.type == DrawResource.ResourceType.PDF } ?: return null
        return pages?.invoke()?.pdfLayout(resource.path, pdf.pdfPageIndex)
    }

    /** What the PDF page [pdf] of [document] draws, as [pages] tells it. */
    internal fun pdfInk(document: DrawDocument, pdf: DrawPdf): PdfInk? {
        val resource = document.findResource(pdf.resourceId)?.takeIf { it.type == DrawResource.ResourceType.PDF } ?: return null
        return pages?.invoke()?.pdfInk(resource.path, pdf.pdfPageIndex)
    }
}
