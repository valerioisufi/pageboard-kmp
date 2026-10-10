package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import io.github.valerioisufi.drawview.core.render.tile.PdfTileAdapter
import io.github.valerioisufi.drawview.core.render.tile.fitPdfPage
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF backend: PDFium on the desktop JVM, Android and iOS (see [PdfiumBindings]).
 */
object PdfPlatform {
    fun openDocument(filePath: String, password: String? = null): PdfDocument {
        if (!PdfiumBindings.load()) {
            throw UnsupportedOperationException("PDF documents are not supported on this platform")
        }
        val handle = PdfiumBindings.openDocument(filePath, password)
        require(handle != 0L) { "Failed to open PDF document at: $filePath" }
        return PdfiumDocument(handle)
    }

    fun isAvailable(): Boolean = PdfiumBindings.load()
}

/**
 * Entry point for opening PDF documents.
 */
object PdfiumEngine {

    /**
     * Opens a PDF file from a local filesystem path.
     *
     * @param filePath Absolute or relative path to the PDF file.
     * @param password Optional password for encrypted documents.
     */
    fun open(filePath: String, password: String? = null): PdfDocument {
        return PdfPlatform.openDocument(filePath, password)
    }

    /**
     * Checks if a PDF backend is available on the current platform.
     */
    fun isAvailable(): Boolean = PdfPlatform.isAvailable()

    /**
     * Starts a new PDF document, written page by page and then saved (see [PdfWriter]).
     */
    fun create(): PdfWriter {
        if (!isAvailable()) throw UnsupportedOperationException("PDF documents are not supported on this platform")
        val handle = PdfiumBindings.newDocument()
        check(handle != 0L) { "Cannot create a PDF document" }
        return PdfiumWriter(handle)
    }

    /**
     * Creates the adapter used by the tile engine to render the PDF pages of the documents, or
     * `null` when PDFs are not supported on this platform.
     */
    fun createTileAdapter(): PdfTileAdapter? = if (isAvailable()) CachingPdfTileAdapter() else null
}

/**
 * [PdfTileAdapter] keeping the most recently used documents open. Thread-safe.
 */
internal class CachingPdfTileAdapter(private val maxOpenDocuments: Int = 4) : PdfTileAdapter {
    private val lock = SyncLock()
    private val documents = LinkedHashMap<String, PdfDocument>()
    private val failed = mutableSetOf<String>()
    private val paint = Paint()

    /** Must be called while holding [lock]. */
    private fun documentLocked(path: String): PdfDocument? {
        documents.remove(path)?.let {
            documents[path] = it
            return it
        }
        if (path in failed) return null
        val doc = try {
            PdfPlatform.openDocument(path)
        } catch (_: Throwable) {
            failed.add(path)
            return null
        }
        documents[path] = doc
        while (documents.size > maxOpenDocuments) {
            val eldest = documents.keys.first()
            documents.remove(eldest)?.close()
        }
        return doc
    }

    override fun renderPdfPage(canvas: Canvas, resourcePath: String, pdfPageIndex: Int, pageRect: Rect, clipRect: Rect) {
        // Rendering is serialized: the native backends are not reentrant
        syncLock(lock) {
            val doc = documentLocked(resourcePath) ?: return
            if (pdfPageIndex !in 0 until doc.pageCount) return
            val size = doc.getPageSize(pdfPageIndex)
            val target = fitPdfPage(size.widthPt, size.heightPt, pageRect)

            // Pixel aligned visible region
            val left = max(floor(target.left), floor(clipRect.left))
            val top = max(floor(target.top), floor(clipRect.top))
            val right = min(ceil(target.right), ceil(clipRect.right))
            val bottom = min(ceil(target.bottom), ceil(clipRect.bottom))
            val w = (right - left).roundToInt()
            val h = (bottom - top).roundToInt()
            if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return

            val bitmap = doc.renderPageSlice(
                pageIndex = pdfPageIndex,
                startX = (target.left - left).roundToInt(),
                startY = (target.top - top).roundToInt(),
                sizeX = target.width.roundToInt().coerceAtLeast(1),
                sizeY = target.height.roundToInt().coerceAtLeast(1),
                widthPx = w,
                heightPx = h
            ) ?: return
            canvas.drawImage(bitmap, Offset(left, top), paint)
        }
    }

    override fun getPageSizesMm(resourcePath: String): List<Pair<Float, Float>> = syncLock(lock) {
        val doc = documentLocked(resourcePath) ?: return@syncLock emptyList()
        (0 until doc.pageCount).map { doc.getPageSize(it).let { s -> s.widthMm to s.heightMm } }
    }

    override fun getPageText(resourcePath: String, pdfPageIndex: Int): String? = syncLock(lock) {
        val doc = documentLocked(resourcePath) ?: return@syncLock null
        if (pdfPageIndex !in 0 until doc.pageCount) null else doc.getPageText(pdfPageIndex)
    }

    override fun getTextLayout(resourcePath: String, pdfPageIndex: Int): PdfTextLayout? = syncLock(lock) {
        val doc = documentLocked(resourcePath) ?: return@syncLock null
        if (pdfPageIndex !in 0 until doc.pageCount) null else doc.getPageTextLayout(pdfPageIndex)
    }

    override fun close() {
        syncLock(lock) {
            documents.values.forEach { it.close() }
            documents.clear()
        }
    }
}
