package io.github.valerioisufi.drawview.lecture

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.StrokeTool
import io.github.valerioisufi.drawview.core.model.PageContentSnapshot
import io.github.valerioisufi.drawview.core.model.readSnapshot
import io.github.valerioisufi.drawview.core.render.tile.TileRenderer
import io.github.valerioisufi.drawview.core.util.SyncLock
import io.github.valerioisufi.drawview.core.util.syncLock
import io.github.valerioisufi.drawview.lecture.handwriting.InkBlock
import io.github.valerioisufi.drawview.lecture.handwriting.blockKey
import io.github.valerioisufi.drawview.lecture.handwriting.groupStrokes
import io.github.valerioisufi.drawview.lecture.handwriting.unionOf
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
 * @param isCrop the image shows only a part of the page (e.g. what the student just wrote).
 */
class PageImage(val pageIndex: Int, val jpeg: ByteArray, val isCrop: Boolean = false)

/**
 * The PDF page shown as the background of a notebook page, with its text.
 *
 * @param pdfNumber number of the PDF document among those of the notebook (1-based, in the order
 *   of their first page).
 * @param text the text layer of the page (empty for a scanned page).
 */
@Serializable
data class SlideText(val pdfNumber: Int, val pdfPageIndex: Int, val text: String)

/**
 * What the assistant knows of a page of the notebook: the same description for every provider,
 * in text (the images of the visible pages are added to it, see [NotebookSnapshot.pageImages]).
 *
 * @param pageId identifier of the page ([io.github.valerioisufi.drawview.core.model.DrawPage.id]),
 *   stable when the pages are reordered (empty when not known).
 * @param slide the PDF page of its background, if any.
 * @param typedTexts typed text blocks of the student.
 * @param handwriting what the student wrote by hand, transcribed (see `HandwritingReader`), from
 *   the top of the page.
 * @param hasInk the page holds handwriting of the student (strokes other than highlights).
 * @param inkTranscribed all the handwriting of the page is in [handwriting].
 * @param imageCount pictures inserted by the student.
 */
@Serializable
data class PageContent(
    val index: Int,
    val pageId: String = "",
    val slide: SlideText? = null,
    val typedTexts: List<String> = emptyList(),
    val handwriting: List<String> = emptyList(),
    val hasInk: Boolean = false,
    val inkTranscribed: Boolean = false,
    val imageCount: Int = 0
) {
    val isBlank: Boolean get() = slide == null && typedTexts.isEmpty() && !hasInk && imageCount == 0
}

/**
 * What the student is looking at and writing, captured at every update.
 *
 * @param pages the visible pages, then the pages around them by distance.
 * @param pageImages the visible pages as they look (slide, notes, pictures).
 * @param newWriting close-ups of the strokes added since the previous update.
 * @param inkBlocks the handwriting of [pages], in blocks.
 * @param strokeIds strokes of the document seen by this capture.
 * @param pageIds identifier of every page of the document, by index.
 */
class NotebookSnapshot(
    val documentTitle: String,
    val pageCount: Int,
    val visiblePageIndexes: List<Int>,
    val pages: List<PageContent>,
    val pageImages: List<PageImage> = emptyList(),
    val newWriting: List<PageImage> = emptyList(),
    val inkBlocks: List<InkBlock> = emptyList(),
    val strokeIds: Set<String> = emptySet(),
    val pageIds: List<String> = emptyList()
) {
    fun withPages(pages: List<PageContent>) =
        NotebookSnapshot(documentTitle, pageCount, visiblePageIndexes, pages, pageImages, newWriting, inkBlocks, strokeIds, pageIds)
}

/** Source of the notebook content sent to the model. */
interface NotebookSource {
    val documentTitle: String

    /** Strokes of the document (those present when the lecture starts are not "new writing"). */
    suspend fun strokeIds(): Set<String>

    /**
     * @param textPages pages described around the visible ones (before and after).
     * @param imagePages visible pages sent as images.
     */
    suspend fun capture(knownStrokeIds: Set<String>, textPages: Int, imagePages: Int): NotebookSnapshot

    /** Every page of the notebook in text, without images (e.g. to write the course context). */
    suspend fun allPages(): List<PageContent> = emptyList()

    /** The handwriting of the pages [pageIndexes], in blocks. */
    suspend fun inkBlocks(pageIndexes: List<Int>): List<InkBlock> = emptyList()

    /** An image of the strokes of [block] alone, on white (`null` when it cannot be rendered). */
    suspend fun renderInk(block: InkBlock): ByteArray? = null
}

/**
 * [NotebookSource] of an open editor: the text of the slides comes from the PDF adapter of
 * [controller] (PDFium), the images from its tile renderer (paper, PDF, images, texts and ink, as
 * on screen).
 */
class ControllerNotebookSource(private val controller: DrawViewController) : NotebookSource {
    /** Text of the PDF pages, by resource and page (read once). */
    private val slideTexts = mutableMapOf<Pair<String, Int>, String>()
    private val slideTextsLock = SyncLock()

    override val documentTitle: String get() = controller.document.title

    override suspend fun strokeIds(): Set<String> = withContext(Dispatchers.Default) {
        readSnapshot { controller.document.pages.toList() }
            .flatMapTo(mutableSetOf()) { page -> page.snapshotContent().strokes.map { it.id } }
    }

    override suspend fun capture(knownStrokeIds: Set<String>, textPages: Int, imagePages: Int): NotebookSnapshot {
        val document = controller.document
        // Camera and layout: read on the UI thread
        val (visible, pages) = withContext(Dispatchers.Main) {
            controller.visiblePages().map { it.index } to document.pages.toList()
        }
        return withContext(Dispatchers.Default) {
            val visibleIndexes = visible.filter { it in pages.indices }.ifEmpty { if (pages.isEmpty()) emptyList() else listOf(0) }
            val first = visibleIndexes.minOrNull() ?: 0
            val last = visibleIndexes.maxOrNull() ?: 0
            val around = ((first - textPages)..(last + textPages))
                .filter { it in pages.indices && it !in visibleIndexes }
                .sortedBy { if (it < first) first - it else it - last }
            val renderer = controller.tileManager.tileRenderer
            val contents = pages.map { it.snapshotContent() }
            val pdfNumbers = pdfNumbers(contents)

            val pageContents = (visibleIndexes + around).map { index -> pageContent(index, pages[index].id, contents[index], pdfNumbers) }

            val pageImages = visibleIndexes.take(imagePages).mapNotNull { index ->
                val bitmap = try {
                    renderer.renderThumbnail(document, pages[index], PAGE_WIDTH_PX)
                } catch (_: Throwable) {
                    null
                }
                bitmap?.let { encodeJpeg(it) }?.let { PageImage(index, it) }
            }

            // Close-ups of the new strokes, page by page
            val strokeIds = mutableSetOf<String>()
            val newWriting = mutableListOf<PageImage>()
            for ((index, content) in contents.withIndex()) {
                content.strokes.forEach { strokeIds.add(it.id) }
                val newStrokes = content.strokes.filter { it.id !in knownStrokeIds }
                if (newStrokes.isEmpty() || newWriting.size >= MAX_NEW_WRITING_IMAGES) continue
                val bounds = newStrokes.map { it.bounds }.reduce(::union)
                renderRegion(renderer, document, content, index, bounds)?.let { newWriting.add(it) }
            }

            NotebookSnapshot(
                documentTitle = document.title,
                pageCount = pages.size,
                visiblePageIndexes = visibleIndexes,
                pages = pageContents,
                pageImages = pageImages,
                newWriting = newWriting,
                inkBlocks = pageContents.flatMap { page -> inkBlocks(page.index, page.pageId, contents[page.index]) },
                strokeIds = strokeIds,
                pageIds = pages.map { it.id }
            )
        }
    }

    override suspend fun allPages(): List<PageContent> {
        val pages = withContext(Dispatchers.Main) { controller.document.pages.toList() }
        return withContext(Dispatchers.Default) {
            val contents = pages.map { it.snapshotContent() }
            val pdfNumbers = pdfNumbers(contents)
            contents.mapIndexed { index, content -> pageContent(index, pages[index].id, content, pdfNumbers) }
        }
    }

    /** The PDF documents of the notebook numbered by their first page. */
    private fun pdfNumbers(contents: List<PageContentSnapshot>): Map<String, Int> =
        contents.flatMap { content -> content.pdfs.map { it.resourceId } }.distinct().withIndex().associate { (i, id) -> id to i + 1 }

    private fun pageContent(index: Int, pageId: String, content: PageContentSnapshot, pdfNumbers: Map<String, Int>) = PageContent(
        index = index,
        pageId = pageId,
        slide = content.pdfs.firstOrNull()?.let { pdf ->
            SlideText(pdfNumbers.getValue(pdf.resourceId), pdf.pdfPageIndex, slideText(pdf.resourceId, pdf.pdfPageIndex))
        },
        typedTexts = content.texts.map { it.text.trim() }.filter { it.isNotEmpty() },
        hasInk = content.strokes.any { it.isHandwriting },
        imageCount = content.images.size
    )

    override suspend fun inkBlocks(pageIndexes: List<Int>): List<InkBlock> {
        val pages = withContext(Dispatchers.Main) { controller.document.pages.toList() }
        return withContext(Dispatchers.Default) {
            pageIndexes.filter { it in pages.indices }.flatMap { index -> inkBlocks(index, pages[index].id, pages[index].snapshotContent()) }
        }
    }

    override suspend fun renderInk(block: InkBlock): ByteArray? {
        val document = controller.document
        val pages = withContext(Dispatchers.Main) { document.pages.toList() }
        return withContext(Dispatchers.Default) {
            val page = pages.firstOrNull { it.id == block.pageId } ?: pages.getOrNull(block.pageIndex) ?: return@withContext null
            val content = page.snapshotContent()
            val strokes = content.strokes.filter { it.id in block.strokeIds }
            if (strokes.isEmpty()) return@withContext null
            // The ink alone, on white: the slide behind would confuse the reading
            val ink = PageContentSnapshot(strokes, emptyList(), emptyList(), emptyList(), PageBackground.Solid(), content.widthMm, content.heightMm)
            renderRegion(controller.tileManager.tileRenderer, document, ink, block.pageIndex, unionOf(strokes.map { it.bounds }))?.jpeg
        }
    }

    /** The handwriting of a page in blocks of strokes close to each other. */
    private fun inkBlocks(pageIndex: Int, pageId: String, content: PageContentSnapshot): List<InkBlock> {
        val strokes = content.strokes.filter { it.isHandwriting }
        if (strokes.isEmpty()) return emptyList()
        return groupStrokes(strokes.map { it.bounds }).map { group ->
            val blockStrokes = group.map { strokes[it] }
            val ids = blockStrokes.mapTo(mutableSetOf()) { it.id }
            InkBlock(pageIndex, pageId, blockKey(ids), ids, unionOf(blockStrokes.map { it.bounds }))
        }
    }

    /** The text of a PDF page, without the empty lines, at most [MAX_SLIDE_TEXT_CHARS]. */
    private fun slideText(resourceId: String, pdfPageIndex: Int): String {
        syncLock(slideTextsLock) { slideTexts[resourceId to pdfPageIndex] }?.let { return it }
        val resource = controller.document.findResource(resourceId)?.takeIf { it.type == DrawResource.ResourceType.PDF }
        val text = cleanSlideText(resource?.let { controller.tileManager.tileRenderer.pdfAdapter?.getPageText(it.path, pdfPageIndex) }.orEmpty())
        syncLock(slideTextsLock) { slideTexts[resourceId to pdfPageIndex] = text }
        return text
    }

    /** Renders [region] of the page (with its background), at most [CROP_MAX_SIDE_PX] pixels. */
    private fun renderRegion(
        renderer: TileRenderer,
        document: DrawDocument,
        content: PageContentSnapshot,
        pageIndex: Int,
        region: Rect
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
            encodeJpeg(bitmap)?.let { PageImage(pageIndex, it, isCrop = true) }
        } catch (_: Throwable) {
            // A page that cannot be rendered is not sent
            null
        }
    }

    private companion object {
        const val PAGE_WIDTH_PX = 1024
        const val MAX_NEW_WRITING_IMAGES = 3
        const val CROP_MARGIN_MM = 6f
        const val CROP_PX_PER_MM = 8f
        const val CROP_MAX_SIDE_PX = 1024f
    }
}

/** The text layer of a slide: lines trimmed, no empty lines, at most [MAX_SLIDE_TEXT_CHARS]. */
internal fun cleanSlideText(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n')
        .lines().map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("\n")
        .take(MAX_SLIDE_TEXT_CHARS)

internal const val MAX_SLIDE_TEXT_CHARS = 1200

/** A stroke of writing or drawing (the highlights mark the slides, they are not read). */
private val DrawStroke.isHandwriting: Boolean
    get() = brushConfig.tool != StrokeTool.HIGHLIGHTER && brushConfig.tool != StrokeTool.ERASER && brushConfig.tool != StrokeTool.LASSO

private fun union(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
