package io.github.valerioisufi.drawview.explorer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A matching passage of a document shown under its title when searching the library.
 *
 * @param snippet short excerpt of the matching content.
 * @param location where in the document the match lives (e.g. `"p. 3"` or `"12:40"`).
 * @param icon optional icon indicating the kind of content (text, PDF, handwriting, lecture).
 * @param onOpen opens the match at its exact place when clicked; when `null`, opens the document.
 */
public data class LibrarySearchMatch(
    public val snippet: String,
    public val location: String = "",
    public val icon: ImageVector? = null,
    public val onOpen: ((openDocument: (String) -> Unit) -> Unit)? = null
)

/**
 * Port of the document library for searching the contents of the notes (`drawview-search-ui`
 * implements it on top of `drawview-search`; Indirection, Low Coupling).
 */
public interface LibrarySearch {
    /**
     * IDs of the documents matching the current query, in rank order, or `null` when no content
     * search is active (the library then matches document names only).
     */
    public val matchedDocumentIds: List<String>?

    /** Updates the query being searched in the library. */
    public fun onQueryChange(query: String)

    /** Matching passages of [documentId] for the current query. */
    public fun matchesOf(documentId: String): List<LibrarySearchMatch> = emptyList()

    /**
     * Optional composable shown above the search results when a query is entered (e.g. "Ask your
     * notes" and indexing status).
     */
    public val headerContent: (@Composable (query: String, openDocument: (String) -> Unit) -> Unit)?
        get() = null
}
