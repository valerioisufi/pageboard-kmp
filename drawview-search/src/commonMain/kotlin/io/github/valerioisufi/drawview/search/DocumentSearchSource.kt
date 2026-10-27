package io.github.valerioisufi.drawview.search

import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.ElementType
import io.github.valerioisufi.drawview.model.searchTextOf
import io.github.valerioisufi.drawview.store.DocumentStore
import io.github.valerioisufi.drawview.store.DocumentSummary
import io.github.valerioisufi.drawview.store.FolderSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map

/**
 * Indexes the names of the documents with the path of their folders, their typed texts
 * (`DrawText`, Markdown marks removed and LaTeX kept) and the elements of the types added by an
 * application ([ElementType.searchText]).
 */
public class DocumentSearchSource(
    private val store: DocumentStore,
    private val elementTypes: List<ElementType<*>> = emptyList()
) : SearchSource {
    override val id: String = ID

    override val changes: Flow<Unit> = merge(
        store.documents.map {},
        store.folders.map {}
    )

    override suspend fun version(document: DocumentSummary): String {
        val folders = store.folders.first()
        val path = folderPath(document.folderId, folders)
        val vector = store.versionVector(document.id).entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}:${it.value}" }
        return "${document.name}|$path|${document.modifiedAt}|$vector"
    }

    override suspend fun chunks(document: DocumentSummary): List<SearchChunk> {
        val folders = store.folders.first()
        val path = folderPath(document.folderId, folders)
        val titleText = if (path.isEmpty()) document.name else "$path / ${document.name}"
        val result = mutableListOf(
            SearchChunk(
                sourceId = ID,
                kind = SearchChunkKind.TITLE,
                anchor = SearchAnchor.Document(document.id),
                title = document.name,
                text = titleText
            )
        )
        val loaded = store.loadDocument(document.id) ?: return result
        loaded.pages.forEachIndexed { pageIndex, page ->
            val pageNumber = pageIndex + 1
            for (textElement in page.texts.sortedWith(compareBy({ it.y }, { it.x }))) {
                val cleaned = cleanMarkdownForSearch(textElement.text)
                if (cleaned.isEmpty()) continue
                val bounds = textElement.getRotatedBounds()
                for (piece in splitTextChunks(cleaned)) {
                    result.add(
                        SearchChunk(
                            sourceId = ID,
                            kind = SearchChunkKind.TEXT,
                            anchor = SearchAnchor.Page(document.id, page.id, bounds, pageNumber),
                            title = document.name,
                            text = piece
                        )
                    )
                }
            }
            for (custom in page.customElements.sortedWith(compareBy({ it.y }, { it.x }))) {
                val raw = customSearchText(custom) ?: continue
                val cleaned = cleanMarkdownForSearch(raw)
                if (cleaned.isEmpty()) continue
                val bounds = custom.getRotatedBounds()
                for (piece in splitTextChunks(cleaned)) {
                    result.add(
                        SearchChunk(
                            sourceId = ID,
                            kind = SearchChunkKind.TEXT,
                            anchor = SearchAnchor.Page(document.id, page.id, bounds, pageNumber),
                            title = document.name,
                            text = piece
                        )
                    )
                }
            }
        }
        return result
    }

    private fun customSearchText(element: DrawCustomElement): String? {
        val type = elementTypes.firstOrNull { it.id == element.type } ?: return null
        return type.searchTextOf(element)
    }

    private fun folderPath(folderId: String?, folders: List<FolderSummary>): String {
        if (folderId == null) return ""
        val byId = folders.associateBy { it.id }
        val names = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var current = folderId
        while (current != null && visited.add(current)) {
            val folder = byId[current] ?: break
            names.add(folder.name)
            current = folder.parentId
        }
        return names.asReversed().joinToString(" / ")
    }

    public companion object {
        public const val ID: String = "document"
    }
}
