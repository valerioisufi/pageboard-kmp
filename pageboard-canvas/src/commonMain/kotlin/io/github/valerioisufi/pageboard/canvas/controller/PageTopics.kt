package io.github.valerioisufi.pageboard.canvas.controller

/**
 * Finds the pages of a document a text is about (e.g. the search in the notes, by meaning when its
 * model is installed): given by the application with the engines
 * ([io.github.valerioisufi.pageboard.canvas.render.DrawViewEngines.topics]), so that a text placed in
 * the notebook goes next to the pages it is about (see [TextPlacer]). Without it, a text goes where
 * the student chooses.
 */
public fun interface PageTopics {

    /** The identifiers of the pages of document [documentId] about [text], the closest first (none: unknown). */
    public suspend fun pagesAbout(documentId: String, text: String): List<String>
}
