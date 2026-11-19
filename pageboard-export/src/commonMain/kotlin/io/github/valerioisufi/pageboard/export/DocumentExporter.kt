package io.github.valerioisufi.pageboard.export

import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.DrawResource
import io.github.valerioisufi.pageboard.model.PageContentSnapshot

/**
 * A format the documents are exported in (see `docs/plans/export.md`): [PdfExporter],
 * [PageImageExporter].
 */
public interface DocumentExporter {
    /** Extension of the files written, without the dot. */
    public val extension: String

    /** Media type of the files written. */
    public val mimeType: String

    /**
     * Writes [content] to the file [path]; [progress] gets the part done, from 0 to 1. Cancelled
     * with the coroutine calling it.
     */
    public suspend fun export(content: DocumentContent, options: ExportOptions, path: String, progress: (Float) -> Unit = {}): ExportResult
}

/**
 * The choices of an export.
 *
 * @param pageIds the pages exported, all of them when `null`.
 * @param backgroundPattern whether the lines and the dots of the backgrounds are drawn (their
 *   color always is).
 * @param pageIndices the pages exported by their place in the document (from 0), as well as in
 *   [pageIds]; all of them when `null`.
 */
public class ExportOptions(
    public val pageIds: Set<String>? = null,
    public val backgroundPattern: Boolean = true,
    public val pageIndices: Set<Int>? = null
)

/**
 * What an export wrote: [pages], and the [skipped] elements that could not be read (a missing
 * image or PDF file).
 */
public class ExportResult(public val pages: Int, public val skipped: Int)

/**
 * A document to be exported from a folder or the home, with its [summary] and its [relativePath]
 * inside the archive or output.
 */
public class FolderDocumentItem(
    public val summary: io.github.valerioisufi.pageboard.store.DocumentSummary,
    public val relativePath: String
)

/**
 * What is exported of a document: its [title], its [pages] (copies that stay as they are, see
 * [PageContentSnapshot.detached]), the files of its [resources] (images and PDFs) by
 * identifier, and when it was created ([createdAt]) and last changed ([modifiedAt]), in
 * milliseconds since the epoch, when they are known.
 */
public class DocumentContent(
    public val title: String,
    public val pages: List<PageContentSnapshot>,
    public val resources: Map<String, DrawResource>,
    public val createdAt: Long? = null,
    public val modifiedAt: Long? = null
) {
    public companion object {
        /**
         * The content of [document]: the pages [pageIds] and at [pageIndices] (all of them when
         * `null`) in their order. Called on the thread of the document (see `OpenDocument.edit`).
         */
        public fun of(document: DrawDocument, pageIds: Set<String>? = null, pageIndices: Set<Int>? = null): DocumentContent {
            val pages = document.pages.filterIndexed { index, page ->
                (pageIds == null || page.id in pageIds) && (pageIndices == null || index in pageIndices)
            }
            return DocumentContent(
                title = document.title,
                pages = pages.map { it.snapshotContent().detached() },
                resources = document.resources.associate { it.id to DrawResource(it.id, it.type, it.path, it.hash) }
            )
        }
    }
}
