package io.github.valerioisufi.pageboard.ui.extension

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.valerioisufi.pageboard.ui.components.BadgeTone

/**
 * What is said of some documents of the library: a badge on them and a filter of the library.
 * The library has its own (the PDFs, the shared documents); an application adds the ones its
 * modules know (e.g. the documents with a lecture), so that the library never knows what they
 * mean.
 *
 * @param id a name unique among the tags of the library.
 * @param label the badge of a document (e.g. "Lecture").
 * @param filterLabel the filter of the library (e.g. "Lectures").
 * @param icon the icon of the badge.
 * @param tone the color of the badge.
 * @param documents the identifiers of the documents tagged, read in composition (a state of the
 *   module knowing them).
 */
@Stable
public class DocumentTag(
    public val id: String,
    public val label: @Composable () -> String,
    public val filterLabel: @Composable () -> String,
    public val icon: ImageVector,
    public val tone: BadgeTone,
    public val documents: () -> Set<String>
) {
    /** Whether the document [documentId] has this tag. */
    public fun contains(documentId: String): Boolean = documentId in documents()
}
