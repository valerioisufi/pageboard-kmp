package io.github.valerioisufi.drawview.export

import io.github.valerioisufi.drawview.platform.PlatformFiles
import io.github.valerioisufi.drawview.platform.newResourcePath
import io.github.valerioisufi.drawview.store.DocumentSummary
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
            document.edit { session -> DocumentContent.of(session.document, options.pageIds, options.pageIndices) }
        } finally {
            documents.close(document)
        }
    }

    /**
     * What [scope] gives: its name, its notes with their paths in an archive (the notes of its
     * folders with those of their subfolders when [includeSubfolders]), its pages. A note both
     * chosen and in a folder chosen is given once.
     */
    public suspend fun resolve(scope: ExportScope, includeSubfolders: Boolean = true): ExportContents {
        val allDocs = documents.store.documents.first()
        val allFolders = documents.store.folders.first()
        val foldersById = allFolders.associateBy { it.id }
        return when (scope) {
            is ExportScope.Pages -> {
                val summary = allDocs.firstOrNull { it.id == scope.documentId }
                val items = listOfNotNull(summary).map { it to "" }
                ExportContents(scope, summary?.name, withPaths(items), scope.pageIds?.size ?: summary?.pageCount ?: 0)
            }
            is ExportScope.Folder -> {
                val items = folderDocuments(scope.folderId, includeSubfolders, allDocs, allFolders, foldersById, dirPrefix = "")
                ExportContents(scope, scope.folderId?.let { foldersById[it]?.name }, withPaths(items), items.sumOf { it.first.pageCount })
            }
            is ExportScope.Items -> {
                val loose = scope.documentIds.mapNotNull { id -> allDocs.firstOrNull { it.id == id } }.map { it to "" }
                val inFolders = scope.folderIds.flatMap { folderId ->
                    val folder = foldersById[folderId] ?: return@flatMap emptyList()
                    folderDocuments(folderId, includeSubfolders, allDocs, allFolders, foldersById, dirPrefix = sanitizeFileName(folder.name))
                }
                val items = (loose + inFolders).distinctBy { it.first.id }
                ExportContents(scope, null, withPaths(items), items.sumOf { it.first.pageCount })
            }
        }
    }

    /**
     * Resolves the list of documents belonging to [folderId] (`null`: the home/root), each with
     * its [FolderDocumentItem.relativePath] calculated for the archive.
     */
    public suspend fun folderDocuments(
        folderId: String?,
        includeSubfolders: Boolean = true
    ): List<FolderDocumentItem> = resolve(ExportScope.Folder(folderId), includeSubfolders).documents

    /**
     * The documents of [folderId] (`null`: the home), with those of its subfolders when
     * [includeSubfolders], each with the directory of its file: [dirPrefix] then its folders
     * under [folderId].
     */
    private fun folderDocuments(
        folderId: String?,
        includeSubfolders: Boolean,
        allDocs: List<DocumentSummary>,
        allFolders: List<FolderSummary>,
        foldersById: Map<String, FolderSummary>,
        dirPrefix: String
    ): List<Pair<DocumentSummary, String>> {
        val targetDocs = if (folderId == null) {
            if (includeSubfolders) allDocs else allDocs.filter { it.folderId == null }
        } else if (includeSubfolders) {
            val subfolderIds = mutableSetOf(folderId)
            val queue = ArrayDeque<String>().apply { add(folderId) }
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                for (folder in allFolders) {
                    if (folder.parentId == current && subfolderIds.add(folder.id)) queue.add(folder.id)
                }
            }
            allDocs.filter { it.folderId in subfolderIds }
        } else {
            allDocs.filter { it.folderId == folderId }
        }
        return targetDocs.map { doc ->
            val relativeDir = if (folderId == null) {
                if (doc.folderId == null) "" else folderPath(doc.folderId, foldersById)
            } else {
                if (doc.folderId == folderId) "" else relativeFolderPath(doc.folderId, folderId, foldersById)
            }
            doc to listOf(dirPrefix, relativeDir).filter { it.isNotEmpty() }.joinToString("/")
        }
    }

    /** The documents of [items] (each with the directory of its file) with paths that differ. */
    private fun withPaths(items: List<Pair<DocumentSummary, String>>): List<FolderDocumentItem> {
        val usedPaths = mutableSetOf<String>()
        return items.map { (doc, dir) ->
            val baseName = sanitizeFileName(doc.name)
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            var candidate = "$prefix$baseName.pdf"
            var counter = 2
            while (candidate in usedPaths) {
                candidate = "$prefix$baseName ($counter).pdf"
                counter++
            }
            usedPaths.add(candidate)
            FolderDocumentItem(doc, candidate)
        }
    }

    /**
     * Exports [contents] (see [resolve]) in [format] with [writers] to the file [path];
     * [progress] gets how far it is. The notes that cannot be read any more are left out and
     * told in [ExportOutcome.missing]. Throws an [ExportException] telling why it failed.
     */
    public suspend fun export(
        contents: ExportContents,
        format: ExportFormat,
        options: ExportOptions,
        writers: ExportWriters,
        path: String,
        progress: (ExportProgress) -> Unit = {}
    ): ExportOutcome = exportFailures {
        when (format) {
            ExportFormat.PDF, ExportFormat.IMAGES -> exportPages(contents, format, options, writers, path, progress)
            ExportFormat.PDF_PER_DOCUMENT -> exportPdfPerDocument(contents, options, writers, path, progress)
            ExportFormat.COMBINED_PDF -> exportCombined(contents, options, writers, path, progress)
        }
    }

    /** The pages of the note of [contents] as a PDF, an image, or a ZIP of images. */
    private suspend fun exportPages(
        contents: ExportContents,
        format: ExportFormat,
        options: ExportOptions,
        writers: ExportWriters,
        path: String,
        progress: (ExportProgress) -> Unit
    ): ExportOutcome {
        val scope = contents.scope as? ExportScope.Pages ?: throw IllegalArgumentException("$format exports the pages of one note")
        val pageOptions = ExportOptions(scope.pageIds ?: options.pageIds, options.backgroundPattern, options.pageIndices)
        val content = content(scope.documentId, pageOptions) ?: throw ExportException(ExportFailure.DOCUMENT_MISSING)
        if (content.pages.isEmpty()) throw ExportException(ExportFailure.NOTHING_TO_EXPORT)
        val report = { fraction: Float -> progress(ExportProgress(fraction, 1, 1, content.title)) }
        val result = when {
            format == ExportFormat.PDF -> writers.pdf.export(content, pageOptions, path, report)
            content.pages.size == 1 -> imageWriter(writers).export(content, pageOptions, path, report)
            else -> {
                val image = imageWriter(writers)
                val zip = ZipWriter()
                val digits = content.pages.size.toString().length
                val base = sanitizeFileName(content.title)
                val temp = newResourcePath("export_page", "png")
                try {
                    content.pages.forEachIndexed { index, page ->
                        image.export(DocumentContent(content.title, listOf(page), content.resources), pageOptions, temp)
                        val bytes = PlatformFiles.readBytes(temp) ?: throw ExportException(ExportFailure.NOT_WRITABLE, "Cannot read $temp")
                        zip.addEntry("$base - ${(index + 1).toString().padStart(digits, '0')}.png", bytes)
                        report((index + 1f) / content.pages.size)
                    }
                } finally {
                    PlatformFiles.delete(temp)
                }
                write(path, zip.toByteArray())
                ExportResult(content.pages.size, 0)
            }
        }
        return ExportOutcome(
            documents = 1,
            pages = result.pages,
            skipped = result.skipped,
            skippedIn = if (result.skipped > 0) listOf(content.title) else emptyList()
        )
    }

    /** The notes of [contents] as one PDF each, in a ZIP. */
    private suspend fun exportPdfPerDocument(
        contents: ExportContents,
        options: ExportOptions,
        writers: ExportWriters,
        path: String,
        progress: (ExportProgress) -> Unit
    ): ExportOutcome {
        val items = contents.documents
        if (items.isEmpty()) throw ExportException(ExportFailure.NOTHING_TO_EXPORT)
        val documentOptions = ExportOptions(backgroundPattern = options.backgroundPattern)
        val zip = ZipWriter()
        val temp = newResourcePath("export_folder_temp", "pdf")
        var pages = 0
        val skippedIn = mutableListOf<String>()
        var skipped = 0
        val missing = mutableListOf<String>()
        try {
            items.forEachIndexed { index, item ->
                val name = item.summary.name
                progress(ExportProgress(index.toFloat() / items.size, index + 1, items.size, name))
                val content = content(item.summary.id, documentOptions)
                if (content == null) {
                    missing += name
                } else {
                    val result = writers.pdf.export(content, documentOptions, temp) { fraction ->
                        progress(ExportProgress((index + fraction) / items.size, index + 1, items.size, name))
                    }
                    pages += result.pages
                    if (result.skipped > 0) {
                        skipped += result.skipped
                        skippedIn += name
                    }
                    val bytes = PlatformFiles.readBytes(temp) ?: throw ExportException(ExportFailure.NOT_WRITABLE, "Cannot read $temp")
                    zip.addEntry(item.relativePath, bytes)
                    PlatformFiles.delete(temp)
                }
            }
        } finally {
            PlatformFiles.delete(temp)
        }
        write(path, zip.toByteArray())
        progress(ExportProgress(1f, items.size, items.size, null))
        return ExportOutcome(items.size - missing.size, pages, skipped, skippedIn, missing)
    }

    /** The notes of [contents] one after the other in one PDF. */
    private suspend fun exportCombined(
        contents: ExportContents,
        options: ExportOptions,
        writers: ExportWriters,
        path: String,
        progress: (ExportProgress) -> Unit
    ): ExportOutcome {
        val items = contents.documents
        if (items.isEmpty()) throw ExportException(ExportFailure.NOTHING_TO_EXPORT)
        val documentOptions = ExportOptions(backgroundPattern = options.backgroundPattern)
        val read = mutableListOf<DocumentContent>()
        val missing = mutableListOf<String>()
        // Reading the notes is the first tenth, writing them the rest
        items.forEachIndexed { index, item ->
            progress(ExportProgress(index * READING / items.size, index + 1, items.size, item.summary.name))
            val content = content(item.summary.id, documentOptions)
            if (content == null) missing += item.summary.name else read += content
        }
        if (read.isEmpty()) throw ExportException(ExportFailure.DOCUMENT_MISSING)
        val result = writers.pdf.exportCombined(read, documentOptions, path, title = contents.name) { fraction ->
            progress(ExportProgress(READING + fraction * (1 - READING), items.size, items.size, contents.name))
        }
        return ExportOutcome(read.size, result.pages, result.skipped, missing = missing)
    }

    private fun imageWriter(writers: ExportWriters): PageImageExporter =
        writers.image ?: throw IllegalArgumentException("No exporter of the images")

    /** Writes [bytes] to [path], or tells that it cannot. */
    private fun write(path: String, bytes: ByteArray) {
        if (!PlatformFiles.writeBytes(path, bytes)) throw ExportException(ExportFailure.NOT_WRITABLE, "Cannot write $path")
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
        val contents = resolve(ExportScope.Folder(folderId), options.includeSubfolders).let {
            ExportContents(it.scope, folderName, it.documents, it.pages)
        }
        val outcome = export(
            contents = contents,
            format = if (format == FolderExportFormat.ZIP) ExportFormat.PDF_PER_DOCUMENT else ExportFormat.COMBINED_PDF,
            options = ExportOptions(backgroundPattern = options.backgroundPattern),
            writers = ExportWriters(pdfExporter),
            path = path
        ) { progress(it.fraction) }
        return FolderExportResult(documents = outcome.documents, pages = outcome.pages, skipped = outcome.skipped)
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

        /** The part of the progress of a combined PDF spent reading the notes. */
        private const val READING = 0.1f

        /** Characters not allowed in the names of the files of one of the systems. */
        private const val FORBIDDEN_IN_NAMES = "/\\:*?\"<>|"
    }
}
