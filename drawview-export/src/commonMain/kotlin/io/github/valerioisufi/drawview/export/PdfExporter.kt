package io.github.valerioisufi.drawview.export

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.core.engine.InkStrokeEngine
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPdf
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup
import io.github.valerioisufi.drawview.core.model.PageContentSnapshot
import io.github.valerioisufi.drawview.core.model.TextMarkupKind
import io.github.valerioisufi.drawview.core.model.pdfPointsToPageMm
import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.core.platform.decodeImageBitmap
import io.github.valerioisufi.drawview.core.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.pdf.PdfMarkupKind
import io.github.valerioisufi.drawview.pdf.PdfPath
import io.github.valerioisufi.drawview.pdf.PdfWriter
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Writes the documents as PDF (see `docs/export.md`): every page a page of the same size, with
 * its background, the pages of the PDFs it shows (their vectors and text kept), the marks of
 * their text as annotations, its images, its texts as images with their text invisible over them,
 * and its strokes as filled outlines.
 *
 * @param textAdapter draws the texts and gives their lines (the one of the editor), or `null` to
 *   leave the texts out.
 * @param createWriter the PDF written.
 */
class PdfExporter(
    private val textAdapter: TextTileAdapter?,
    private val createWriter: () -> PdfWriter = PdfiumEngine::create
) : DocumentExporter {
    override val extension: String get() = "pdf"
    override val mimeType: String get() = "application/pdf"

    override suspend fun export(content: DocumentContent, options: ExportOptions, path: String, progress: (Float) -> Unit): ExportResult =
        withContext(Dispatchers.Default) {
            var skipped = 0
            createWriter().use { writer ->
                content.pages.forEachIndexed { index, page ->
                    ensureActive()
                    skipped += PageWriter(writer, content, page).write(options)
                    progress((index + 1f) / content.pages.size)
                }
                writer.save(path)
            }
            ExportResult(content.pages.size, skipped)
        }

    /** Writes [page] with [writer]. */
    private inner class PageWriter(
        private val writer: PdfWriter,
        private val content: DocumentContent,
        private val page: PageContentSnapshot
    ) {
        private val mmToPt = Matrix2D().postScale(PT_PER_MM, PT_PER_MM)
        private var skipped = 0

        /** Returns the number of elements that could not be written. */
        fun write(options: ExportOptions): Int {
            writer.beginPage(page.widthMm * PT_PER_MM, page.heightMm * PT_PER_MM)
            writeBackground(options.backgroundPattern)
            for (element in page.paintOrder) {
                val written = when (element) {
                    is DrawPdf -> writePdfPage(element)
                    is DrawTextMarkup -> writeMarkup(element)
                    is DrawImage -> writeImage(element)
                    is DrawText -> writeText(element)
                    is DrawStroke -> writeStroke(element)
                }
                if (!written) skipped++
            }
            return skipped
        }

        private fun writeBackground(withPattern: Boolean) {
            val background = page.background
            if (background.backgroundColor != WHITE) {
                writer.fillPath(PdfPath().apply { addPolygon(rect(0f, 0f, page.widthMm, page.heightMm)) }, background.backgroundColor)
            }
            if (!withPattern) return
            val pattern = background.pattern(page.widthMm, page.heightMm) ?: return
            val path = PdfPath()
            if (pattern.dots) {
                for (x in pattern.columns) for (y in pattern.rows) {
                    path.addCircle(x * PT_PER_MM, y * PT_PER_MM, pattern.sizeMm * PT_PER_MM)
                }
                writer.fillPath(path, pattern.color)
            } else {
                for (y in pattern.rows) {
                    path.moveTo(0f, y * PT_PER_MM)
                    path.lineTo(page.widthMm * PT_PER_MM, y * PT_PER_MM)
                }
                for (x in pattern.columns) {
                    path.moveTo(x * PT_PER_MM, 0f)
                    path.lineTo(x * PT_PER_MM, page.heightMm * PT_PER_MM)
                }
                writer.strokePath(path, pattern.color, pattern.sizeMm * PT_PER_MM)
            }
        }

        private fun writePdfPage(pdf: DrawPdf): Boolean {
            val file = resourceFile(pdf.resourceId, DrawResource.ResourceType.PDF) ?: return false
            val size = writer.pdfPageSize(file, pdf.pdfPageIndex) ?: return false
            val toPage = pdfPointsToPageMm(size.widthPt.toFloat(), size.heightPt.toFloat(), page.widthMm, page.heightMm)
            return writer.drawPdfPage(file, pdf.pdfPageIndex, toPage.postConcat(mmToPt))
        }

        private fun writeMarkup(markup: DrawTextMarkup): Boolean {
            val toPage = markup.pointsToPageMm(page.widthMm, page.heightMm).postConcat(mmToPt)
            val kind = when (markup.kind) {
                TextMarkupKind.HIGHLIGHT -> PdfMarkupKind.HIGHLIGHT
                TextMarkupKind.UNDERLINE -> PdfMarkupKind.UNDERLINE
                TextMarkupKind.STRIKEOUT -> PdfMarkupKind.STRIKEOUT
            }
            return writer.addMarkup(kind, markup.rects.map(toPage::mapRect), markup.color)
        }

        private fun writeImage(image: DrawImage): Boolean {
            if (image.width <= 0f || image.height <= 0f) return true
            val file = resourceFile(image.resourceId, DrawResource.ResourceType.IMAGE) ?: return false
            val bytes = PlatformFiles.readBytes(file) ?: return false
            val toPage = boxToPage(image.x, image.y, image.width, image.height, image.rotation)
            // A JPEG is embedded as it is, unless its metadata turn it
            if (isJpeg(bytes) && jpegOrientation(bytes) == 1 && writer.drawJpeg(bytes, toPage)) return true
            val bitmap = decodeImageBitmap(bytes, MAX_IMAGE_SIDE) ?: return false
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.readPixels(pixels)
            return writer.drawImage(pixels, bitmap.width, bitmap.height, toPage)
        }

        private fun writeText(text: DrawText): Boolean {
            val adapter = textAdapter ?: return true
            if (text.width <= 0f || text.text.isBlank()) return true
            // A formula sets the height of its block when it is drawn
            if (text.isLatex) adapter.drawText(Canvas(ImageBitmap(1, 1)), text, Matrix2D().postScale(TEXT_PX_PER_MM, TEXT_PX_PER_MM))
            if (text.height <= 0f) return true

            // The block drawn without its rotation, then placed with it
            val scale = minOf(TEXT_PX_PER_MM, MAX_TEXT_SIDE / max(text.width, text.height))
            val width = ceil(text.width * scale).toInt().coerceAtLeast(1)
            val height = ceil(text.height * scale).toInt().coerceAtLeast(1)
            val bitmap = ImageBitmap(width, height)
            val canvas = Canvas(bitmap)
            canvas.drawRect(Rect(0f, 0f, width.toFloat(), height.toFloat()), Paint().apply { blendMode = BlendMode.Clear })
            val toBitmap = Matrix2D()
                .postRotate(-text.rotation, text.x + text.width / 2f, text.y + text.height / 2f)
                .postTranslate(-text.x, -text.y)
                .postScale(scale, scale)
            adapter.drawText(canvas, text, toBitmap)
            val pixels = IntArray(width * height)
            bitmap.readPixels(pixels)
            if (pixels.all { it ushr 24 == 0 }) return true
            val toPage = boxToPage(text.x, text.y, width / scale, height / scale, text.rotation, text.width, text.height)
            if (!writer.drawImage(pixels, width, height, toPage)) return false

            for (line in adapter.lines(text)) {
                val toLine = Matrix2D()
                    .postTranslate(line.left, line.baseline)
                    .postRotate(text.rotation, text.width / 2f, text.height / 2f)
                    .postTranslate(text.x, text.y)
                    .postConcat(mmToPt)
                writer.addInvisibleText(line.text, line.fontSize, line.width, toLine)
            }
            return true
        }

        private fun writeStroke(stroke: DrawStroke): Boolean {
            val outline = InkStrokeEngine.outline(stroke)
            val path = PdfPath()
            for (polygon in outline.polygons) {
                path.addPolygon(polygon.copyOf().also(mmToPt::mapPoints))
            }
            return path.isEmpty || writer.fillPath(path, stroke.mesh.color.toArgb())
        }

        /**
         * From the unit square to the box ([x], [y], [width], [height]) of the page, turned by
         * [rotation] degrees around the center of the box of [boxWidth] x [boxHeight].
         */
        private fun boxToPage(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            rotation: Float,
            boxWidth: Float = width,
            boxHeight: Float = height
        ): Matrix2D = Matrix2D()
            .postScale(width, height)
            .postRotate(rotation, boxWidth / 2f, boxHeight / 2f)
            .postTranslate(x, y)
            .postConcat(mmToPt)

        /** The file of the resource [id] of [type], or `null`. */
        private fun resourceFile(id: String, type: DrawResource.ResourceType): String? =
            content.resources[id]?.takeIf { it.type == type && PlatformFiles.exists(it.path) }?.path
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        floatArrayOf(left, top, right, top, right, bottom, left, bottom).also { Matrix2D().postScale(PT_PER_MM, PT_PER_MM).mapPoints(it) }

    private companion object {
        const val PT_PER_MM = 72f / 25.4f
        const val WHITE = 0xFFFFFFFF.toInt()

        /** Resolution of the texts: 300 dpi. */
        const val TEXT_PX_PER_MM = 300f / 25.4f

        /** Largest side of the image of a text, in pixels. */
        const val MAX_TEXT_SIDE = 8192f

        /** Largest side of an image that is not embedded as it is, in pixels. */
        const val MAX_IMAGE_SIDE = 4096
    }
}
