package io.github.valerioisufi.drawview.core.controller

import io.github.valerioisufi.drawview.core.change.Operation
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawPdf
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup
import io.github.valerioisufi.drawview.core.model.PurgedContent
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.core.text.TextDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Persistence port of the editor. Implemented by `:drawview-data` on top of Room; the default
 * [NoOpDrawPersistence] keeps documents in memory only.
 *
 * The documents, pages and elements are identified by their `id` (the same on every device): the
 * save operations insert the element or update the stored one with the same identifier. Nothing
 * is deleted: the removed pages and elements are stored as such (see [DrawPage.removed],
 * [io.github.valerioisufi.drawview.core.model.DrawElement.removed]). The changes of an open
 * document reach it through a [ChangePersister].
 */
interface DrawPersistence {
    suspend fun saveStrokes(page: DrawPage, strokes: List<DrawStroke>) {}
    suspend fun saveText(page: DrawPage, text: DrawText) {}
    suspend fun saveImage(page: DrawPage, image: DrawImage) {}
    suspend fun savePdf(page: DrawPage, pdf: DrawPdf) {}
    suspend fun saveTextMarkup(page: DrawPage, markup: DrawTextMarkup) {}

    /** Registers [resource], a file owned by [document]. */
    suspend fun saveResource(document: DrawDocument, resource: DrawResource) {}

    /** A file of a resource with the content of hash [hash] (of any document), `null` when there is none. */
    suspend fun fileWithContent(hash: String): String? = null

    /**
     * Inserts or updates [page]: its format, background, position, presence and versions (not its
     * content).
     */
    suspend fun savePage(document: DrawDocument, page: DrawPage) {}

    suspend fun updateDocumentDefaults(document: DrawDocument) {}

    /** Writes the title of [document] and its stamp (see `RenameDocument`). */
    suspend fun updateDocumentTitle(document: DrawDocument) {}

    /** Deletes the pages and the elements of [document] removed for good (see [DrawDocument.purgeHidden]). */
    suspend fun deleteForGood(document: DrawDocument, content: PurgedContent) {}
    suspend fun touchDocument(document: DrawDocument, timestamp: Long) {}

    /** Appends [operation] to the log of the operations of [document] (see `DocumentStore`). */
    suspend fun appendOperation(document: DrawDocument, operation: Operation) {}

    // --- Global user preferences ---
    suspend fun savePresets(tool: Tool, presets: List<ToolPreset>) {}
    suspend fun saveStylusOnlyMode(enabled: Boolean) {}
    suspend fun saveLastSelectedTool(tool: Tool) {}
    suspend fun saveTextDefaults(defaults: TextDefaults) {}
    suspend fun saveSnapEnabled(enabled: Boolean) {}
}

/**
 * In-memory persistence: nothing is stored.
 */
object NoOpDrawPersistence : DrawPersistence

/**
 * Serializes the persistence operations of an editing session: operations are executed in
 * submission order, so that e.g. a page is always inserted before its elements.
 */
class PersistenceQueue(
    scope: CoroutineScope,
    private val persistence: DrawPersistence
) {
    private val channel = Channel<suspend DrawPersistence.() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (operation in channel) {
                try {
                    persistence.operation()
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    println("DrawView: persistence operation failed: ${e.message}")
                }
            }
        }
    }

    fun enqueue(operation: suspend DrawPersistence.() -> Unit) {
        channel.trySend(operation)
    }

    /**
     * Suspends until every operation enqueued so far has been executed.
     */
    suspend fun flush() {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        channel.trySend { done.complete(Unit) }
        done.await()
    }
}
