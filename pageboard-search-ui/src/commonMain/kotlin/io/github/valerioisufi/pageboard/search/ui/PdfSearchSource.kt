package io.github.valerioisufi.pageboard.search.ui

import io.github.valerioisufi.pageboard.canvas.render.tile.PdfTileAdapter
import io.github.valerioisufi.pageboard.model.DrawResource
import io.github.valerioisufi.pageboard.platform.Sha256
import io.github.valerioisufi.pageboard.search.SearchAnchor
import io.github.valerioisufi.pageboard.search.SearchChunk
import io.github.valerioisufi.pageboard.search.SearchChunkKind
import io.github.valerioisufi.pageboard.search.SearchSource
import io.github.valerioisufi.pageboard.search.splitTextChunks
import io.github.valerioisufi.pageboard.store.DocumentStore
import io.github.valerioisufi.pageboard.store.DocumentSummary
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

    /**
     * The name of the document and its pages in their order with their PDF pages (file and index):
     * a stroke does not change it, a PDF page added, removed or moved does.
     */
    override suspend fun version(document: DocumentSummary): String? {
        if (!document.hasPdf) return null
        val adapter = pdfTiles?.invoke() ?: return null
        adapter.close()
        val outline = store.loadOutline(document.id) ?: return null
        val pages = outline.pages.joinToString(";") { page ->
            page.id + page.pdfs.joinToString("") { pdf ->
                ",${pdf.resourceId}:${outline.findResource(pdf.resourceId)?.hash.orEmpty()}:${pdf.pdfPageIndex}"
            }
        }
        return "${document.name}|${Sha256.of(pages.encodeToByteArray())}"
    }

    override suspend fun chunks(document: DocumentSummary): List<SearchChunk> {
        if (!document.hasPdf) return emptyList()
        val adapter = pdfTiles?.invoke() ?: return emptyList()
        try {
            val outline = store.loadOutline(document.id) ?: return emptyList()
            val result = mutableListOf<SearchChunk>()
            outline.pages.forEachIndexed { pageIndex, page ->
                val pageNumber = pageIndex + 1
                for (pdf in page.pdfs) {
                    val resource = outline.findResource(pdf.resourceId)
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
