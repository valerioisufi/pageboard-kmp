package io.github.valerioisufi.drawview.canvas.render.tile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import io.github.valerioisufi.drawview.canvas.StrokeMeshPainter
import io.github.valerioisufi.drawview.canvas.TextMarkupPainter
import io.github.valerioisufi.drawview.canvas.geometry.toCompose
import io.github.valerioisufi.drawview.canvas.geometry.toComposeMatrix
import io.github.valerioisufi.drawview.canvas.geometry.toModel
import io.github.valerioisufi.drawview.canvas.render.ElementRenderers
import io.github.valerioisufi.drawview.canvas.render.ImageCache
import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.PageContentSnapshot
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Worker responsible for rasterizing a discrete tile in the multi-resolution pyramid.
 * Renders page backgrounds, PDF layers and the marks of their text, images, texts, formulas, and vector strokes into the tile's [ImageBitmap].
 *
 * The renderer is stateless and thread-safe: every call allocates its own paints, so several
 * tile workers can rasterize concurrently.
 *
 * @param pdfAdapter Optional adapter for rendering PDF document pages.
 * @param textAdapter Optional adapter for rasterizing text blocks and LaTeX formulas.
 * @param images the pixels of the images of the documents.
 */
class TileRenderer(
    var pdfAdapter: PdfTileAdapter? = null,
    var textAdapter: TextTileAdapter? = null,
    val images: ImageCache = ImageCache.Shared
) {
    /** Whether the element of an identifier is left out (drawn over the tiles by the editor, see [HiddenElements]). */
    var isHidden: (String) -> Boolean = { false }

    /** Draws the elements of the types of the application (placeholders for the others). */
    var elements: ElementRenderers = ElementRenderers.None

    /**
     * Renders the content of [page] for [tileKey] into [targetBitmap].
     */
    fun renderTile(
        tileKey: TileKey,
        targetBitmap: ImageBitmap,
        document: DrawDocument,
        page: DrawPage,
        pageWorldRect: Rect
    ) {
        renderTile(tileKey, targetBitmap, document, page.snapshotContent(), pageWorldRect)
    }

    /**
     * Renders [content] for [tileKey] into [targetBitmap].
     *
     * @param pageWorldRect Unscaled World Space bounds of the page.
     */
    fun renderTile(
        tileKey: TileKey,
        targetBitmap: ImageBitmap,
        document: DrawDocument,
        content: PageContentSnapshot,
        pageWorldRect: Rect
    ) {
        val canvas = Canvas(targetBitmap)
        val tilePixelBounds = Rect(0f, 0f, targetBitmap.width.toFloat(), targetBitmap.height.toFloat())

        // Clear previous pixels
        canvas.drawRect(tilePixelBounds, Paint().apply { blendMode = BlendMode.Clear })

        val mmToTileMatrix = TilePyramid.getMmToTileMatrix(tileKey, content.rect().toCompose(), pageWorldRect)
        renderPageContent(canvas, document, content, mmToTileMatrix, tilePixelBounds)
    }

    /**
     * Renders the whole page into a new bitmap [widthPx] pixels wide (page thumbnails).
     */
    fun renderThumbnail(document: DrawDocument, page: DrawPage, widthPx: Int): ImageBitmap =
        renderThumbnail(document, page.snapshotContent(), widthPx)

    /**
     * Rasterizes [content] (a page copied, drawn on any thread) into a thumbnail [widthPx] wide;
     * [document] gives the files of its resources.
     */
    fun renderThumbnail(document: DrawDocument, content: PageContentSnapshot, widthPx: Int): ImageBitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = if (content.widthMm > 0f) ceil(w * content.heightMm / content.widthMm).toInt().coerceAtLeast(1) else w
        val bitmap = ImageBitmap(w, h)
        val canvas = Canvas(bitmap)
        val scale = if (content.widthMm > 0f) w / content.widthMm else 1f
        val mmToCanvas = Matrix2D().postScale(scale, scale)
        renderPageContent(canvas, document, content, mmToCanvas, Rect(0f, 0f, w.toFloat(), h.toFloat()))
        return bitmap
    }

    /**
     * Renders every layer of [content] on [canvas].
     *
     * @param mmToCanvas Transformation from page millimeters to canvas pixels.
     * @param clipBounds Visible region of the canvas.
     */
    fun renderPageContent(
        canvas: Canvas,
        document: DrawDocument,
        content: PageContentSnapshot,
        mmToCanvas: Matrix2D,
        clipBounds: Rect
    ) {
        val pageCanvasRect = mmToCanvas.mapRect(content.rect()).toCompose()
        val visibleRect = pageCanvasRect.intersectOrZero(clipBounds)
        if (visibleRect.isEmpty) return

        val canvasToMm = Matrix2D()
        // In page millimeters, as the bounds of the elements
        val visibleMmBounds = if (mmToCanvas.invert(canvasToMm)) {
            // Small margin: anti-aliasing fringes of the elements crossing the border
            canvasToMm.mapRect(visibleRect.toModel()).inflate(2f / max(mmToCanvas.scaleFactor, 1e-3f))
        } else {
            content.rect()
        }

        // --- 1. Page background (paper & patterns) ---
        PageBackgroundRenderer().drawBackground(
            canvas = canvas,
            pageRect = pageCanvasRect,
            background = content.background,
            pageWidthMm = content.widthMm,
            targetWidth = clipBounds.right,
            targetHeight = clipBounds.bottom
        )

        canvas.save()
        canvas.clipRect(visibleRect)

        // --- 2. Layers: PDF pages, marks of their text, images, texts and formulas, strokes ---
        val pdf = pdfAdapter
        val text = textAdapter
        val markupPaint = Paint()
        val imagePaint = Paint().apply {
            filterQuality = FilterQuality.Medium
            isAntiAlias = true
        }
        val strokePainter = StrokeMeshPainter()
        for (element in content.paintOrder) {
            when (element) {
                is DrawPdf -> {
                    if (pdf == null) continue
                    val resource = document.findResource(element.resourceId) ?: continue
                    if (resource.type != DrawResource.ResourceType.PDF) continue
                    try {
                        pdf.renderPdfPage(canvas, resource.path, element.pdfPageIndex, pageCanvasRect, visibleRect)
                    } catch (_: Throwable) {
                        // A corrupted or missing PDF must not break the rendering of the page
                    }
                }

                is DrawTextMarkup -> {
                    val pointsToMm = element.pointsToPageMm(content.widthMm, content.heightMm)
                    if (!pointsToMm.mapRect(element.boundsPoints).overlaps(visibleMmBounds)) continue
                    TextMarkupPainter.draw(
                        canvas = canvas,
                        rects = element.rects,
                        kind = element.kind,
                        color = Color(element.color),
                        pointsToCanvas = pointsToMm.postConcat(mmToCanvas),
                        paint = markupPaint
                    )
                }

                is DrawImage -> {
                    if (isHidden(element.id)) continue
                    if (!element.getRotatedBounds().overlaps(visibleMmBounds)) continue
                    val bitmap = imageOf(document, element) ?: continue
                    drawImage(canvas, element, bitmap, mmToCanvas, imagePaint)
                }

                is DrawText -> {
                    if (text == null || isHidden(element.id)) continue
                    if (!element.getRotatedBounds().overlaps(visibleMmBounds)) continue
                    try {
                        text.drawText(canvas, element, mmToCanvas)
                    } catch (_: Throwable) {
                    }
                }

                is DrawCustomElement -> {
                    if (isHidden(element.id)) continue
                    if (!element.getRotatedBounds().overlaps(visibleMmBounds)) continue
                    elements.draw(canvas, element, mmToCanvas)
                }

                is DrawStroke -> {
                    if (isHidden(element.id)) continue
                    val bounds = element.bounds
                    if (bounds.left < visibleMmBounds.right &&
                        bounds.right > visibleMmBounds.left &&
                        bounds.top < visibleMmBounds.bottom &&
                        bounds.bottom > visibleMmBounds.top
                    ) {
                        strokePainter.draw(canvas, element.mesh, mmToCanvas)
                    }
                }
            }
        }

        canvas.restore()
    }

    /** The decoded pixels of [image] of [document] (loaded from its resource file when needed). */
    fun imageOf(document: DrawDocument, image: DrawImage): ImageBitmap? =
        document.findResource(image.resourceId)?.let { images.load(it.path) }

    companion object {

        /**
         * Draws [bitmap] stretched over the (rotated) rectangle of [image].
         */
        fun drawImage(canvas: Canvas, image: DrawImage, bitmap: ImageBitmap, mmToCanvas: Matrix2D, paint: Paint) {
            if (bitmap.width <= 0 || bitmap.height <= 0) return
            val imageMatrix = Matrix2D()
                .postScale(image.width / bitmap.width, image.height / bitmap.height)
                .postRotate(image.rotation, image.width / 2f, image.height / 2f)
                .postTranslate(image.x, image.y)
                .postConcat(mmToCanvas)
            canvas.save()
            canvas.concat(imageMatrix.toComposeMatrix())
            canvas.drawImage(bitmap, Offset.Zero, paint)
            canvas.restore()
        }
    }
}

internal fun Rect.intersectOrZero(other: Rect): Rect {
    val l = max(left, other.left)
    val t = max(top, other.top)
    val r = min(right, other.right)
    val b = min(bottom, other.bottom)
    return if (l < r && t < b) Rect(l, t, r, b) else Rect.Zero
}
