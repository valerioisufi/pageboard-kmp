package io.github.valerioisufi.pageboard.explorer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalFontFamilyResolver
import io.github.valerioisufi.pageboard.canvas.platform.decodeImageBitmap
import io.github.valerioisufi.pageboard.canvas.platform.encodePng
import io.github.valerioisufi.pageboard.canvas.render.PageboardEngines
import io.github.valerioisufi.pageboard.canvas.render.ElementRenderers
import io.github.valerioisufi.pageboard.canvas.render.ImageCache
import io.github.valerioisufi.pageboard.canvas.render.tile.PdfTileAdapter
import io.github.valerioisufi.pageboard.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.pageboard.canvas.render.tile.TileRenderer
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.PageContentSnapshot
import io.github.valerioisufi.pageboard.platform.PageboardLog
import io.github.valerioisufi.pageboard.platform.PlatformFiles
import io.github.valerioisufi.pageboard.platform.dataDirectory
import io.github.valerioisufi.pageboard.store.DocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.pageboard.ui.render.LocalPageboardEngines
import io.github.valerioisufi.pageboard.ui.theme.rememberNotesFonts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The thumbnails of the documents of the library: the first page of each, drawn small as the
 * editor draws it. The last ones shown are kept in memory, every one on disk ([directory]). When
 * the date of change of a document changes, its first page is drawn again only if it changed as
 * it is drawn (its fingerprint, see [DocumentStore.pageFingerprint]: a stroke on another page
 * changes nothing), loaded alone ([DocumentStore.loadPage]); a thumbnail shown larger is drawn
 * again too. The documents are drawn one at a time, on a thread of their own, so that a grid
 * appearing does not load all of them at once.
 *
 * @param documents the documents of the library, whose store reads their first pages.
 * @param textAdapter draws the texts of the notes (the one of the editor), or `null`.
 * @param directory where the thumbnails are kept, or `null` to keep them in memory only.
 * @param capacity the thumbnails kept in memory.
 * @param pdfTiles creates the adapter drawing the PDF pages (see `PageboardEngines.pdfTiles`).
 * @param elements draws the elements of the types of the application.
 * @param images the pixels of the images of the documents (`PageboardEngines.images`).
 */
@Stable
public class DocumentThumbnails(
    private val documents: OpenDocuments,
    private val textAdapter: TextTileAdapter?,
    private val directory: String? = defaultDirectory(),
    private val capacity: Int = 48,
    private val pdfTiles: () -> PdfTileAdapter? = { null },
    private val elements: ElementRenderers = ElementRenderers.None,
    private val images: ImageCache = ImageCache()
) {
    /** A thumbnail drawn of the first page of fingerprint [fingerprint] (`null`: not known), the document changed at [modifiedAt]. */
    private class Thumbnail(val modifiedAt: Long, val widthPx: Int, val fingerprint: String?, val image: ImageBitmap)

    /** What is kept on disk with the image of a thumbnail (see [Thumbnail]). */
    private class Key(val modifiedAt: Long, val widthPx: Int, val fingerprint: String?)

    public companion object {
        /** The directory of the thumbnails in the data directory of the application. */
        public fun defaultDirectory(): String = dataDirectory().trimEnd('/') + "/thumbnails"
    }

    private val mutex = Mutex()

    // In the order of their use, the last used at the end; used on the thread of the callers (the
    // composition), the drawing going on another
    private val memory = LinkedHashMap<String, Thumbnail>()

    /** How many thumbnails are being drawn (the screenshot tests wait for none). */
    public var rendering: Int by mutableIntStateOf(0)
        private set

    /**
     * The thumbnail of the document [documentId] as changed at [modifiedAt], at least [widthPx]
     * wide, or `null` when it has no page or cannot be read.
     */
    public suspend fun of(documentId: String, modifiedAt: Long, widthPx: Int): ImageBitmap? {
        remembered(documentId, modifiedAt, widthPx)?.let { return it }
        return mutex.withLock {
            // Drawn meanwhile for another item showing the same document
            remembered(documentId, modifiedAt, widthPx)?.let { return@withLock it }
            rendering++
            try {
                thumbnail(documentId, modifiedAt, widthPx)?.also { keep(documentId, it) }?.image
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PageboardLog.warn("Cannot draw the thumbnail of $documentId", e)
                null
            } finally {
                rendering--
            }
        }
    }

    /**
     * The page at [index] of the document [documentId] drawn [widthPx] wide, as the editor draws
     * it, or `null` past its last page or when it cannot be read. Not kept: for a preview (see
     * `TrashPreview`), which keeps the pages it shows.
     */
    public suspend fun page(documentId: String, index: Int, widthPx: Int): ImageBitmap? = mutex.withLock {
        rendering++
        try {
            val page = documents.store.loadPage(documentId, index) ?: return@withLock null
            withContext(Dispatchers.Default) { drawPage(page.content, page.resources, widthPx) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PageboardLog.warn("Cannot draw the page $index of $documentId", e)
            null
        } finally {
            rendering--
        }
    }

    private fun remembered(documentId: String, modifiedAt: Long, widthPx: Int): ImageBitmap? {
        val thumbnail = memory.remove(documentId) ?: return null
        memory[documentId] = thumbnail
        return thumbnail.image.takeIf { thumbnail.modifiedAt == modifiedAt && thumbnail.widthPx >= widthPx }
    }

    private fun keep(documentId: String, thumbnail: Thumbnail) {
        memory.remove(documentId)
        memory[documentId] = thumbnail
        while (memory.size > capacity) memory.remove(memory.keys.first())
    }

    /**
     * The thumbnail of [documentId] as changed at [modifiedAt], at least [widthPx] wide: the one
     * kept, in memory or on disk, when its first page is as it was drawn, drawn again otherwise.
     */
    private suspend fun thumbnail(documentId: String, modifiedAt: Long, widthPx: Int): Thumbnail? {
        val onDisk = readKey(documentId)
        if (onDisk != null && onDisk.modifiedAt == modifiedAt && onDisk.widthPx >= widthPx) {
            readImage(documentId)?.let { return Thumbnail(modifiedAt, onDisk.widthPx, onDisk.fingerprint, it) }
        }
        val fingerprint = documents.store.pageFingerprint(documentId, 0) ?: return null
        // Drawn of the same page: dated again
        val inMemory = memory[documentId]
        if (inMemory != null && inMemory.fingerprint == fingerprint && inMemory.widthPx >= widthPx) {
            if (onDisk?.fingerprint == fingerprint) writeKey(documentId, Key(modifiedAt, onDisk.widthPx, fingerprint))
            return Thumbnail(modifiedAt, inMemory.widthPx, fingerprint, inMemory.image)
        }
        if (onDisk != null && onDisk.fingerprint == fingerprint && onDisk.widthPx >= widthPx) {
            readImage(documentId)?.let { image ->
                writeKey(documentId, Key(modifiedAt, onDisk.widthPx, fingerprint))
                return Thumbnail(modifiedAt, onDisk.widthPx, fingerprint, image)
            }
        }
        return draw(documentId, modifiedAt, widthPx, fingerprint)
    }

    /** The paths of the image of [documentId] and of its key (see [Key]). */
    private fun paths(documentId: String): Pair<String, String>? =
        directory?.let { "$it/$documentId.png" to "$it/$documentId.key" }

    /** The key kept on disk with the image of [documentId]: `date:width:fingerprint` (`date:width` before the fingerprints). */
    private suspend fun readKey(documentId: String): Key? = withContext(Dispatchers.Default) {
        val (_, key) = paths(documentId) ?: return@withContext null
        val parts = PlatformFiles.readBytes(key)?.decodeToString()?.split(':', limit = 3) ?: return@withContext null
        val keptAt = parts.getOrNull(0)?.toLongOrNull() ?: return@withContext null
        val keptWidth = parts.getOrNull(1)?.toIntOrNull() ?: return@withContext null
        Key(keptAt, keptWidth, parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }

    private suspend fun writeKey(documentId: String, key: Key): Unit = withContext(Dispatchers.Default) {
        val (_, path) = paths(documentId) ?: return@withContext
        PlatformFiles.writeBytes(path, "${key.modifiedAt}:${key.widthPx}:${key.fingerprint.orEmpty()}".encodeToByteArray())
    }

    private suspend fun readImage(documentId: String): ImageBitmap? = withContext(Dispatchers.Default) {
        val (image, _) = paths(documentId) ?: return@withContext null
        PlatformFiles.readBytes(image)?.let { decodeImageBitmap(it, maxDimension = 4096) }
    }

    /** Draws the first page of [documentId] (of fingerprint [fingerprint]) and keeps it on disk. */
    private suspend fun draw(documentId: String, modifiedAt: Long, widthPx: Int, fingerprint: String): Thumbnail? {
        val page = documents.store.loadPage(documentId, 0) ?: return null
        return withContext(Dispatchers.Default) {
            val image = drawPage(page.content, page.resources, widthPx)
            paths(documentId)?.let { (path, _) ->
                encodePng(image)?.let { png ->
                    if (PlatformFiles.writeBytes(path, png)) writeKey(documentId, Key(modifiedAt, widthPx, fingerprint))
                }
            }
            Thumbnail(modifiedAt, widthPx, fingerprint, image)
        }
    }

    private fun drawPage(page: PageContentSnapshot, resources: List<DocumentResource>, widthPx: Int): ImageBitmap {
        // The renderer finds the files of the resources in a document
        val document = Document(addInitialPage = false)
        resources.forEach(document::addResource)
        val pdfAdapter = pdfTiles()
        return try {
            TileRenderer(pdfAdapter, textAdapter, images).apply { elements = this@DocumentThumbnails.elements }.renderThumbnail(document, page, widthPx)
        } finally {
            pdfAdapter?.close()
        }
    }
}

/**
 * The thumbnails of [documents] kept in [directory] (`null`: in memory only; e.g.
 * [DocumentThumbnails.defaultDirectory] for a store that outlives the application), their texts
 * drawn by the fonts of the notes, their PDF pages and formulas by [engines].
 */
@Composable
public fun rememberDocumentThumbnails(
    documents: OpenDocuments,
    directory: String? = null,
    engines: PageboardEngines = LocalPageboardEngines.current
): DocumentThumbnails {
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val notesFonts = rememberNotesFonts()
    return remember(documents, directory, fontFamilyResolver, notesFonts, engines) {
        val textAdapter = ComposeTextTileAdapter(fontFamilyResolver, notesFonts, engines.formulas, engines.code)
        DocumentThumbnails(documents, textAdapter, directory, pdfTiles = engines.pdfTiles, elements = engines.elements, images = engines.images)
    }
}
