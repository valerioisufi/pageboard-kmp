package io.github.valerioisufi.drawview.search.ui

import io.github.valerioisufi.drawview.canvas.render.tile.PdfTileAdapter
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.search.SearchAnchor
import io.github.valerioisufi.drawview.search.SearchChunk
import io.github.valerioisufi.drawview.search.SearchChunkKind
import io.github.valerioisufi.drawview.search.SearchSource
import io.github.valerioisufi.drawview.search.splitTextChunks
import io.github.valerioisufi.drawview.store.DocumentStore
import io.github.valerioisufi.drawview.store.DocumentSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Indexes the text of the PDF pages of a document through the PDF port of the canvas
 * ([PdfTileAdapter.getPageText], Indirection). When no PDF engine is given (`pdfTiles == null`),
 * no chunk is produced.
 */
public class PdfSearchSource(
    private val store: DocumentStore,
    private val pdfTiles: (() -> PdfTileAdapter?)?
) : SearchSource {
    override val id: String = ID

    override val changes: Flow<Unit> = emptyFlow()

    override suspend fun version(document: DocumentSummary): String? {
        if (!document.hasPdf) return null
        val adapter = pdfTiles?.invoke() ?: return null
        adapter.close()
        return "${document.name}|${document.modifiedAt}|${document.pageCount}"
    }

    override suspend fun chunks(document: DocumentSummary): List<SearchChunk> {
        if (!document.hasPdf) return emptyList()
        val adapter = pdfTiles?.invoke() ?: return emptyList()
        try {
            val loaded = store.loadDocument(document.id) ?: return emptyList()
            val result = mutableListOf<SearchChunk>()
            loaded.pages.forEachIndexed { pageIndex, page ->
                val pageNumber = pageIndex + 1
                for (pdf in page.pdfs) {
                    val resource = loaded.findResource(pdf.resourceId)
                        ?.takeIf { it.type == DrawResource.ResourceType.PDF }
                        ?: continue
                    val raw = try {
                        adapter.getPageText(resource.path, pdf.pdfPageIndex)
                    } catch (_: Throwable) {
                        null
                    } ?: continue
                    val cleaned = normalizePdfText(raw)
                    if (cleaned.isEmpty()) continue
                    for (piece in splitTextChunks(cleaned)) {
                        result.add(
                            SearchChunk(
                                sourceId = ID,
                                kind = SearchChunkKind.PDF,
                                anchor = SearchAnchor.Page(
                                    documentId = document.id,
                                    pageId = page.id,
                                    bounds = null,
                                    pageNumber = pageNumber
                                ),
                                title = document.name,
                                text = piece
                            )
                        )
                    }
                }
            }
            return result
        } finally {
            adapter.close()
        }
    }

    private fun normalizePdfText(text: String): String =
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
            .map { it.trim() }
            .joinToString("\n")
            .replace(MULTIPLE_BLANK_LINES_REGEX, "\n\n")
            .trim()

    public companion object {
        public const val ID: String = "pdf"
        private val MULTIPLE_BLANK_LINES_REGEX = Regex("""\n{3,}""")
    }
}
