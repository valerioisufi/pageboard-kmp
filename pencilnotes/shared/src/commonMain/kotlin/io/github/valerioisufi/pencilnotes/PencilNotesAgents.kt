package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.drawview.agent.AgentEndpoint
import io.github.valerioisufi.drawview.agent.AgentEngines
import io.github.valerioisufi.drawview.agent.AgentSearchHit
import io.github.valerioisufi.drawview.agent.ExternalAgents
import io.github.valerioisufi.drawview.agent.ui.AgentReviewUi
import io.github.valerioisufi.drawview.agent.ui.SessionAgentEditors
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.search.SearchAnchor
import io.github.valerioisufi.drawview.search.ui.ContentSearch
import io.github.valerioisufi.drawview.store.room.DrawViewStore
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import kotlin.concurrent.Volatile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The external models of PencilNotes, on the documents of the application: given by the application
 * of a platform that lets clients in (see `docs/agent-tools.md`), none on the others.
 */
class PencilNotesAgents {
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
            renderPage = editors::renderPage
        )
    )

    val endpoint: AgentEndpoint = AgentEndpoint(agents, serverName = "PencilNotes", serverVersion = "3.0.0")

    /** The review in the editors. */
    internal val editorExtension: EditorExtension = AgentReviewUi(agents).editorExtension

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
    }
}
