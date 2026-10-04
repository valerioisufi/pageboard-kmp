package io.github.valerioisufi.drawview.lecture

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.readSnapshot
import io.github.valerioisufi.drawview.lecture.platform.encodeJpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.min

/** A page of the notebook rendered for the model. */
class PageImage(val pageIndex: Int, val jpeg: ByteArray)

/**
 * A PDF document imported in the notebook (typically the slides of the lecture).
 *
 * @param pages notebook page index of each PDF page shown in the notebook, by PDF page index.
 */
class SlideDocument(
    val resourceId: String,
    val path: String,
    val pages: List<Pair<Int, Int>>
)

/**
 * What the student is looking at and writing, captured at every update.
 *
 * @param pageImages the visible pages, then the pages around them.
 * @param newWriting close-ups of the strokes added since the previous update.
 * @param typedTexts typed text blocks of the pages of [pageImages], by page index.
 * @param slides PDF documents of the pages around the visible ones.
 * @param strokeIds strokes of the document seen by this capture.
 */
class NotebookSnapshot(
    val documentTitle: String,
    val pageCount: Int,
    val visiblePageIndexes: List<Int>,
    val pageImages: List<PageImage>,
    val newWriting: List<PageImage>,
    val typedTexts: Map<Int, List<String>>,
    val slides: List<SlideDocument>,
    val strokeIds: Set<String>
)

/** Source of the notebook content sent to the model. */
interface NotebookSource {
    val documentTitle: String

    /** Strokes of the document (those present when the lecture starts are not "new writing"). */
    suspend fun strokeIds(): Set<String>

    suspend fun capture(knownStrokeIds: Set<String>, neighborPages: Int): NotebookSnapshot
}

/**
 * [NotebookSource] of an open editor: renders the pages with the tile renderer of [controller]
 * (paper, PDF, images, texts and ink, as on screen).
 */
class ControllerNotebookSource(private val controller: DrawViewController) : NotebookSource {

    override val documentTitle: String get() = controller.document.title

    override suspend fun strokeIds(): Set<String> = withContext(Dispatchers.Default) {
        readSnapshot { controller.document.pages.toList() }
            .flatMapTo(mutableSetOf()) { page -> page.snapshotContent().strokes.map { it.id } }
    }

    override suspend fun capture(knownStrokeIds: Set<String>, neighborPages: Int): NotebookSnapshot {
        val document = controller.document
        // Camera and layout: read on the UI thread
        val (visible, pages) = withContext(Dispatchers.Main) {
            controller.visiblePages().map { it.index } to document.pages.toList()
        }
        return withContext(Dispatchers.Default) {
            val visibleIndexes = visible.filter { it in pages.indices }.ifEmpty { if (pages.isEmpty()) emptyList() else listOf(0) }
            val first = visibleIndexes.minOrNull() ?: 0
            val last = visibleIndexes.maxOrNull() ?: 0
            val around = ((first - neighborPages)..(last + neighborPages)).filter { it in pages.indices && it !in visibleIndexes }
            val renderer = controller.tileManager.tileRenderer

            val pageImages = (visibleIndexes.map { it to VISIBLE_PAGE_WIDTH_PX } + around.map { it to NEIGHBOR_PAGE_WIDTH_PX })
                .mapNotNull { (index, widthPx) ->
                    val bitmap = try {
                        renderer.renderThumbnail(document, pages[index], widthPx)
                    } catch (_: Throwable) {
                        null
                    }
                    bitmap?.let { encodeJpeg(it) }?.let { PageImage(index, it) }
                }

            // Close-ups of the new strokes, page by page
            val strokeIds = mutableSetOf<String>()
            val newWriting = mutableListOf<PageImage>()
            for ((index, page) in pages.withIndex()) {
                val content = page.snapshotContent()
                content.strokes.forEach { strokeIds.add(it.id) }
                val newStrokes = content.strokes.filter { it.id !in knownStrokeIds }
                if (newStrokes.isEmpty() || newWriting.size >= MAX_NEW_WRITING_IMAGES) continue
                var bounds = newStrokes.first().bounds
                for (stroke in newStrokes) {
                    val b = stroke.bounds
                    bounds = Rect(min(bounds.left, b.left), min(bounds.top, b.top), maxOf(bounds.right, b.right), maxOf(bounds.bottom, b.bottom))
                }
                val region = Rect(
                    (bounds.left - CROP_MARGIN_MM).coerceAtLeast(0f),
                    (bounds.top - CROP_MARGIN_MM).coerceAtLeast(0f),
                    (bounds.right + CROP_MARGIN_MM).coerceAtMost(content.widthMm),
                    (bounds.bottom + CROP_MARGIN_MM).coerceAtMost(content.heightMm)
                )
                if (region.width <= 0f || region.height <= 0f) continue
                val scale = min(CROP_PX_PER_MM, CROP_MAX_SIDE_PX / maxOf(region.width, region.height))
                val w = ceil(region.width * scale).toInt().coerceAtLeast(1)
                val h = ceil(region.height * scale).toInt().coerceAtLeast(1)
                try {
                    val bitmap = ImageBitmap(w, h)
                    val matrix = Matrix2D().postTranslate(-region.left, -region.top).postScale(scale, scale)
                    renderer.renderPageContent(Canvas(bitmap), document, content, matrix, Rect(0f, 0f, w.toFloat(), h.toFloat()))
                    encodeJpeg(bitmap)?.let { newWriting.add(PageImage(index, it)) }
                } catch (_: Throwable) {
                    // A page that cannot be rendered is not sent
                }
            }

            val typedTexts = pageImages.associate { image ->
                image.pageIndex to pages[image.pageIndex].snapshotContent().texts.map { it.text.trim() }.filter { it.isNotEmpty() }
            }.filterValues { it.isNotEmpty() }

            NotebookSnapshot(
                documentTitle = document.title,
                pageCount = pages.size,
                visiblePageIndexes = visibleIndexes,
                pageImages = pageImages,
                newWriting = newWriting,
                typedTexts = typedTexts,
                slides = slidesAround(pages.map { it.snapshotContent().pdfs.map { pdf -> pdf.resourceId to pdf.pdfPageIndex } }, first, last),
                strokeIds = strokeIds
            )
        }
    }

    /** The PDF documents shown on the pages around [first]..[last], with their page mapping. */
    private fun slidesAround(pdfsByPage: List<List<Pair<String, Int>>>, first: Int, last: Int): List<SlideDocument> {
        val window = (first - SLIDES_WINDOW_PAGES)..(last + SLIDES_WINDOW_PAGES)
        val resourceIds = window.filter { it in pdfsByPage.indices }.flatMap { pdfsByPage[it].map { pdf -> pdf.first } }.distinct()
        return resourceIds.mapNotNull { resourceId ->
            val resource = controller.document.findResource(resourceId)
                ?.takeIf { it.type == DrawResource.ResourceType.PDF } ?: return@mapNotNull null
            val mapping = pdfsByPage.withIndex().flatMap { (pageIndex, pdfs) ->
                pdfs.filter { it.first == resourceId }.map { it.second to pageIndex }
            }.sortedBy { it.first }
            SlideDocument(resourceId, resource.path, mapping)
        }
    }

    private companion object {
        const val VISIBLE_PAGE_WIDTH_PX = 1024
        const val NEIGHBOR_PAGE_WIDTH_PX = 640
        const val MAX_NEW_WRITING_IMAGES = 4
        const val CROP_MARGIN_MM = 6f
        const val CROP_PX_PER_MM = 8f
        const val CROP_MAX_SIDE_PX = 1280f

        /** Pages around the visible ones whose PDF documents are sent. */
        const val SLIDES_WINDOW_PAGES = 10
    }
}
