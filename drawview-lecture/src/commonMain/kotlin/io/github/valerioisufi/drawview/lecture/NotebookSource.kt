package io.github.valerioisufi.drawview.lecture

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.PageContentSnapshot
import io.github.valerioisufi.drawview.core.model.readSnapshot
import io.github.valerioisufi.drawview.core.render.tile.TileRenderer
import io.github.valerioisufi.drawview.lecture.platform.encodeJpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * An image of a page of the notebook sent to the model.
 *
 * @param isCrop the image shows only the area written by the student, not the whole page.
 */
class PageImage(val pageIndex: Int, val jpeg: ByteArray, val isCrop: Boolean = false)

/**
 * A PDF document imported in the notebook (typically the slides of the lecture).
 *
 * @param pages notebook page index of each PDF page shown in the notebook, by PDF page index.
 */
@Serializable
data class SlideDocument(
    val resourceId: String,
    val path: String,
    val pages: List<Pair<Int, Int>>
)

/** A PDF page shown as the background of a notebook page. */
class SlidePage(val resourceId: String, val pdfPageIndex: Int)

/**
 * A page of the notebook around the visible ones.
 *
 * @param slide the PDF page of its background, if any.
 * @param image the area written by the student (`null` when the page holds nothing of theirs), or
 *   the whole page when the model cannot get the slide otherwise.
 * @param typedTexts typed text blocks of the page.
 */
data class NotebookPage(
    val index: Int,
    val visible: Boolean,
    val slide: SlidePage?,
    val image: PageImage?,
    val typedTexts: List<String>
)

/**
 * What the student is looking at and writing, captured at every update.
 *
 * @param pages the visible pages, then the pages around them.
 * @param newWriting close-ups of the strokes added since the previous update.
 * @param slides PDF documents of the pages around the visible ones.
 * @param strokeIds strokes of the document seen by this capture.
 * @param pageDbIds database id of every page of the document, by index (0: not saved yet).
 */
class NotebookSnapshot(
    val documentTitle: String,
    val pageCount: Int,
    val visiblePageIndexes: List<Int>,
    val pages: List<NotebookPage>,
    val newWriting: List<PageImage>,
    val slides: List<SlideDocument>,
    val strokeIds: Set<String>,
    val pageDbIds: List<Int> = emptyList()
) {
    val pageImages: List<PageImage> get() = pages.mapNotNull { it.image }

    /** This capture with whole-page images of [replacements] (by page index). */
    fun withPageImages(replacements: Map<Int, PageImage>): NotebookSnapshot = NotebookSnapshot(
        documentTitle, pageCount, visiblePageIndexes,
        pages.map { page -> replacements[page.index]?.let { page.copy(image = it) } ?: page },
        newWriting, slides, strokeIds, pageDbIds
    )
}

/** Source of the notebook content sent to the model. */
interface NotebookSource {
    val documentTitle: String

    /** Strokes of the document (those present when the lecture starts are not "new writing"). */
    suspend fun strokeIds(): Set<String>

    suspend fun capture(knownStrokeIds: Set<String>, neighborPages: Int): NotebookSnapshot

    /** Whole-page images of [pageIndexes] (slides that cannot be sent as PDF). */
    suspend fun renderPages(pageIndexes: List<Int>): List<PageImage>
}

/**
 * [NotebookSource] of an open editor: renders the pages with the tile renderer of [controller]
 * (paper, PDF, images, texts and ink, as on screen).
 *
 * Every image costs the same tokens whatever its size: only what the model cannot get otherwise
 * is rendered, i.e. the area written by the student on each page (a slide without notes is in the
 * PDF, an empty page needs no image).
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
            val contents = pages.map { it.snapshotContent() }

            val notebookPages = (visibleIndexes + around).map { index ->
                val content = contents[index]
                val slide = content.pdfs.firstOrNull()?.let { SlidePage(it.resourceId, it.pdfPageIndex) }
                val written = studentContentBounds(content)
                NotebookPage(
                    index = index,
                    visible = index in visibleIndexes,
                    slide = slide,
                    image = written?.let { renderRegion(renderer, document, content, index, it, isCrop = true) },
                    typedTexts = content.texts.map { it.text.trim() }.filter { it.isNotEmpty() }
                )
            }

            // Close-ups of the new strokes, page by page
            val strokeIds = mutableSetOf<String>()
            val newWriting = mutableListOf<PageImage>()
            for ((index, content) in contents.withIndex()) {
                content.strokes.forEach { strokeIds.add(it.id) }
                val newStrokes = content.strokes.filter { it.id !in knownStrokeIds }
                if (newStrokes.isEmpty() || newWriting.size >= MAX_NEW_WRITING_IMAGES) continue
                val bounds = newStrokes.map { it.bounds }.reduce(::union)
                renderRegion(renderer, document, content, index, bounds, isCrop = true)?.let { newWriting.add(it) }
            }

            NotebookSnapshot(
                documentTitle = document.title,
                pageCount = pages.size,
                visiblePageIndexes = visibleIndexes,
                pages = notebookPages,
                newWriting = newWriting,
                slides = slidesAround(contents.map { c -> c.pdfs.map { pdf -> pdf.resourceId to pdf.pdfPageIndex } }, first, last),
                strokeIds = strokeIds,
                pageDbIds = pages.map { it.dbId }
            )
        }
    }

    override suspend fun renderPages(pageIndexes: List<Int>): List<PageImage> {
        val document = controller.document
        val pages = withContext(Dispatchers.Main) { document.pages.toList() }
        return withContext(Dispatchers.Default) {
            pageIndexes.filter { it in pages.indices }.mapNotNull { index ->
                val bitmap = try {
                    controller.tileManager.tileRenderer.renderThumbnail(document, pages[index], PAGE_WIDTH_PX)
                } catch (_: Throwable) {
                    null
                }
                bitmap?.let { encodeJpeg(it) }?.let { PageImage(index, it) }
            }
        }
    }

    /** Area of the strokes, texts and images of the student (page millimeters), or `null`. */
    private fun studentContentBounds(content: PageContentSnapshot): Rect? {
        val rects = content.strokes.map { it.bounds } +
            content.texts.filter { it.text.isNotBlank() }.map { it.getRotatedBounds() } +
            content.images.map { it.getRotatedBounds() }
        return rects.reduceOrNull(::union)
    }

    /** Renders [region] of the page (with its background), at most [CROP_MAX_SIDE_PX] pixels. */
    private fun renderRegion(
        renderer: TileRenderer,
        document: DrawDocument,
        content: PageContentSnapshot,
        pageIndex: Int,
        region: Rect,
        isCrop: Boolean
    ): PageImage? {
        val area = Rect(
            (region.left - CROP_MARGIN_MM).coerceAtLeast(0f),
            (region.top - CROP_MARGIN_MM).coerceAtLeast(0f),
            (region.right + CROP_MARGIN_MM).coerceAtMost(content.widthMm),
            (region.bottom + CROP_MARGIN_MM).coerceAtMost(content.heightMm)
        )
        if (area.width <= 0f || area.height <= 0f) return null
        val scale = min(CROP_PX_PER_MM, CROP_MAX_SIDE_PX / max(area.width, area.height))
        val w = ceil(area.width * scale).toInt().coerceAtLeast(1)
        val h = ceil(area.height * scale).toInt().coerceAtLeast(1)
        return try {
            val bitmap = ImageBitmap(w, h)
            val matrix = Matrix2D().postTranslate(-area.left, -area.top).postScale(scale, scale)
            renderer.renderPageContent(Canvas(bitmap), document, content, matrix, Rect(0f, 0f, w.toFloat(), h.toFloat()))
            encodeJpeg(bitmap)?.let { PageImage(pageIndex, it, isCrop) }
        } catch (_: Throwable) {
            // A page that cannot be rendered is not sent
            null
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
        const val PAGE_WIDTH_PX = 1024
        const val MAX_NEW_WRITING_IMAGES = 3
        const val CROP_MARGIN_MM = 6f
        const val CROP_PX_PER_MM = 8f
        const val CROP_MAX_SIDE_PX = 1024f

        /** Pages around the visible ones whose PDF documents are sent. */
        const val SLIDES_WINDOW_PAGES = 10
    }
}

private fun union(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
