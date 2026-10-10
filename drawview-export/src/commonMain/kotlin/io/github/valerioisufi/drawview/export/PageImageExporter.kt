package io.github.valerioisufi.drawview.export

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.canvas.platform.encodePng
import io.github.valerioisufi.drawview.canvas.render.tile.PdfTileAdapter
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.canvas.render.tile.TileRenderer
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.PageContentSnapshot
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.platform.PlatformFiles
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes the first page exported as a PNG image, drawn as the editor draws it, [pixelsPerMm]
 * (200 dpi by default, at most 8192 pixels a side).
 *
 * @param textAdapter draws the texts (the one of the editor), or `null` to leave them out.
 * @param pdfTiles creates the adapter drawing the PDF pages (see `DrawViewEngines.pdfTiles`).
 */
class PageImageExporter(
    private val textAdapter: TextTileAdapter?,
    private val pixelsPerMm: Float = 200f / 25.4f,
    private val pdfTiles: () -> PdfTileAdapter? = { null }
) : DocumentExporter {
    override val extension: String get() = "png"
    override val mimeType: String get() = "image/png"

    override suspend fun export(content: DocumentContent, options: ExportOptions, path: String, progress: (Float) -> Unit): ExportResult =
        withContext(Dispatchers.Default) {
            val page = content.pages.firstOrNull() ?: error("No page to export")
            val shown = if (options.backgroundPattern) page else page.withBackground(PageBackground.Solid(page.background.backgroundColor))
            val scale = minOf(pixelsPerMm, MAX_SIDE / max(page.widthMm, page.heightMm))
            val width = ceil(page.widthMm * scale).toInt().coerceAtLeast(1)
            val height = ceil(page.heightMm * scale).toInt().coerceAtLeast(1)
            val bitmap = ImageBitmap(width, height)
            // The renderer finds the files of the resources in a document
            val document = DrawDocument(title = content.title, addInitialPage = false).apply { content.resources.values.forEach(::addResource) }
            val pdfAdapter = pdfTiles()
            try {
                TileRenderer(pdfAdapter, textAdapter).renderPageContent(
                    Canvas(bitmap), document, shown, Matrix2D().postScale(scale, scale), Rect(0f, 0f, width.toFloat(), height.toFloat())
                )
            } finally {
                pdfAdapter?.close()
            }
            val png = checkNotNull(encodePng(bitmap)) { "Cannot encode the page" }
            check(PlatformFiles.writeBytes(path, png)) { "Cannot write the image to $path" }
            progress(1f)
            ExportResult(pages = 1, skipped = 0)
        }

    private fun PageContentSnapshot.withBackground(background: PageBackground) =
        PageContentSnapshot(strokes, texts, images, pdfs, background, widthMm, heightMm, markups)

    private companion object {
        const val MAX_SIDE = 8192f
    }
}
