package io.github.valerioisufi.drawview.lecture

/**
 * Finds the pages of a document a text is about (e.g. the search in the notes, by meaning when its
 * model is installed): given by the application, so that a section of the review of a lecture is
 * placed next to the pages it is about. Without it, the review goes where the student chooses.
 */
public fun interface TopicFinder {

    /** The identifiers of the pages of document [documentId] about [text], the closest first (none: unknown). */
    public suspend fun pagesAbout(documentId: String, text: String): List<String>
}
