package io.github.valerioisufi.pageboard.lecture.ui

import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.pageboard.canvas.port.EditorRendering
import io.github.valerioisufi.pageboard.lecture.LectureAssistant
import io.github.valerioisufi.pageboard.lecture.NotebookSnapshot
import io.github.valerioisufi.pageboard.lecture.NotebookSource
import io.github.valerioisufi.pageboard.lecture.PageContent
import io.github.valerioisufi.pageboard.lecture.PageImage
import io.github.valerioisufi.pageboard.lecture.SlideText
import io.github.valerioisufi.pageboard.lecture.cleanSlideText
import io.github.valerioisufi.pageboard.model.ink.InkBlock
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.model.ink.blockKey
import io.github.valerioisufi.pageboard.model.ink.groupStrokes
import io.github.valerioisufi.pageboard.model.ink.isHandwriting
import io.github.valerioisufi.pageboard.model.ink.unionOf
import io.github.valerioisufi.pageboard.lecture.ui.platform.encodeJpeg
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.PageContentSnapshot
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.platform.SyncLock
import io.github.valerioisufi.pageboard.platform.syncLock
import io.github.valerioisufi.pageboard.ui.extension.EditorContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [NotebookSource] of an open editor: the text of the slides comes from the PDF adapter of
 * [editor] (PDFium), the images from its tile renderer (paper, PDF, images, texts and ink, as
 * on screen).
 */
internal class ControllerNotebookSource(private val editor: EditorContext) : NotebookSource {
    /** Text of the PDF pages, by resource and page (read once). */
    private val slideTexts = mutableMapOf<Pair<String, Int>, String>()
    private val slideTextsLock = SyncLock()

    override val documentTitle: String get() = editor.document.document.title

    override suspend fun strokeIds(): Set<String> = withContext(Dispatchers.Default) {
        editor.document.document.pages
            .flatMapTo(mutableSetOf()) { page -> page.snapshotContent().strokes.map { it.id } }
    }

    override suspend fun capture(knownStrokeIds: Set<String>, textPages: Int, imagePages: Int): NotebookSnapshot {
        val document = editor.document.document
        // Camera and layout: read on the UI thread
        val (visible, pages) = withContext(Dispatchers.Main) {
            editor.pages.visiblePageIndices() to document.pages.toList()
        }
        return withContext(Dispatchers.Default) {
            val visibleIndexes = visible.filter { it in pages.indices }.ifEmpty { if (pages.isEmpty()) emptyList() else listOf(0) }
            val first = visibleIndexes.minOrNull() ?: 0
            val last = visibleIndexes.maxOrNull() ?: 0
            val around = ((first - textPages)..(last + textPages))
                .filter { it in pages.indices && it !in visibleIndexes }
                .sortedBy { if (it < first) first - it else it - last }
            val renderer = editor.rendering
            val contents = pages.map { it.snapshotContent() }
            val pdfNumbers = pdfNumbers(contents)

            val pageContents = (visibleIndexes + around).map { index -> pageContent(index, pages[index].id, contents[index], pdfNumbers) }

            val contentByIndex = pageContents.associateBy { it.index }
            // Close-ups of the new strokes, page by page
            val strokeIds = mutableSetOf<String>()
            val newStrokesByPage = mutableMapOf<Int, List<StrokeElement>>()
            for ((index, content) in contents.withIndex()) {
                content.strokes.forEach { strokeIds.add(it.id) }
                val newStrokes = content.strokes.filter { it.id !in knownStrokeIds }
                if (newStrokes.isNotEmpty()) newStrokesByPage[index] = newStrokes
            }

            val pageImages = visibleIndexes.take(imagePages).mapNotNull { index ->
                val pageInfo = contentByIndex[index]
                // Evita di inviare un JPEG da 560 token per un foglio bianco o con solo testo già noto
                if (pageInfo != null && (pageInfo.isBlank || (pageInfo.slide == null && pageInfo.imageCount == 0 && !pageInfo.hasInk))) {
                    return@mapNotNull null
                }
                val bitmap = try {
                    renderer.renderThumbnail(document, contents[index], PAGE_WIDTH_PX)
                } catch (_: Throwable) {
                    null
                }
                bitmap?.let { encodeJpeg(it) }?.let { PageImage(index, it) }
            }
            val fullImagePages = pageImages.mapTo(mutableSetOf()) { it.pageIndex }

            val newWriting = mutableListOf<PageImage>()
            for ((index, newStrokes) in newStrokesByPage) {
                if (newWriting.size >= MAX_NEW_WRITING_IMAGES) break
                val content = contents[index]
                val bounds = newStrokes.map { it.bounds }.reduce(::union)
                // Se la pagina è già inviata per intero in pageImages e il nuovo tratto copre oltre il 35% dell'area, evita il doppione
                val coversPage = bounds.width * bounds.height > content.widthMm * content.heightMm * 0.35f
                if (index in fullImagePages && coversPage) continue
                renderRegion(renderer, document, content, index, bounds)?.let { newWriting.add(it) }
            }

            NotebookSnapshot(
                documentTitle = document.title,
                pageCount = pages.size,
                visiblePageIndexes = visibleIndexes,
                pages = pageContents,
                pageImages = pageImages,
                newWriting = newWriting,
                inkBlocks = pageContents.flatMap { page -> inkBlocksOf(page.index, page.pageId, contents[page.index].strokes) },
                strokeIds = strokeIds,
                pageIds = pages.map { it.id }
            )
        }
    }

    override suspend fun pageIds(): List<String> = withContext(Dispatchers.Main) { editor.document.document.pages.map { it.id } }

    override suspend fun allPages(): List<PageContent> {
        val pages = withContext(Dispatchers.Main) { editor.document.document.pages.toList() }
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
        val pages = withContext(Dispatchers.Main) { editor.document.document.pages.toList() }
        return withContext(Dispatchers.Default) {
            pageIndexes.filter { it in pages.indices }.flatMap { index -> inkBlocksOf(index, pages[index].id, pages[index].snapshotContent().strokes) }
        }
    }

    override suspend fun renderInk(block: InkBlock): ByteArray? {
        val document = editor.document.document
        val pages = withContext(Dispatchers.Main) { document.pages.toList() }
        return withContext(Dispatchers.Default) {
            val page = pages.firstOrNull { it.id == block.pageId } ?: pages.getOrNull(block.pageIndex) ?: return@withContext null
            val content = page.snapshotContent()
            val strokes = content.strokes.filter { it.id in block.strokeIds }
            if (strokes.isEmpty()) return@withContext null
            // The ink alone, on white: the slide behind would confuse the reading
            val ink = PageContentSnapshot(strokes, emptyList(), emptyList(), emptyList(), PageBackground.Solid(), content.widthMm, content.heightMm)
            renderRegion(editor.rendering, document, ink, block.pageIndex, unionOf(strokes.map { it.bounds }))?.jpeg
        }
    }

    override suspend fun inkSample(block: InkBlock): InkSample? {
        val pages = withContext(Dispatchers.Main) { editor.document.document.pages.toList() }
        return withContext(Dispatchers.Default) {
            val page = pages.firstOrNull { it.id == block.pageId } ?: pages.getOrNull(block.pageIndex) ?: return@withContext null
            InkSample.of(page.snapshotContent().strokes.filter { it.id in block.strokeIds }).takeIf { !it.isEmpty }
        }
    }

    /** The text of a PDF page, without the empty lines, at most `MAX_SLIDE_TEXT_CHARS`. */
    private fun slideText(resourceId: String, pdfPageIndex: Int): String {
        syncLock(slideTextsLock) { slideTexts[resourceId to pdfPageIndex] }?.let { return it }
        val resource = editor.document.document.findResource(resourceId)?.takeIf { it.type == DocumentResource.ResourceType.PDF }
        val text = cleanSlideText(resource?.let { editor.rendering.pdfPageText(it.path, pdfPageIndex) }.orEmpty())
        syncLock(slideTextsLock) { slideTexts[resourceId to pdfPageIndex] = text }
        return text
    }

    /** Renders [region] of the page (with its background), at most [CROP_MAX_SIDE_PX] pixels. */
    private fun renderRegion(
        renderer: EditorRendering,
        document: Document,
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
            renderer.renderPageContent(Canvas(bitmap), document, content, matrix, ComposeRect(0f, 0f, w.toFloat(), h.toFloat()))
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

/**
 * The handwriting of page [pageIndex] ([pageId]) in blocks of strokes close to each other, among
 * [strokes] (the highlights left out), each with the key its text is kept by.
 */
internal fun inkBlocksOf(pageIndex: Int, pageId: String, strokes: List<StrokeElement>): List<InkBlock> {
    val writing = strokes.filter { it.isHandwriting }
    if (writing.isEmpty()) return emptyList()
    return groupStrokes(writing.map { it.bounds }).map { group ->
        val blockStrokes = group.map { writing[it] }
        val ids = blockStrokes.mapTo(mutableSetOf()) { it.id }
        InkBlock(pageIndex, pageId, blockKey(ids), ids, unionOf(blockStrokes.map { it.bounds }))
    }
}

/** A stroke of writing or drawing (the highlights mark the slides, they are not read). */
private val StrokeElement.isHandwriting: Boolean
    get() = brushConfig.tool.isHandwriting

private fun union(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))

/** The notebook of [editor], the editor of [documentId], for the recordings of the document. */
internal fun LectureAssistant.notebookOf(documentId: String, editor: EditorContext): NotebookSource =
    editorNotebook(documentId) { ControllerNotebookSource(editor) }
