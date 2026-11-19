package io.github.valerioisufi.drawview.export

import io.github.valerioisufi.drawview.store.FolderSummary
import kotlinx.coroutines.CancellationException

/**
 * What is exported (see `docs/plans/export-ux.md`): pages of a note, notes and folders chosen together,
 * a folder or the Home.
 */
public sealed interface ExportScope {
    /** Whether the pages of one note are exported. */
    public val isSingleDocument: Boolean get() = this is Pages

    /** The formats offered for this scope, the first being the default. */
    public val formats: List<ExportFormat>
        get() = if (isSingleDocument) {
            listOf(ExportFormat.PDF, ExportFormat.IMAGES, ExportFormat.MARKDOWN, ExportFormat.ARCHIVE)
        } else {
            listOf(ExportFormat.PDF_PER_DOCUMENT, ExportFormat.COMBINED_PDF, ExportFormat.ARCHIVE)
        }

    /** The pages [pageIds] of the document [documentId], all of them when `null`. */
    public class Pages(public val documentId: String, public val pageIds: Set<String>? = null) : ExportScope

    /** The documents [documentIds] and the folders [folderIds] with their notes, chosen together in the library. */
    public class Items(public val documentIds: List<String>, public val folderIds: List<String>) : ExportScope

    /** The notes of the folder [folderId], of the Home when `null`. */
    public class Folder(public val folderId: String?) : ExportScope

    public companion object {
        /**
         * The scope of the items selected in the library: the pages of a document selected alone,
         * the folder selected alone, else the items together.
         */
        public fun ofSelection(documentIds: List<String>, folderIds: List<String>): ExportScope = when {
            documentIds.size == 1 && folderIds.isEmpty() -> Pages(documentIds.single())
            documentIds.isEmpty() && folderIds.size == 1 -> Folder(folderIds.single())
            else -> Items(documentIds, folderIds)
        }
    }
}

/**
 * What a [scope] gives (see [DocumentExport.resolve]): its [name] (the note, the folder; `null`
 * for several items or the Home), its [documents] with their paths in an archive, the number
 * of [pages] they have together, and the [folders] of the library the directories of the archive
 * stand for, by path (`"Math/Geometry"`), parents first.
 */
public class ExportContents(
    public val scope: ExportScope,
    public val name: String?,
    public val documents: List<FolderDocumentItem>,
    public val pages: Int,
    public val folders: Map<String, FolderSummary> = emptyMap()
) {
    /** Whether the pages of one note are exported (see [ExportScope.isSingleDocument]). */
    public val isSingleDocument: Boolean get() = scope.isSingleDocument

    /** The formats offered for these contents, the first being the default (see [ExportScope.formats]). */
    public val formats: List<ExportFormat> get() = scope.formats

    /**
     * The name of the file of these contents in [format], [pages] of them exported, named
     * [fallbackName] when they have no name of their own.
     */
    public fun fileName(format: ExportFormat, pages: Int = this.pages, fallbackName: String): String =
        "${DocumentExport.sanitizeFileName(name ?: fallbackName)}.${format.extension(pages)}"
}

/** A format of the export: what the user chooses in the sheet. */
public enum class ExportFormat {
    /** The pages of one note in a PDF. */
    PDF,

    /** The pages of one note as PNG images: one image, or a ZIP of one image per page. */
    IMAGES,

    /** Several notes as one PDF each, in a ZIP keeping their folders. */
    PDF_PER_DOCUMENT,

    /** Several notes one after the other in one PDF. */
    COMBINED_PDF,

    /** The text of one note as Markdown, with what the application adds (see [MarkdownExporter]). */
    MARKDOWN,

    /** Notes with all their content, to import again (see [ArchiveFormat]). */
    ARCHIVE;

    /** The extension of the file written for [pages] pages, without the dot. */
    public fun extension(pages: Int): String = when (this) {
        PDF, COMBINED_PDF -> "pdf"
        IMAGES -> if (pages == 1) "png" else "zip"
        PDF_PER_DOCUMENT -> "zip"
        MARKDOWN -> "md"
        ARCHIVE -> ArchiveFormat.EXTENSION
    }

    /** The media type of the file written for [pages] pages. */
    public fun mimeType(pages: Int): String = when (extension(pages)) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "md" -> "text/markdown"
        ArchiveFormat.EXTENSION -> ArchiveFormat.MIME_TYPE
        else -> "application/zip"
    }
}

/**
 * The exporters of the formats: [pdf] for the PDFs, [image] for the images of the pages, [markdown]
 * for the text.
 */
public class ExportWriters(
    public val pdf: PdfExporter,
    public val image: PageImageExporter? = null,
    public val markdown: MarkdownExporter = MarkdownExporter()
)

/**
 * How far an export is: the [fraction] written (0 to 1), the note being read ([item] of [items],
 * from 1) and its [name].
 */
public class ExportProgress(public val fraction: Float, public val item: Int, public val items: Int, public val name: String?)

/**
 * What an export wrote: [documents] and [pages]; the [skipped] elements whose file is missing,
 * in the notes [skippedIn]; the notes that could not be read ([missing]).
 */
public class ExportOutcome(
    public val documents: Int,
    public val pages: Int,
    public val skipped: Int,
    public val skippedIn: List<String> = emptyList(),
    public val missing: List<String> = emptyList()
)

/** Why an export failed. */
public enum class ExportFailure {
    /** No space left where the file is written. */
    NO_SPACE,

    /** The file cannot be written where it was asked. */
    NOT_WRITABLE,

    /** The note exported does not exist any more. */
    DOCUMENT_MISSING,

    /** Nothing to export: no note, no page. */
    NOTHING_TO_EXPORT,

    /** Any other cause. */
    UNKNOWN;

    public companion object {
        /** The failure that [error], thrown by an export, stands for. */
        public fun of(error: Throwable): ExportFailure {
            if (error is ExportException) return error.failure
            val messages = generateSequence(error) { it.cause }.mapNotNull { it.message?.lowercase() }.toList()
            fun any(vararg words: String) = messages.any { message -> words.any { it in message } }
            return when {
                any("no space", "enospc", "not enough space", "disk full", "quota") -> NO_SPACE
                any("permission denied", "eacces", "access is denied", "cannot write", "read-only", "erofs") -> NOT_WRITABLE
                else -> UNKNOWN
            }
        }
    }
}

/** An export that failed because of [failure]. */
public class ExportException(public val failure: ExportFailure, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: failure.name, cause)

/** Runs [block], turning what it throws into an [ExportException] (a cancellation stays one). */
internal inline fun <T> exportFailures(block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: ExportException) {
    throw e
} catch (e: Exception) {
    throw ExportException(ExportFailure.of(e), e.message, e)
}
