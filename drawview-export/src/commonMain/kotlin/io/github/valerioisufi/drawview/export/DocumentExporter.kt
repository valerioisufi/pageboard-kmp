package io.github.valerioisufi.drawview.export

import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.PageContentSnapshot

/**
 * A format the documents are exported in (see `docs/export.md`): [PdfExporter],
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
 */
public class ExportOptions(
    public val pageIds: Set<String>? = null,
    public val backgroundPattern: Boolean = true
)

/**
 * What an export wrote: [pages], and the [skipped] elements that could not be read (a missing
 * image or PDF file).
 */
public class ExportResult(public val pages: Int, public val skipped: Int)

/**
 * The format of a folder export (see `docs/export.md`): a ZIP archive of individual PDFs, or a
 * single combined PDF document.
 */
public enum class FolderExportFormat(public val extension: String, public val mimeType: String) {
    ZIP("zip", "application/zip"),
    COMBINED_PDF("pdf", "application/pdf")
}

/**
 * The choices of a folder export.
 *
 * @param includeSubfolders whether notes in subdirectories are included.
 * @param backgroundPattern whether the lines and the dots of the backgrounds are drawn.
 */
public class FolderExportOptions(
    public val includeSubfolders: Boolean = true,
    public val backgroundPattern: Boolean = true
)

/**
 * What a folder export wrote: [documents] exported, total [pages], and [skipped] elements that
 * could not be read.
 */
public class FolderExportResult(
    public val documents: Int,
    public val pages: Int,
    public val skipped: Int
)

/**
 * A document to be exported from a folder or the home, with its [summary] and its [relativePath]
 * inside the archive or output.
 */
public class FolderDocumentItem(
    public val summary: io.github.valerioisufi.drawview.store.DocumentSummary,
    public val relativePath: String
)

/**
 * What is exported of a document: its [title], its [pages] (copies that stay as they are, see
 * [PageContentSnapshot.detached]) and the files of its [resources] (images and PDFs) by
 * identifier.
 */
public class DocumentContent(
    public val title: String,
    public val pages: List<PageContentSnapshot>,
    public val resources: Map<String, DrawResource>
) {
    public companion object {
        /**
         * The content of [document]: the pages [pageIds] (all of them when `null`) in their order.
         * Called on the thread of the document (see `OpenDocument.edit`).
         */
        public fun of(document: DrawDocument, pageIds: Set<String>? = null): DocumentContent {
            val pages = document.pages.filter { pageIds == null || it.id in pageIds }
            return DocumentContent(
                title = document.title,
                pages = pages.map { it.snapshotContent().detached() },
                resources = document.resources.associate { it.id to DrawResource(it.id, it.type, it.path, it.hash) }
            )
        }
    }
}
