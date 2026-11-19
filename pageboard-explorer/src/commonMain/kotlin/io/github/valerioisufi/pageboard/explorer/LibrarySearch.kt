package io.github.valerioisufi.pageboard.explorer

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController

/**
 * Opens a document in the editor and runs [then] on its [DrawViewController] once loaded
 * (e.g. to center the camera on a page and its area).
 */
public fun interface DocumentOpener {
    /** Opens [documentId] and invokes [then] with its controller once loaded. */
    public fun open(documentId: String, then: (DrawViewController) -> Unit)

    /** Opens [documentId] at its default view. */
    public fun open(documentId: String) {
        open(documentId) {}
    }
}

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
    public val onOpen: ((openDocument: DocumentOpener) -> Unit)? = null
)

/**
 * Port of the document library for searching the contents of the notes (`pageboard-search-ui`
 * implements it on top of `pageboard-search`; Indirection, Low Coupling).
 */
public interface LibrarySearch {
    /**
     * IDs of the documents matching the current query, in rank order, or `null` when no content
     * search is active (the library then matches document names only).
     */
    public val matchedDocumentIds: List<String>?

    /** Updates the query being searched in the library. */
    public fun onQueryChange(query: String)

    /**
     * Updates where the query is searched: the documents the library looks at, `null` for all of
     * them. Those found elsewhere are left out by the library in any case.
     */
    public fun onScopeChange(documentIds: Set<String>?) {}

    /** Matching passages of [documentId] for the current query. */
    public fun matchesOf(documentId: String): List<LibrarySearchMatch> = emptyList()

    /**
     * Optional composable shown above the search results when a query is entered (e.g. "Ask your
     * notes" and indexing status).
     */
    public val headerContent: (@Composable (query: String, openDocument: DocumentOpener) -> Unit)?
        get() = null
}
