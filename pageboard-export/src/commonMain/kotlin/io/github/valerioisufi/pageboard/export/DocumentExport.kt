package io.github.valerioisufi.pageboard.export

import io.github.valerioisufi.pageboard.platform.PlatformFiles
import io.github.valerioisufi.pageboard.platform.newResourcePath
import io.github.valerioisufi.pageboard.store.DocumentSummary
import io.github.valerioisufi.pageboard.store.FolderSummary
import io.github.valerioisufi.pageboard.store.OpenDocuments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.io.files.Path

/**
 * The use case "export a document" (the Controller of the export): copies the content of the
 * document and writes it with an exporter (whoever asked delivers the file where the user chose).
 *
 * The content is copied in the session of the document (see `OpenDocument.edit`), so that a
 * change arriving meanwhile from another device cannot make a file of two states; the editor and
 * the library give the identifier of the document and the pages only.
 */
public class DocumentExport(private val documents: OpenDocuments) {
    private val archives = ArchiveExporter(documents)


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

    /**
     * The content of the document [documentId] to export with [options], dated as the library
     * dates it, or `null` when it does not exist.
     */
    public suspend fun content(documentId: String, options: ExportOptions = ExportOptions()): DocumentContent? {
        val document = documents.open(documentId) ?: return null
        val content = try {
            document.edit { session -> DocumentContent.of(session.document, options.pageIds, options.pageIndices) }
        } finally {
            documents.close(document)
        }
        // Read after the session is closed, so that its last changes are counted
        val summary = documents.store.documents.first().firstOrNull { it.id == documentId } ?: return content
        return DocumentContent(content.title, content.pages, content.resources, summary.createdAt, summary.modifiedAt)
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
                val items = listOfNotNull(summary).map { it to emptyList<FolderSummary>() }
                ExportContents(scope, summary?.name, withPaths(items), scope.pageIds?.size ?: summary?.pageCount ?: 0)
            }
            is ExportScope.Folder -> {
                val items = folderDocuments(scope.folderId, includeSubfolders, allDocs, allFolders, foldersById, parents = emptyList())
                ExportContents(
                    scope,
                    scope.folderId?.let { foldersById[it]?.name },
                    withPaths(items),
                    items.sumOf { it.first.pageCount },
                    directories(items)
                )
            }
            is ExportScope.Items -> {
                val loose = scope.documentIds.mapNotNull { id -> allDocs.firstOrNull { it.id == id } }.map { it to emptyList<FolderSummary>() }
                val inFolders = scope.folderIds.flatMap { folderId ->
                    val folder = foldersById[folderId] ?: return@flatMap emptyList()
                    folderDocuments(folderId, includeSubfolders, allDocs, allFolders, foldersById, parents = listOf(folder))
                }
                val items = (loose + inFolders).distinctBy { it.first.id }
                ExportContents(scope, null, withPaths(items), items.sumOf { it.first.pageCount }, directories(items))
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
     * [includeSubfolders], each with the folders of the directory of its file: [parents] then its
     * folders under [folderId].
     */
    private fun folderDocuments(
        folderId: String?,
        includeSubfolders: Boolean,
        allDocs: List<DocumentSummary>,
        allFolders: List<FolderSummary>,
        foldersById: Map<String, FolderSummary>,
        parents: List<FolderSummary>
    ): List<Pair<DocumentSummary, List<FolderSummary>>> {
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
        return targetDocs.map { doc -> doc to parents + folderChain(doc.folderId, folderId, foldersById) }
    }

    /** The documents of [items] (each with the folders of the directory of its file) with paths that differ. */
    private fun withPaths(items: List<Pair<DocumentSummary, List<FolderSummary>>>): List<FolderDocumentItem> {
        val usedPaths = mutableSetOf<String>()
        return items.map { (doc, folders) ->
            val baseName = sanitizeFileName(doc.name)
            val dir = directoryPath(folders)
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
     * The directories of the files of [items] by path, with the folder each stands for, parents
     * first (the first folder of a path when two have the same name).
     */
    private fun directories(items: List<Pair<DocumentSummary, List<FolderSummary>>>): Map<String, FolderSummary> {
        val directories = LinkedHashMap<String, FolderSummary>()
        for ((_, folders) in items) {
            for (depth in 1..folders.size) {
                directories.getOrPut(directoryPath(folders.subList(0, depth))) { folders[depth - 1] }
            }
        }
        return directories
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
            ExportFormat.MARKDOWN -> exportMarkdown(contents, options, writers, path, progress)
            ExportFormat.ARCHIVE -> exportArchive(contents, path, progress)
            ExportFormat.RECORDINGS -> exportRecordings(contents, options, writers, path, progress)
        }
    }

    /** The notes of [contents] with all their content, as an archive of the application (see [ArchiveFormat]). */
    private suspend fun exportArchive(contents: ExportContents, path: String, progress: (ExportProgress) -> Unit): ExportOutcome {
        val items = contents.documents
        if (items.isEmpty()) throw ExportException(ExportFailure.NOTHING_TO_EXPORT)
        val result = archives.write(items) { index, name -> progress(ExportProgress(index.toFloat() / items.size, index + 1, items.size, name)) }
        if (result.documents == 0) throw ExportException(ExportFailure.DOCUMENT_MISSING)
        write(path, result.bytes)
        progress(ExportProgress(1f, items.size, items.size, null))
        return ExportOutcome(documents = result.documents, pages = result.pages, skipped = 0, skippedIn = emptyList(), missing = result.missing)
    }

    /** The text of the note of [contents] as Markdown (see [MarkdownExporter]). */
    private suspend fun exportMarkdown(
        contents: ExportContents,
        options: ExportOptions,
        writers: ExportWriters,
        path: String,
        progress: (ExportProgress) -> Unit
    ): ExportOutcome {
        val scope = contents.scope as? ExportScope.Pages ?: throw IllegalArgumentException("Markdown exports the text of one note")
        val pageOptions = ExportOptions(scope.pageIds ?: options.pageIds, options.backgroundPattern, options.pageIndices)
        val content = content(scope.documentId, pageOptions) ?: throw ExportException(ExportFailure.DOCUMENT_MISSING)
        progress(ExportProgress(0f, 1, 1, content.title))
        if (!writers.markdown.hasText(content, scope.documentId)) throw ExportException(ExportFailure.NOTHING_TO_EXPORT)
        write(path, writers.markdown.write(content, scope.documentId).encodeToByteArray())
        progress(ExportProgress(1f, 1, 1, content.title))
        return ExportOutcome(documents = 1, pages = content.pages.size, skipped = 0, skippedIn = emptyList())
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
                val times = contents.documents.firstOrNull()?.summary?.let(::entryTimes)
                try {
                    content.pages.forEachIndexed { index, page ->
                        image.export(DocumentContent(content.title, listOf(page), content.resources, content.createdAt, content.modifiedAt), pageOptions, temp)
                        val bytes = PlatformFiles.readBytes(temp) ?: throw ExportException(ExportFailure.NOT_WRITABLE, "Cannot read $temp")
                        zip.addEntry("$base - ${(index + 1).toString().padStart(digits, '0')}.png", bytes, times)
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
        // In the order of the tree, each directory just before what it holds: the tools that date
        // a directory when they leave it (ditto, the Archive Utility of macOS) then keep its date
        val items = contents.documents.sortedWith(compareBy(DirectoryOrder) { directorySegments(it.relativePath) })
        if (items.isEmpty()) throw ExportException(ExportFailure.NOTHING_TO_EXPORT)
        val documentOptions = ExportOptions(backgroundPattern = options.backgroundPattern)
        val zip = ZipWriter()
        val directories = mutableSetOf<String>()
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
                    addDirectories(directorySegments(item.relativePath), contents.folders, directories) { dir, dirTimes ->
                        zip.addDirectory(dir, dirTimes)
                    }
                    zip.addEntry(item.relativePath, bytes, entryTimes(item.summary))
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

    /** The finished lecture recordings of [contents] as `.m4a` files (and optional transcripts), in a ZIP keeping their folders. */
    private suspend fun exportRecordings(
        contents: ExportContents,
        options: ExportOptions,
        writers: ExportWriters,
        path: String,
        progress: (ExportProgress) -> Unit
    ): ExportOutcome {
        val source = writers.recordings ?: throw ExportException(ExportFailure.NOTHING_TO_EXPORT)
        val items = contents.documents.sortedWith(compareBy(DirectoryOrder) { directorySegments(it.relativePath) })
        val entriesByItem = mutableListOf<Pair<FolderDocumentItem, List<ExportedRecording>>>()
        for (item in items) {
            currentCoroutineContext().ensureActive()
            val recordings = source.of(item.summary.id)
            if (recordings.isNotEmpty()) entriesByItem += item to recordings
        }
        val totalSteps = entriesByItem.sumOf { (_, recordings) ->
            recordings.count { it.hasAudio || (options.includeTranscripts && !it.transcript.isNullOrBlank()) }
        }
        if (totalSteps == 0) throw ExportException(ExportFailure.NOTHING_TO_EXPORT)

        val zip = try {
            StreamingZipWriter(Path(path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = ExportFailure.of(e).takeIf { it != ExportFailure.UNKNOWN } ?: ExportFailure.NOT_WRITABLE
            throw ExportException(failure, "Cannot write $path", e)
        }
        val addedDirectories = mutableSetOf<String>()
        val usedPaths = mutableSetOf<String>()
        val exportedDocIds = mutableSetOf<String>()
        var skipped = 0
        val skippedIn = LinkedHashSet<String>()
        var completed = 0
        try {
            for ((item, recordings) in entriesByItem) {
                val dirSegments = directorySegments(item.relativePath)
                val folderName = dirSegments.lastOrNull()
                val noteBase = item.relativePath.substringAfterLast('/').removeSuffix(".pdf")
                val dirPrefix = if (dirSegments.isEmpty()) "" else dirSegments.joinToString("/") + "/"
                recordings.forEachIndexed { recIndex, rec ->
                    currentCoroutineContext().ensureActive()
                    val dot = rec.fileName.lastIndexOf('.')
                    val rawStem = if (dot > 0) rec.fileName.substring(0, dot) else rec.fileName
                    val audioExt = if (dot > 0) rec.fileName.substring(dot + 1).ifBlank { "m4a" } else "m4a"
                    val recStem = sanitizeFileName(rawStem)
                    val txtExt = rec.transcriptFileName?.substringAfterLast('.', "")?.ifBlank { "txt" } ?: "txt"

                    val willWrite = rec.hasAudio || (options.includeTranscripts && !rec.transcript.isNullOrBlank())
                    val resolvedTopic = if (willWrite && RecordingNamePart.TOPIC in options.recordingNameParts) {
                        rec.topic.ifBlank {
                            val resolve = rec.resolveTopic
                            if (resolve == null) {
                                ""
                            } else {
                                progress(ExportProgress(completed.toFloat() / totalSteps, completed + 1, totalSteps, item.summary.name))
                                try {
                                    resolve()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    ""
                                }
                            }
                        }
                    } else {
                        ""
                    }
                    val formatted = formatRecordingFileName(
                        parts = options.recordingNameParts,
                        folder = folderName,
                        note = noteBase,
                        date = recStem,
                        topic = resolvedTopic,
                        number = recIndex + 1,
                        totalInNote = recordings.size
                    )
                    val baseStem = "$dirPrefix$formatted"
                    var stem = baseStem
                    var counter = 2
                    while ("$stem.$audioExt" in usedPaths || "$stem.$txtExt" in usedPaths) {
                        stem = "$baseStem ($counter)"
                        counter++
                    }

                    val times = ZipEntryTimes(rec.recordedAt, rec.recordedAt)
                    if (rec.hasAudio) {
                        val temp = newResourcePath("export_recording", audioExt)
                        try {
                            progress(ExportProgress(completed.toFloat() / totalSteps, completed + 1, totalSteps, item.summary.name))
                            rec.writeAudio(temp) { fraction ->
                                progress(
                                    ExportProgress(
                                        (completed + fraction.coerceIn(0f, 1f)) / totalSteps,
                                        completed + 1,
                                        totalSteps,
                                        item.summary.name
                                    )
                                )
                            }
                            addDirectories(dirSegments, contents.folders, addedDirectories) { dir, dirTimes ->
                                zip.addDirectory(dir, dirTimes)
                            }
                            val context = currentCoroutineContext()
                            zip.addFile("$stem.$audioExt", Path(temp), times) {
                                if (!context.isActive) throw CancellationException("Export cancelled")
                            }
                            usedPaths += "$stem.$audioExt"
                            exportedDocIds += item.summary.id
                            completed++
                        } finally {
                            PlatformFiles.delete(temp)
                        }
                    } else {
                        skipped++
                        skippedIn += item.summary.name
                    }
                    val transcript = rec.transcript
                    if (options.includeTranscripts && !transcript.isNullOrBlank()) {
                        addDirectories(dirSegments, contents.folders, addedDirectories) { dir, dirTimes ->
                            zip.addDirectory(dir, dirTimes)
                        }
                        zip.addEntry("$stem.$txtExt", transcript.encodeToByteArray(), times)
                        usedPaths += "$stem.$txtExt"
                        exportedDocIds += item.summary.id
                        if (!rec.hasAudio) {
                            completed++
                            progress(ExportProgress(completed.toFloat() / totalSteps, completed, totalSteps, item.summary.name))
                        }
                    }
                }
            }
            zip.finish()
        } catch (e: Throwable) {
            zip.cancel()
            throw e
        }
        progress(ExportProgress(1f, totalSteps, totalSteps, null))
        return ExportOutcome(documents = exportedDocIds.size, pages = 0, skipped = skipped, skippedIn = skippedIn.toList())
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

    /**
     * Adds through [onDirectory] the directories of [segments] not [added] yet, parents first,
     * dated by the [folders] they stand for.
     */
    private inline fun addDirectories(
        segments: List<String>,
        folders: Map<String, FolderSummary>,
        added: MutableSet<String>,
        onDirectory: (path: String, times: ZipEntryTimes?) -> Unit
    ) {
        for (depth in 1..segments.size) {
            val path = segments.subList(0, depth).joinToString("/")
            if (added.add(path)) onDirectory(path, folders[path]?.let { ZipEntryTimes(it.modifiedAt, it.createdAt) })
        }
    }

    /** The dates in an archive of the file of the document [summary]. */
    private fun entryTimes(summary: DocumentSummary) = ZipEntryTimes(summary.modifiedAt, summary.createdAt)

    private fun imageWriter(writers: ExportWriters): PageImageExporter =
        writers.image ?: throw IllegalArgumentException("No exporter of the images")

    /** Writes [bytes] to [path], or tells that it cannot. */
    private fun write(path: String, bytes: ByteArray) {
        if (!PlatformFiles.writeBytes(path, bytes)) throw ExportException(ExportFailure.NOT_WRITABLE, "Cannot write $path")
    }

    /**
     * The folders from under [rootFolderId] (from the top when `null`) down to [folderId], the
     * folder of a document.
     */
    private fun folderChain(folderId: String?, rootFolderId: String?, foldersById: Map<String, FolderSummary>): List<FolderSummary> {
        if (folderId == null || folderId == rootFolderId) return emptyList()
        return generateSequence(foldersById[folderId]) { if (it.parentId == rootFolderId) null else foldersById[it.parentId] }
            .toList()
            .asReversed()
    }

    /** The path in an archive of the directory of [folders]. */
    private fun directoryPath(folders: List<FolderSummary>): String = folders.joinToString("/") { sanitizeFileName(it.name) }

    public companion object {
        /** Name of the file of the document [title] in the format of [exporter]. */
        public fun fileName(title: String, exporter: DocumentExporter): String =
            "${sanitizeFileName(title)}.${exporter.extension}"

        /** Replaces characters not allowed in file names. */
        public fun sanitizeFileName(name: String): String {
            val clean = name.map { if (it in FORBIDDEN_IN_NAMES || it.code < 0x20) '_' else it }.joinToString("").trim().trimEnd('.')
            return clean.ifEmpty { "Document" }
        }

        /** The directories of the file at [path] in an archive, from the top. */
        private fun directorySegments(path: String): List<String> =
            path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }

        /** The directories in the order of the tree: a directory before those under it, beside the others by name. */
        private val DirectoryOrder = Comparator<List<String>> { a, b ->
            for (i in 0 until minOf(a.size, b.size)) {
                val order = a[i].compareTo(b[i])
                if (order != 0) return@Comparator order
            }
            a.size.compareTo(b.size)
        }

        /** The part of the progress of a combined PDF spent reading the notes. */
        private const val READING = 0.1f

        /** Characters not allowed in the names of the files of one of the systems. */
        private const val FORBIDDEN_IN_NAMES = "/\\:*?\"<>|"
    }
}
