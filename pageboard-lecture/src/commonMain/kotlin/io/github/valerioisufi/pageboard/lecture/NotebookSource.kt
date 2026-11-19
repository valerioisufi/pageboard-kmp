package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.handwriting.InkBlock
import io.github.valerioisufi.pageboard.model.DrawStroke
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.geometry.Rect
import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

/**
 * An image of a page of the notebook sent to the model.
 *
 * @param isCrop the image shows only a part of the page (e.g. what the student just wrote).
 */
public class PageImage(public val pageIndex: Int, public val jpeg: ByteArray, internal val isCrop: Boolean = false)

/**
 * The PDF page shown as the background of a notebook page, with its text.
 *
 * @param pdfNumber number of the PDF document among those of the notebook (1-based, in the order
 *   of their first page).
 * @param text the text layer of the page (empty for a scanned page).
 */
@Serializable
public data class SlideText(val pdfNumber: Int, val pdfPageIndex: Int, val text: String)

/**
 * What the assistant knows of a page of the notebook: the same description for every provider,
 * in text (the images of the visible pages are added to it, see [NotebookSnapshot.pageImages]).
 *
 * @param pageId identifier of the page ([io.github.valerioisufi.pageboard.model.DrawPage.id]),
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
public data class PageContent(
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
public class NotebookSnapshot(
    public val documentTitle: String,
    public val pageCount: Int,
    public val visiblePageIndexes: List<Int>,
    public val pages: List<PageContent>,
    public val pageImages: List<PageImage> = emptyList(),
    public val newWriting: List<PageImage> = emptyList(),
    public val inkBlocks: List<InkBlock> = emptyList(),
    public val strokeIds: Set<String> = emptySet(),
    public val pageIds: List<String> = emptyList()
) {
    internal fun withPages(pages: List<PageContent>) =
        NotebookSnapshot(documentTitle, pageCount, visiblePageIndexes, pages, pageImages, newWriting, inkBlocks, strokeIds, pageIds)
}

/** Source of the notebook content sent to the model. */
public interface NotebookSource {
    public val documentTitle: String

    /** Strokes of the document (those present when the lecture starts are not "new writing"). */
    public suspend fun strokeIds(): Set<String>

    /**
     * @param textPages pages described around the visible ones (before and after).
     * @param imagePages visible pages sent as images.
     */
    public suspend fun capture(knownStrokeIds: Set<String>, textPages: Int, imagePages: Int): NotebookSnapshot

    /** Every page of the notebook in text, without images (e.g. to write the course context). */
    public suspend fun allPages(): List<PageContent> = emptyList()

    /** The identifier of every page, by index. */
    public suspend fun pageIds(): List<String> = emptyList()

    /** The handwriting of the pages [pageIndexes], in blocks. */
    public suspend fun inkBlocks(pageIndexes: List<Int>): List<InkBlock> = emptyList()

    /** An image of the strokes of [block] alone, on white (`null` when it cannot be rendered). */
    public suspend fun renderInk(block: InkBlock): ByteArray? = null
}

/** The text layer of a slide: lines trimmed, no empty lines, at most [MAX_SLIDE_TEXT_CHARS]. */
public fun cleanSlideText(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n')
        .lines().map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("\n")
        .take(MAX_SLIDE_TEXT_CHARS)

internal const val MAX_SLIDE_TEXT_CHARS = 1200


