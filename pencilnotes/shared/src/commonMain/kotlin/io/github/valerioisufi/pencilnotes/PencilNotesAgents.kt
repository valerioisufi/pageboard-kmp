package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.drawview.agent.AgentEndpoint
import io.github.valerioisufi.drawview.agent.AgentEngines
import io.github.valerioisufi.drawview.agent.AgentImage
import io.github.valerioisufi.drawview.agent.AgentImages
import io.github.valerioisufi.drawview.agent.AgentJournal
import io.github.valerioisufi.drawview.agent.AgentSearchHit
import io.github.valerioisufi.drawview.agent.ExternalAgents
import io.github.valerioisufi.drawview.agent.ui.AgentReviewUi
import io.github.valerioisufi.drawview.agent.ui.SessionAgentEditors
import io.github.valerioisufi.drawview.canvas.platform.decodeImageBitmap
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.platform.PlatformFiles
import io.github.valerioisufi.drawview.platform.Sha256
import io.github.valerioisufi.drawview.platform.newResourcePath
import io.github.valerioisufi.drawview.search.SearchAnchor
import io.github.valerioisufi.drawview.search.ui.ContentSearch
import io.github.valerioisufi.drawview.store.room.DrawViewStore
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
 * of a platform that lets clients in (see `docs/agent-tools.md`), none on the others.
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

    private val reviews = Channel<String>(Channel.CONFLATED)

    /** The documents to open for a review of their proposals: the application opens them in an editor. */
    val reviewRequests: Flow<String> = reviews.receiveAsFlow()

    private val editors = SessionAgentEditors(openDocument = { reviews.trySend(it) })

    val agents: ExternalAgents = ExternalAgents(
        documents = DrawViewStore.documents,
        engines = AgentEngines(
            editors = editors,
            search = ::search,
            pdfText = ::pdfText,
            renderPage = editors::renderPage,
            images = object : AgentImages {
                override suspend fun read(path: String): AgentImage? = image(path, keep = false)

                override suspend fun keep(path: String): AgentImage? = image(path, keep = true)
            }
        ),
        journal = journal
    )

    val endpoint: AgentEndpoint = AgentEndpoint(agents, serverName = "PencilNotes", serverVersion = "3.0.0")

    private val ui = AgentReviewUi(agents)

    /** The review in the editors. */
    internal val editorExtension: EditorExtension = ui.editorExtension

    /** The access, from the library. */
    internal val libraryAction: LibraryAction = ui.libraryAction

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
            DrawViewStore.documents.fileWithContent(hash)?.takeIf { PlatformFiles.exists(it) }
                ?: newResourcePath("img", extensionOf(path)).takeIf { PlatformFiles.writeBytes(it, bytes) }
                ?: return@withContext null
        }
        AgentImage(kept, hash, bitmap.width, bitmap.height)
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
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    }
}
