package io.github.valerioisufi.drawview.agent.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.agent.PageEngine
import io.github.valerioisufi.drawview.canvas.platform.encodePng
import io.github.valerioisufi.drawview.canvas.render.ElementRenderers
import io.github.valerioisufi.drawview.canvas.render.tile.PdfTileAdapter
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.canvas.render.tile.TileRenderer
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PageContentSnapshot
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The pages of a document in no editor, laid out and drawn as the canvas does it (see [PageEngine]):
 * the texts by the adapter of the editors, the PDF pages by the engine of the application.
 *
 * @param textAdapter lays out and draws the texts (as the one of the editor), or `null`: the heights
 *   are not told, and the texts are left out of a page drawn.
 * @param pdfTiles creates the adapter reading and drawing the PDF pages (see `DrawViewEngines.pdfTiles`).
 * @param elements draws the elements of the types of the application.
 */
public class CanvasPageEngine(
    private val textAdapter: TextTileAdapter?,
    private val pdfTiles: () -> PdfTileAdapter? = { null },
    private val elements: ElementRenderers = ElementRenderers.None
) : PageEngine {
    /** The text of the last PDF pages asked for, by file and page: a PDF does not change. Used on the thread of the documents. */
    private val layouts = LinkedHashMap<String, PdfTextLayout?>()

    /** One page drawn at a time: a picture of a page is large. */
    private val drawing = Mutex()

    override fun textHeight(markdown: String, widthMm: Float, fontSize: Float): Float? {
        val text = DrawText().apply {
            this.text = markdown
            width = widthMm
            this.fontSize = fontSize
            markDirty()
        }
        return textAdapter?.measure(text)?.height
    }

    override fun pdfLayout(path: String, pageIndex: Int): PdfTextLayout? {
        val key = "$path#$pageIndex"
        if (layouts.containsKey(key)) return layouts.remove(key).also { layouts[key] = it }
        val adapter = pdfTiles() ?: return null
        val layout = try {
            adapter.getTextLayout(path, pageIndex)
        } catch (_: Throwable) {
            null
        } finally {
            adapter.close()
        }
        layouts[key] = layout
        while (layouts.size > MAX_CACHED_LAYOUTS) layouts.remove(layouts.keys.first())
        return layout
    }

    override suspend fun renderPage(resources: List<DrawResource>, page: PageContentSnapshot, maxSidePx: Int): ByteArray? = drawing.withLock {
        withContext(Dispatchers.Default) {
            val scale = maxSidePx / maxOf(page.widthMm, page.heightMm)
            val width = ceil(page.widthMm * scale).toInt().coerceAtLeast(1)
            val height = ceil(page.heightMm * scale).toInt().coerceAtLeast(1)
            val bitmap = ImageBitmap(width, height)
            // The renderer finds the files of the resources in a document
            val document = DrawDocument(addInitialPage = false).apply { resources.forEach(::addResource) }
            val pdfAdapter = pdfTiles()
            try {
                TileRenderer(pdfAdapter, textAdapter).apply { elements = this@CanvasPageEngine.elements }.renderPageContent(
                    Canvas(bitmap), document, page, Matrix2D().postScale(scale, scale), Rect(0f, 0f, width.toFloat(), height.toFloat())
                )
            } finally {
                pdfAdapter?.close()
            }
            encodePng(bitmap)
        }
    }

    private companion object {
        const val MAX_CACHED_LAYOUTS = 64
    }
}
