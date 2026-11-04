package io.github.valerioisufi.pencilnotes

import io.github.valerioisufi.drawview.agent.AgentEndpoint
import io.github.valerioisufi.drawview.agent.AgentEngines
import io.github.valerioisufi.drawview.agent.AgentSearchHit
import io.github.valerioisufi.drawview.agent.ExternalAgents
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.search.SearchAnchor
import io.github.valerioisufi.drawview.search.ui.ContentSearch
import io.github.valerioisufi.drawview.store.room.DrawViewStore
import kotlin.concurrent.Volatile

/**
 * The external models of PencilNotes, on the documents of the application: given by the application
 * of a platform that lets clients in (see `docs/agent-tools.md`), none on the others.
 */
class PencilNotesAgents {
    /** The search of the library, once made. */
    @Volatile
    internal var contentSearch: ContentSearch? = null

    val agents: ExternalAgents = ExternalAgents(
        documents = DrawViewStore.documents,
        engines = AgentEngines(
            search = ::search,
            pdfText = ::pdfText
        )
    )

    val endpoint: AgentEndpoint = AgentEndpoint(agents, serverName = "PencilNotes", serverVersion = "3.0.0")

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
