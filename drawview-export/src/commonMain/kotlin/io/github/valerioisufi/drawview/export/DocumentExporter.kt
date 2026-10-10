package io.github.valerioisufi.drawview.export

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.PageContentSnapshot

/**
 * A format the documents are exported in (see `docs/export.md`): [PdfExporter],
 * [PageImageExporter].
 */
interface DocumentExporter {
    /** Extension of the files written, without the dot. */
    val extension: String

    /** Media type of the files written. */
    val mimeType: String

    /**
     * Writes [content] to the file [path]; [progress] gets the part done, from 0 to 1. Cancelled
     * with the coroutine calling it.
     */
    suspend fun export(content: DocumentContent, options: ExportOptions, path: String, progress: (Float) -> Unit = {}): ExportResult
}

/**
 * The choices of an export.
 *
 * @param pageIds the pages exported, all of them when `null`.
 * @param backgroundPattern whether the lines and the dots of the backgrounds are drawn (their
 *   color always is).
 */
class ExportOptions(
    val pageIds: Set<String>? = null,
    val backgroundPattern: Boolean = true
)

/**
 * What an export wrote: [pages], and the [skipped] elements that could not be read (a missing
 * image or PDF file).
 */
class ExportResult(val pages: Int, val skipped: Int)

/**
 * What is exported of a document: its [title], its [pages] (copies that stay as they are, see
 * [PageContentSnapshot.detached]) and the files of its [resources] (images and PDFs) by
 * identifier.
 */
class DocumentContent(
    val title: String,
    val pages: List<PageContentSnapshot>,
    val resources: Map<String, DrawResource>
) {
    companion object {
        /**
         * The content of [document]: the pages [pageIds] (all of them when `null`) in their order.
         * Called on the thread of the document (see `OpenDocument.edit`).
         */
        fun of(document: DrawDocument, pageIds: Set<String>? = null): DocumentContent {
            val pages = document.pages.filter { pageIds == null || it.id in pageIds }
            return DocumentContent(
                title = document.title,
                pages = pages.map { it.snapshotContent().detached() },
                resources = document.resources.associate { it.id to DrawResource(it.id, it.type, it.path, it.hash) }
            )
        }
    }
}
