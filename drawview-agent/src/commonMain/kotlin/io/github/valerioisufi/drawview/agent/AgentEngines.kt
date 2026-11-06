package io.github.valerioisufi.drawview.agent

import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.PdfTextLayout

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
}

/**
 * What the tools need from the application, each optional: without one the tool that needs it is not
 * offered, or says that it cannot tell (rule 1 of the libraries, see `docs/modularization.md`).
 *
 * @param editors the editors, for the review and for the placement of a text as it is drawn.
 * @param search searches [documentIds] only.
 * @param pdfText the text of the page `pageIndex` of the PDF file at `path`, or `null` when it has none.
 * @param handwriting the transcription of the handwriting of a page, or `null` when there is none.
 * @param renderPage the page as a PNG, at most `maxSidePx` on its longest side, or `null`.
 * @param images the image files a client adds (`add_image`), read by the application.
 * @param defaultTextSize the size of a text on a page that tells nothing about it.
 */
public class AgentEngines(
    public val editors: AgentEditors? = null,
    public val search: (suspend (query: String, documentIds: Set<String>, limit: Int) -> List<AgentSearchHit>)? = null,
    public val pdfText: (suspend (path: String, pageIndex: Int) -> String?)? = null,
    public val handwriting: (suspend (documentId: String, pageId: String) -> String?)? = null,
    public val renderPage: (suspend (documentId: String, pageId: String, maxSidePx: Int) -> ByteArray?)? = null,
    public val images: AgentImages? = null,
    public val defaultTextSize: Float = 16f
)
