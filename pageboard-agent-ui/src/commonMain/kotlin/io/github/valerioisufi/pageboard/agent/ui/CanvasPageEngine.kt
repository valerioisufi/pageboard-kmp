package io.github.valerioisufi.pageboard.agent.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.pageboard.agent.PageEngine
import io.github.valerioisufi.pageboard.agent.PageMark
import io.github.valerioisufi.pageboard.canvas.platform.encodePng
import io.github.valerioisufi.pageboard.canvas.render.ElementRenderers
import io.github.valerioisufi.pageboard.canvas.render.ImageCache
import io.github.valerioisufi.pageboard.canvas.render.tile.PdfTileAdapter
import io.github.valerioisufi.pageboard.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.pageboard.canvas.render.tile.TileRenderer
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.PageContentSnapshot
import io.github.valerioisufi.pageboard.model.PdfInk
import io.github.valerioisufi.pageboard.model.PdfTextLayout
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Rect as ModelRect
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
 * @param pdfTiles creates the adapter reading and drawing the PDF pages (see `PageboardEngines.pdfTiles`).
 * @param elements draws the elements of the types of the application.
 * @param images the pixels of the images of the documents (`PageboardEngines.images`).
 */
public class CanvasPageEngine(
    private val textAdapter: TextTileAdapter?,
    private val pdfTiles: () -> PdfTileAdapter? = { null },
    private val elements: ElementRenderers = ElementRenderers.None,
    private val images: ImageCache = ImageCache()
) : PageEngine {
    /** The text of the last PDF pages asked for, by file and page: a PDF does not change. Used on the thread of the documents. */
    private val layouts = LinkedHashMap<String, PdfTextLayout?>()

    /** What the last PDF pages asked for draw, by file and page. Used on the thread of the documents. */
    private val inks = LinkedHashMap<String, PdfInk?>()

    /** One page drawn at a time: a picture of a page is large. */
    private val drawing = Mutex()

    override fun textHeight(markdown: String, widthMm: Float, fontSize: Float): Float? {
        val text = TextElement().apply {
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

    override suspend fun renderPage(
        resources: List<DocumentResource>,
        page: PageContentSnapshot,
        maxSidePx: Int,
        area: ModelRect?,
        marks: List<PageMark>
    ): ByteArray? = drawing.withLock {
        withContext(Dispatchers.Default) {
            val shown = area ?: ModelRect(0f, 0f, page.widthMm, page.heightMm)
            val scale = maxSidePx / maxOf(shown.width, shown.height)
            val width = ceil(shown.width * scale).toInt().coerceAtLeast(1)
            val height = ceil(shown.height * scale).toInt().coerceAtLeast(1)
            val bitmap = ImageBitmap(width, height)
            val canvas = Canvas(bitmap)
            val toCanvas = Matrix2D().postTranslate(-shown.left, -shown.top).postScale(scale, scale)
            // The renderer finds the files of the resources in a document
            val document = Document(addInitialPage = false).apply { resources.forEach(::addResource) }
            val pdfAdapter = pdfTiles()
            try {
                TileRenderer(pdfAdapter, textAdapter, images).apply { elements = this@CanvasPageEngine.elements }
                    .renderPageContent(canvas, document, page, toCanvas, Rect(0f, 0f, width.toFloat(), height.toFloat()))
            } finally {
                pdfAdapter?.close()
            }
            for (mark in marks) drawMark(canvas, mark, shown, scale, toCanvas)
            encodePng(bitmap)
        }
    }

    /** The outline of [mark] and its label inside its top-left corner, over a page drawn at [scale] pixels a millimeter. */
    private fun drawMark(canvas: Canvas, mark: PageMark, shown: ModelRect, scale: Float, toCanvas: Matrix2D) {
        val box = mark.box
        val outline = Paint().apply {
            color = Color(mark.color)
            style = PaintingStyle.Stroke
            strokeWidth = (MARK_WIDTH_MM * scale).coerceAtLeast(1.5f)
        }
        canvas.drawRect(Rect((box.left - shown.left) * scale, (box.top - shown.top) * scale, (box.right - shown.left) * scale, (box.bottom - shown.top) * scale), outline)
        val label = TextElement().apply {
            text = "**${mark.label}**"
            x = box.left + MARK_LABEL_INSET_MM
            y = box.top + MARK_LABEL_INSET_MM
            width = MARK_LABEL_WIDTH_MM
            fontSize = MARK_LABEL_SIZE
            color = mark.color
            background = LABEL_PAPER
            markDirty()
        }
        textAdapter?.drawText(canvas, label, toCanvas, LABEL_PAPER)
    }

    private companion object {
        const val MAX_CACHED_LAYOUTS = 64
        const val MARK_WIDTH_MM = 0.35f
        const val MARK_LABEL_INSET_MM = 0.6f
        const val MARK_LABEL_WIDTH_MM = 12f
        const val MARK_LABEL_SIZE = 8f
        const val LABEL_PAPER = 0xFFFFFFFF.toInt()

        /** The cell of the free space of a page (see `FreeSpace`). */
        const val INK_CELL_MM = 2f
        const val INK_CELL_PX = 3
        const val MAX_INK_CELLS = 600
        const val PAPER_MASK = 0xF0F0F0

        /** The most the three channels of a pixel of the paper differ from it, summed. */
        const val PAPER_TOLERANCE = 72
    }
}
