package io.github.valerioisufi.drawview.agent.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
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
import io.github.valerioisufi.drawview.model.PdfInk
import io.github.valerioisufi.drawview.model.PdfTextLayout
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.math.abs
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

    /** What the last PDF pages asked for draw, by file and page. Used on the thread of the documents. */
    private val inks = LinkedHashMap<String, PdfInk?>()

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

    override fun pdfInk(path: String, pageIndex: Int): PdfInk? {
        val key = "$path#$pageIndex"
        if (inks.containsKey(key)) return inks.remove(key).also { inks[key] = it }
        val adapter = pdfTiles() ?: return null
        val ink = try {
            adapter.getPageSizesMm(path).getOrNull(pageIndex)?.let { (widthMm, heightMm) -> inkOf(adapter, path, pageIndex, widthMm, heightMm) }
        } catch (_: Throwable) {
            null
        } finally {
            adapter.close()
        }
        inks[key] = ink
        while (inks.size > MAX_CACHED_LAYOUTS) inks.remove(inks.keys.first())
        return ink
    }

    /**
     * The page drawn small on white, [INK_CELL_PX] pixels a cell of [INK_CELL_MM]: a cell is taken when
     * one of its pixels is not the color of most of the page, its paper.
     */
    private fun inkOf(adapter: PdfTileAdapter, path: String, pageIndex: Int, widthMm: Float, heightMm: Float): PdfInk? {
        if (!(widthMm > 0f) || !(heightMm > 0f)) return null
        val columns = ceil(widthMm / INK_CELL_MM).toInt().coerceIn(1, MAX_INK_CELLS)
        val rows = ceil(heightMm / INK_CELL_MM).toInt().coerceIn(1, MAX_INK_CELLS)
        val width = columns * INK_CELL_PX
        val height = rows * INK_CELL_PX
        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        val whole = Rect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRect(whole, Paint().apply { color = Color.White })
        adapter.renderPdfPage(canvas, path, pageIndex, whole, whole)
        val pixels = IntArray(width * height)
        bitmap.readPixels(pixels, width = width, height = height)
        // The paper: the color of most pixels, told apart to a sixteenth of each channel
        val counts = HashMap<Int, Int>()
        for (pixel in pixels) counts[pixel and PAPER_MASK] = (counts[pixel and PAPER_MASK] ?: 0) + 1
        val paper = counts.maxByOrNull { it.value }?.key ?: return null
        val taken = BooleanArray(columns * rows)
        for (y in 0 until height) {
            val row = y / INK_CELL_PX
            for (x in 0 until width) {
                if (!isPaper(pixels[y * width + x], paper)) taken[row * columns + x / INK_CELL_PX] = true
            }
        }
        return PdfInk(widthMm, heightMm, columns, rows, taken)
    }

    /** Whether [pixel] is as [paper], to what the edges of a drawing leave on it. */
    private fun isPaper(pixel: Int, paper: Int): Boolean {
        val red = abs(((pixel shr 16) and 0xFF) - ((paper shr 16) and 0xFF))
        val green = abs(((pixel shr 8) and 0xFF) - ((paper shr 8) and 0xFF))
        val blue = abs((pixel and 0xFF) - (paper and 0xFF))
        return red + green + blue <= PAPER_TOLERANCE
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

        /** The cell of the free space of a page (see `FreeSpace`). */
        const val INK_CELL_MM = 2f
        const val INK_CELL_PX = 3
        const val MAX_INK_CELLS = 600
        const val PAPER_MASK = 0xF0F0F0

        /** The most the three channels of a pixel of the paper differ from it, summed. */
        const val PAPER_TOLERANCE = 72
    }
}
