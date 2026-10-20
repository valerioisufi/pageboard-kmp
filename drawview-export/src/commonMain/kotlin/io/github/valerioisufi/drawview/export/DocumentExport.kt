package io.github.valerioisufi.drawview.export

import io.github.valerioisufi.drawview.platform.PlatformFiles
import io.github.valerioisufi.drawview.platform.newResourcePath
import io.github.valerioisufi.drawview.store.FolderSummary
import io.github.valerioisufi.drawview.store.OpenDocuments
import kotlinx.coroutines.flow.first

/**
 * The use case "export a document" (the Controller of the export): copies the content of the
 * document and writes it with an exporter (whoever asked delivers the file where the user chose).
 *
 * The content is copied in the session of the document (see `OpenDocument.edit`), so that a
 * change arriving meanwhile from another device cannot make a file of two states; the editor and
 * the library give the identifier of the document and the pages only.
 */
public class DocumentExport(private val documents: OpenDocuments) {

    /**
     * Exports the document [documentId] with [exporter] to the file [path]; [progress] gets the
     * part written, from 0 to 1. Throws when the document does not exist or the file cannot be
     * written.
     */
    public suspend fun export(
        documentId: String,
        exporter: DocumentExporter,
        options: ExportOptions,
        path: String,
        progress: (Float) -> Unit = {}
    ): ExportResult {
        val content = checkNotNull(content(documentId, options)) { "The document $documentId does not exist" }
        return exporter.export(content, options, path, progress)
    }

    /** The content of the document [documentId] to export with [options], or `null` when it does not exist. */
    public suspend fun content(documentId: String, options: ExportOptions = ExportOptions()): DocumentContent? {
        val document = documents.open(documentId) ?: return null
        return try {
            document.edit { session -> DocumentContent.of(session.document, options.pageIds) }
        } finally {
            documents.close(document)
        }
    }

    /**
     * Resolves the list of documents belonging to [folderId] (`null`: the home/root), each with
     * its [FolderDocumentItem.relativePath] calculated for the archive.
     */
    public suspend fun folderDocuments(
        folderId: String?,
        includeSubfolders: Boolean = true
    ): List<FolderDocumentItem> {
        val allDocs = documents.store.documents.first()
        val allFolders = documents.store.folders.first()
        val foldersById = allFolders.associateBy { it.id }

        val targetDocs = if (folderId == null) {
            if (includeSubfolders) {
                allDocs
            } else {
                allDocs.filter { it.folderId == null }
            }
        } else {
            if (includeSubfolders) {
                val subfolderIds = mutableSetOf(folderId)
                val queue = ArrayDeque<String>().apply { add(folderId) }
                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()
                    for (folder in allFolders) {
                        if (folder.parentId == current && subfolderIds.add(folder.id)) {
                            queue.add(folder.id)
                        }
                    }
                }
                allDocs.filter { it.folderId in subfolderIds }
            } else {
                allDocs.filter { it.folderId == folderId }
            }
        }

        val usedPaths = mutableSetOf<String>()
        return targetDocs.map { doc ->
            val relativeDir = if (folderId == null) {
                if (doc.folderId == null) "" else folderPath(doc.folderId, foldersById)
            } else {
                if (doc.folderId == folderId) "" else relativeFolderPath(doc.folderId, folderId, foldersById)
            }
            val baseName = sanitizeFileName(doc.name)
            var candidate = if (relativeDir.isEmpty()) "$baseName.pdf" else "$relativeDir/$baseName.pdf"
            var counter = 2
            while (candidate in usedPaths) {
                candidate = if (relativeDir.isEmpty()) "$baseName ($counter).pdf" else "$relativeDir/$baseName ($counter).pdf"
                counter++
            }
            usedPaths.add(candidate)
            FolderDocumentItem(doc, candidate)
        }
    }

    /**
     * Exports all documents of [folderId] (`null`: the home/root) with [pdfExporter] to [path] in
     * the format [format]; [progress] gets the part written, from 0 to 1.
     */
    public suspend fun exportFolder(
        folderId: String?,
        folderName: String,
        format: FolderExportFormat,
        options: FolderExportOptions,
        pdfExporter: PdfExporter,
        path: String,
        progress: (Float) -> Unit = {}
    ): FolderExportResult {
        val items = folderDocuments(folderId, options.includeSubfolders)
        if (items.isEmpty()) {
            when (format) {
                FolderExportFormat.ZIP -> {
                    val zipWriter = ZipWriter()
                    PlatformFiles.writeBytes(path, zipWriter.toByteArray())
                }
                FolderExportFormat.COMBINED_PDF -> {
                    pdfExporter.exportCombined(
                        contents = emptyList(),
                        options = ExportOptions(backgroundPattern = options.backgroundPattern),
                        path = path,
                        title = folderName
                    )
                }
            }
            progress(1f)
            return FolderExportResult(documents = 0, pages = 0, skipped = 0)
        }

        var totalPages = 0
        var totalSkipped = 0

        when (format) {
            FolderExportFormat.ZIP -> {
                val zipWriter = ZipWriter()
                val tempPdfPath = newResourcePath("export_folder_temp", "pdf")
                try {
                    items.forEachIndexed { index, item ->
                        val docContent = content(item.summary.id, ExportOptions(backgroundPattern = options.backgroundPattern))
                        if (docContent != null) {
                            val result = pdfExporter.export(
                                content = docContent,
                                options = ExportOptions(backgroundPattern = options.backgroundPattern),
                                path = tempPdfPath,
                                progress = { docProgress ->
                                    progress((index + docProgress) / items.size)
                                }
                            )
                            totalPages += result.pages
                            totalSkipped += result.skipped
                            val pdfBytes = PlatformFiles.readBytes(tempPdfPath)
                            if (pdfBytes != null) {
                                zipWriter.addEntry(item.relativePath, pdfBytes)
                            }
                            PlatformFiles.delete(tempPdfPath)
                        }
                        progress((index + 1f) / items.size)
                    }
                    PlatformFiles.writeBytes(path, zipWriter.toByteArray())
                } finally {
                    PlatformFiles.delete(tempPdfPath)
                }
            }
            FolderExportFormat.COMBINED_PDF -> {
                val contents = mutableListOf<DocumentContent>()
                for (item in items) {
                    content(item.summary.id, ExportOptions(backgroundPattern = options.backgroundPattern))?.let {
                        contents.add(it)
                    }
                }
                val result = pdfExporter.exportCombined(
                    contents = contents,
                    options = ExportOptions(backgroundPattern = options.backgroundPattern),
                    path = path,
                    title = folderName,
                    progress = progress
                )
                totalPages = result.pages
                totalSkipped = result.skipped
            }
        }

        return FolderExportResult(documents = items.size, pages = totalPages, skipped = totalSkipped)
    }

    private fun folderPath(folderId: String?, foldersById: Map<String, FolderSummary>): String {
        if (folderId == null) return ""
        val segments = generateSequence(foldersById[folderId]) { foldersById[it.parentId] }
            .map { sanitizeFileName(it.name) }
            .toList()
            .asReversed()
        return segments.joinToString("/")
    }

    private fun relativeFolderPath(folderId: String?, rootFolderId: String, foldersById: Map<String, FolderSummary>): String {
        if (folderId == null || folderId == rootFolderId) return ""
        val segments = generateSequence(foldersById[folderId]) {
            if (it.parentId == rootFolderId) null else foldersById[it.parentId]
        }
            .map { sanitizeFileName(it.name) }
            .toList()
            .asReversed()
        return segments.joinToString("/")
    }

    public companion object {
        /** Name of the file of the document [title] in the format of [exporter]. */
        public fun fileName(title: String, exporter: DocumentExporter): String =
            "${sanitizeFileName(title)}.${exporter.extension}"

        /** Name of the file of the folder [folderName] in the format of [format]. */
        public fun folderFileName(folderName: String, format: FolderExportFormat): String =
            "${sanitizeFileName(folderName)}.${format.extension}"

        /** Replaces characters not allowed in file names. */
        public fun sanitizeFileName(name: String): String {
            val clean = name.map { if (it in FORBIDDEN_IN_NAMES || it.code < 0x20) '_' else it }.joinToString("").trim().trimEnd('.')
            return clean.ifEmpty { "Document" }
        }

        /** Characters not allowed in the names of the files of one of the systems. */
        private const val FORBIDDEN_IN_NAMES = "/\\:*?\"<>|"
    }
}
