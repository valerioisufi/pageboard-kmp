package io.github.valerioisufi.drawview.core.controller

import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawPdf
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup
import io.github.valerioisufi.drawview.core.model.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Persistence port of the editor. Implemented by `:drawview-data` on top of Room; the default
 * [NoOpDrawPersistence] keeps documents in memory only.
 *
 * The documents, pages and elements are identified by their `id` (the same on every device): the
 * save operations insert the element or update the stored one with the same identifier.
 */
interface DrawPersistence {
    suspend fun saveNewStrokes(page: DrawPage, strokes: List<DrawStroke>) {}
    suspend fun updateStrokes(page: DrawPage, strokes: List<DrawStroke>) {}
    suspend fun deleteStrokes(strokes: List<DrawStroke>) {}

    suspend fun saveText(page: DrawPage, text: DrawText) {}
    suspend fun deleteText(text: DrawText) {}

    suspend fun saveImage(page: DrawPage, image: DrawImage) {}
    suspend fun deleteImage(image: DrawImage) {}

    suspend fun savePdf(page: DrawPage, pdf: DrawPdf) {}

    suspend fun saveTextMarkup(page: DrawPage, markup: DrawTextMarkup) {}
    suspend fun deleteTextMarkup(markup: DrawTextMarkup) {}

    /** Registers a file owned by [document] and returns the identifier of the resource. */
    suspend fun addResource(document: DrawDocument, type: DrawResource.ResourceType, path: String): String =
        path

    /**
     * Inserts [page] at [index], or puts it back there after [softDeletePage], shifting the
     * following pages.
     */
    suspend fun insertPage(document: DrawDocument, page: DrawPage, index: Int) {}
    suspend fun softDeletePage(document: DrawDocument, page: DrawPage, index: Int) {}
    suspend fun updatePagesOrder(document: DrawDocument, pages: List<DrawPage>) {}
    suspend fun updatePageFormat(document: DrawDocument, page: DrawPage) {}

    suspend fun updateDocumentDefaults(document: DrawDocument) {}
    suspend fun touchDocument(document: DrawDocument, timestamp: Long) {}

    // --- Global user preferences ---
    suspend fun savePresets(tool: Tool, presets: List<ToolPreset>) {}
    suspend fun saveStylusOnlyMode(enabled: Boolean) {}
    suspend fun saveLastSelectedTool(tool: Tool) {}
    suspend fun saveTextDefaults(fontSize: Float, color: Int) {}
}

/**
 * In-memory persistence: nothing is stored.
 */
object NoOpDrawPersistence : DrawPersistence

/**
 * Serializes the persistence operations of an editing session: operations are executed in
 * submission order, so that e.g. a stroke is always inserted before it can be deleted by the
 * eraser.
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
