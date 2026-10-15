package io.github.valerioisufi.drawview.model.change

import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.PurgedContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Persistence port of the documents: implemented by the stores (see `drawview-store`); the
 * default [NoOpDocumentPersistence] keeps the documents in memory only.
 *
 * The documents, pages and elements are identified by their `id` (the same on every device): the
 * save operations insert the element or update the stored one with the same identifier. Nothing
 * is deleted: the removed pages and elements are stored as such (see [DrawPage.removed],
 * [io.github.valerioisufi.drawview.model.DrawElement.removed]). The changes of an open
 * document reach it through a [ChangePersister].
 */
public interface DocumentPersistence {
    public suspend fun saveStrokes(page: DrawPage, strokes: List<DrawStroke>) {}
    public suspend fun saveText(page: DrawPage, text: DrawText) {}
    public suspend fun saveImage(page: DrawPage, image: DrawImage) {}
    public suspend fun savePdf(page: DrawPage, pdf: DrawPdf) {}
    public suspend fun saveTextMarkup(page: DrawPage, markup: DrawTextMarkup) {}

    /** Inserts or updates [element], of a kind added by an application, with its value. */
    public suspend fun saveCustomElement(page: DrawPage, element: DrawCustomElement) {}

    /** Registers [resource], a file owned by [document]. */
    public suspend fun saveResource(document: DrawDocument, resource: DrawResource) {}

    /** A file of a resource with the content of hash [hash] (of any document), `null` when there is none. */
    public suspend fun fileWithContent(hash: String): String? = null

    /**
     * Inserts or updates [page]: its format, background, position, presence and versions (not its
     * content).
     */
    public suspend fun savePage(document: DrawDocument, page: DrawPage) {}

    public suspend fun updateDocumentDefaults(document: DrawDocument) {}

    /** Writes the title of [document] and its stamp (see `RenameDocument`). */
    public suspend fun updateDocumentTitle(document: DrawDocument) {}

    /** Deletes the pages and the elements of [document] removed for good (see [DrawDocument.purgeHidden]). */
    public suspend fun deleteForGood(document: DrawDocument, content: PurgedContent) {}
    public suspend fun touchDocument(document: DrawDocument, timestamp: Long) {}

    /** Appends [operation] to the log of the operations of [document] (see `DocumentStore`). */
    public suspend fun appendOperation(document: DrawDocument, operation: Operation) {}
}

/**
 * In-memory persistence: nothing is stored.
 */
public object NoOpDocumentPersistence : DocumentPersistence

/**
 * Serializes the persistence operations of an editing session: operations are executed in
 * submission order, so that e.g. a page is always inserted before its elements.
 */
public class PersistenceQueue(
    scope: CoroutineScope,
    private val persistence: DocumentPersistence
) {
    private val channel = Channel<suspend DocumentPersistence.() -> Unit>(Channel.UNLIMITED)

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

    public fun enqueue(operation: suspend DocumentPersistence.() -> Unit) {
        channel.trySend(operation)
    }

    /**
     * Suspends until every operation enqueued so far has been executed.
     */
    public suspend fun flush() {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        channel.trySend { done.complete(Unit) }
        done.await()
    }
}
