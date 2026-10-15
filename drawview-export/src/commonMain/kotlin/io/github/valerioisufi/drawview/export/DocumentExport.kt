package io.github.valerioisufi.drawview.export

import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.platform.FileTarget

/**
 * The use case "export a document" (the Controller of the export): copies the content of the
 * document, writes it with an exporter and delivers the file where the user chose.
 *
 * The content is copied in the session of the document (see `OpenDocument.edit`), so that a
 * change arriving meanwhile from another device cannot make a file of two states; the editor and
 * the library give the identifier of the document and the pages only.
 */
class DocumentExport(private val documents: OpenDocuments) {

    /**
     * Exports the document [documentId] with [exporter] to [target]; [progress] gets the part
     * written, from 0 to 1. Throws when the document does not exist or the file cannot be written.
     */
    suspend fun export(
        documentId: String,
        exporter: DocumentExporter,
        options: ExportOptions,
        target: FileTarget,
        progress: (Float) -> Unit = {}
    ): ExportResult {
        val content = checkNotNull(content(documentId, options)) { "The document $documentId does not exist" }
        val result = exporter.export(content, options, target.path, progress)
        target.deliver(target.path)
        return result
    }

    /** The content of the document [documentId] to export with [options], or `null` when it does not exist. */
    suspend fun content(documentId: String, options: ExportOptions = ExportOptions()): DocumentContent? {
        val document = documents.open(documentId) ?: return null
        return try {
            document.edit { session -> DocumentContent.of(session.document, options.pageIds) }
        } finally {
            documents.close(document)
        }
    }

    companion object {
        /** Name of the file of the document [title] in the format of [exporter]. */
        fun fileName(title: String, exporter: DocumentExporter): String {
            val name = title.map { if (it in FORBIDDEN_IN_NAMES || it.code < 0x20) '_' else it }.joinToString("").trim().trimEnd('.')
            return "${name.ifEmpty { "Document" }}.${exporter.extension}"
        }

        /** Characters not allowed in the names of the files of one of the systems. */
        private const val FORBIDDEN_IN_NAMES = "/\\:*?\"<>|"
    }
}
