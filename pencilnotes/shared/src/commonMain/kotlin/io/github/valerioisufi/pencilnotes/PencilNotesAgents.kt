package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.drawview.agent.AgentEndpoint
import io.github.valerioisufi.drawview.agent.AgentEngines
import io.github.valerioisufi.drawview.agent.AgentFiles
import io.github.valerioisufi.drawview.agent.AgentPdf
import io.github.valerioisufi.drawview.agent.AgentErrorCode
import io.github.valerioisufi.drawview.agent.AgentExportRequest
import io.github.valerioisufi.drawview.agent.AgentExported
import io.github.valerioisufi.drawview.agent.AgentExports
import io.github.valerioisufi.drawview.agent.AgentToolException
import io.github.valerioisufi.drawview.export.ArchiveException
import io.github.valerioisufi.drawview.export.ArchiveImporter
import io.github.valerioisufi.drawview.export.DocumentExport
import io.github.valerioisufi.drawview.export.ExportException
import io.github.valerioisufi.drawview.export.ExportFormat
import io.github.valerioisufi.drawview.export.ExportOptions
import io.github.valerioisufi.drawview.export.ExportScope
import io.github.valerioisufi.drawview.export.ExportWriters
import io.github.valerioisufi.drawview.platform.dataDirectory
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import io.github.valerioisufi.drawview.agent.AgentImage
import io.github.valerioisufi.drawview.agent.AgentImages
import io.github.valerioisufi.drawview.agent.AgentJournal
import io.github.valerioisufi.drawview.agent.AgentSearchHit
import io.github.valerioisufi.drawview.agent.ExternalAgents
import io.github.valerioisufi.drawview.agent.PageEngine
import io.github.valerioisufi.drawview.agent.ui.AgentReviewUi
import io.github.valerioisufi.drawview.agent.ui.SessionAgentEditors
import io.github.valerioisufi.drawview.canvas.platform.decodeImageBitmap
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.platform.PlatformFiles
import io.github.valerioisufi.drawview.platform.Sha256
import io.github.valerioisufi.drawview.platform.newResourcePath
import io.github.valerioisufi.drawview.search.SearchAnchor
import io.github.valerioisufi.drawview.search.ui.ContentSearch
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.extension.LibraryAction
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext

/**
 * The external models of PencilNotes, on the documents of the application: given by the application
 * of a platform that lets clients in (see `docs/plans/agent-tools.md`), none on the others.
 *
 * @param readFile the content of the file at a path of this device, or `null` when it is not a file or
 *   holds more than the bytes given: for the images a client adds.
 * @param journal where the proposals and the activity are kept, to outlive the application.
 */
class PencilNotesAgents(
    private val readFile: (path: String, maxBytes: Long) -> ByteArray? = { _, _ -> null },
    journal: AgentJournal? = null
) {
    /** The search of the library, once made. */
    @Volatile
    internal var contentSearch: ContentSearch? = null

    /** The pages of the documents in no editor, once the fonts of a window are there. */
    @Volatile
    internal var pageEngine: PageEngine? = null

    /** What the exports of the models are written with, once the fonts of a window are there. */
    @Volatile
    internal var exportWriters: ExportWriters? = null

    private val reviews = Channel<String>(Channel.CONFLATED)

    /** The documents to open for a review of their proposals, or to show to the user: the application opens them in an editor. */
    val reviewRequests: Flow<String> = reviews.receiveAsFlow()

    private val editors = SessionAgentEditors(PencilNotesData.sessions, openDocument = { reviews.trySend(it) })

    val agents: ExternalAgents = ExternalAgents(
        documents = PencilNotesData.documents,
        engines = AgentEngines(
            editors = editors,
            search = ::search,
            pdfText = ::pdfText,
            renderPage = editors::renderPage,
            pages = { pageEngine },
            images = object : AgentImages {
                override suspend fun read(path: String): AgentImage? = image(path, keep = false)

                override suspend fun keep(path: String): AgentImage? = image(path, keep = true)

                override suspend fun bytes(path: String, maxBytes: Int): ByteArray? =
                    withContext(Dispatchers.Default) { readFile(path, maxBytes.toLong()) }
            },
            files = object : AgentFiles {
                override suspend fun readPdf(path: String): AgentPdf? = pdf(path, keep = false)

                override suspend fun keepPdf(path: String): AgentPdf? = pdf(path, keep = true)

                override suspend fun importArchive(path: String, parentId: String?): String? = withContext(Dispatchers.Default) {
                    val bytes = readFile(path, MAX_ARCHIVE_BYTES) ?: return@withContext null
                    val name = PlatformFiles.name(path).substringBeforeLast('.').ifBlank { "Archive" }
                    try {
                        ArchiveImporter(PencilNotesData.documents).import(bytes, name, parentId).folderId
                    } catch (_: ArchiveException) {
                        null
                    }
                }
            },
            exports = object : AgentExports {
                override suspend fun export(request: AgentExportRequest): AgentExported = exportFor(request)
            },
            show = ::show
        ),
        journal = journal
    )

    val endpoint: AgentEndpoint = AgentEndpoint(agents, serverName = "PencilNotes", serverVersion = "3.0.0")

    private val ui = AgentReviewUi(agents)

    /** The review in the editors. */
    internal val editorExtension: EditorExtension = ui.editorExtension

    /** The access, from the library. */
    internal val libraryAction: LibraryAction = ui.libraryAction

    /**
     * [request] exported as the export sheet does it, into a new file of the folder of the exports of
     * the models in the data directory, never over another file.
     */
    private suspend fun exportFor(request: AgentExportRequest): AgentExported = withContext(Dispatchers.Default) {
        val writers = exportWriters
            ?: throw AgentToolException(AgentErrorCode.UNAVAILABLE, "The export is ready once a window of the application is open.")
        val format = when (request.format) {
            "pdf" -> ExportFormat.PDF
            "png" -> ExportFormat.IMAGES
            "markdown" -> ExportFormat.MARKDOWN
            "pdf_per_document" -> ExportFormat.PDF_PER_DOCUMENT
            "combined_pdf" -> ExportFormat.COMBINED_PDF
            else -> ExportFormat.ARCHIVE
        }
        val scope = when {
            request.folderId != null -> ExportScope.Folder(request.folderId)
            request.documentIds.size == 1 -> ExportScope.Pages(request.documentIds.single(), request.pageIds)
            else -> ExportScope.Items(request.documentIds, emptyList())
        }
        val export = DocumentExport(PencilNotesData.documents)
        val contents = export.resolve(scope)
        val pages = request.pageIds?.size ?: contents.pages
        val path = freePath(dataDirectory().trimEnd('/', '\\') + "/exports/agent", contents.fileName(format, pages, fallbackName = "Notes"))
        val outcome = try {
            export.export(contents, format, ExportOptions(pageIds = request.pageIds), writers, path)
        } catch (e: ExportException) {
            throw AgentToolException(AgentErrorCode.REFUSED, "The export failed: ${e.failure.name.lowercase()}.")
        }
        AgentExported(path, PlatformFiles.readBytes(path)?.size?.toLong() ?: 0L, outcome.documents, outcome.pages)
    }

    /** [name] in [directory], with a number when a file has it: "Notes (2).pdf"... */
    private fun freePath(directory: String, name: String): String {
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        var path = "$directory/$name"
        var n = 2
        while (PlatformFiles.exists(path)) path = "$directory/$stem (${n++}).$extension"
        return path
    }

    /** Opens the document [documentId] in an editor and, when [pageId] is given, brings it to that page. */
    private suspend fun show(documentId: String, pageId: String?): Boolean {
        reviews.trySend(documentId)
        if (pageId == null) return true
        val controller = withTimeoutOrNull(SHOW_TIMEOUT_MS) {
            var found = PencilNotesData.sessions.find(documentId)?.controller
            while (found == null) {
                delay(100)
                found = PencilNotesData.sessions.find(documentId)?.controller
            }
            found
        } ?: return false
        withContext(Dispatchers.Main) { controller.pageManager.jumpToPage(pageId) }
        return true
    }

    private suspend fun search(query: String, documentIds: Set<String>, limit: Int): List<AgentSearchHit> {
        val engine = contentSearch?.engine ?: return emptyList()
        // Asked for more: what is not allowed is left out here
        return engine.search(query, limit = limit * SEARCH_OVERFETCH)
            .filter { it.documentId in documentIds }
            .flatMap { document ->
                document.hits.map { hit ->
                    AgentSearchHit(document.documentId, document.title, (hit.anchor as? SearchAnchor.Page)?.pageId, hit.snippet)
                }
            }
            .take(limit)
    }

    /** The image at [path], decoded to be sure it is one; [keep]: copied among the files of the documents. */
    private suspend fun image(path: String, keep: Boolean): AgentImage? = withContext(Dispatchers.Default) {
        val bytes = readFile(path, MAX_IMAGE_BYTES) ?: return@withContext null
        val bitmap = decodeImageBitmap(bytes) ?: return@withContext null
        val hash = Sha256.of(bytes)
        val kept = if (!keep) path else {
            // The same content is kept once, as the editor does
            PencilNotesData.documents.fileWithContent(hash)?.takeIf { PlatformFiles.exists(it) }
                ?: newResourcePath("img", extensionOf(path)).takeIf { PlatformFiles.writeBytes(it, bytes) }
                ?: return@withContext null
        }
        AgentImage(kept, hash, bitmap.width, bitmap.height)
    }

    /**
     * The PDF at [path], read by PDFium to be sure it is one and to know its pages; [keep]: copied among
     * the files of the documents, as the editor imports one.
     */
    private suspend fun pdf(path: String, keep: Boolean): AgentPdf? = withContext(Dispatchers.Default) {
        val bytes = readFile(path, MAX_PDF_BYTES) ?: return@withContext null
        val hash = Sha256.of(bytes)
        val kept = PencilNotesData.documents.fileWithContent(hash)?.takeIf { PlatformFiles.exists(it) }
            ?: if (!keep) path else newResourcePath("pdf", "pdf").takeIf { PlatformFiles.writeBytes(it, bytes) } ?: return@withContext null
        val adapter = PdfiumEngine.createTileAdapter() ?: return@withContext null
        val sizes = try {
            adapter.getPageSizesMm(kept)
        } catch (_: Throwable) {
            emptyList()
        } finally {
            adapter.close()
        }
        if (sizes.isEmpty()) {
            // Not a PDF: the copy is not kept, unless a document has the same content
            if (keep && kept != path && PencilNotesData.documents.fileWithContent(hash) != kept) PlatformFiles.delete(kept)
            return@withContext null
        }
        AgentPdf(kept, hash, sizes)
    }

    private fun extensionOf(path: String): String =
        path.substringAfterLast('.', "").lowercase().takeIf { it in IMAGE_EXTENSIONS } ?: "png"

    private fun pdfText(path: String, pageIndex: Int): String? {
        val adapter = PdfiumEngine.createTileAdapter() ?: return null
        return try {
            adapter.getPageText(path, pageIndex)
        } finally {
            adapter.close()
        }
    }

    private companion object {
        const val SEARCH_OVERFETCH = 5
        const val MAX_IMAGE_BYTES = 32L * 1024 * 1024
        const val MAX_PDF_BYTES = 256L * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        const val SHOW_TIMEOUT_MS = 10_000L
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    }
}
