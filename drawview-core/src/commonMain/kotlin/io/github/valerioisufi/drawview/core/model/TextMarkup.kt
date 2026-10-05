package io.github.valerioisufi.drawview.core.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.render.tile.fitPdfPage
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/**
 * How a passage of the text of a PDF is marked.
 */
enum class TextMarkupKind {
    HIGHLIGHT,
    UNDERLINE,
    STRIKEOUT;

    companion object {
        fun fromName(name: String?): TextMarkupKind? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A highlight, underline or strikeout of a passage of the text of a PDF page shown on a page
 * (see [DrawPdf]).
 *
 * The passage is the characters from [startIndex] until [endIndex] (exclusive) of the text layer
 * of page [pdfPageIndex] of the PDF [resourceId] ([text] is their text). Its [rects], one per
 * line, are in points of that PDF page, which is [pdfWidthPt] x [pdfHeightPt]: they follow the
 * PDF where the page draws it (fitted in the page, see [fitPdfPage]), even when the format of
 * the page changes. [id] identifies the mark on every device (see [newElementId]).
 */
class DrawTextMarkup(
    override val id: String = newElementId(),
    val resourceId: String,
    val pdfPageIndex: Int,
    val pdfWidthPt: Float,
    val pdfHeightPt: Float,
    val startIndex: Int,
    val endIndex: Int,
    val text: String,
    val rects: List<Rect>,
    kind: TextMarkupKind,
    color: Int,
    override var zIndex: Int = 0
) : DrawElement {
    @Volatile
    var kind: TextMarkupKind = kind

    /** ARGB color. */
    @Volatile
    var color: Int = color

    /** Transformation from the points of the PDF page to the millimeters of [page]. */
    fun pointsToPageMm(page: DrawPage): Matrix2D = pointsToPageMm(page.widthMm, page.heightMm)

    /** Transformation from the points of the PDF page to the millimeters of a [widthMm] x [heightMm] page. */
    fun pointsToPageMm(widthMm: Float, heightMm: Float): Matrix2D =
        pdfPointsToPageMm(pdfWidthPt, pdfHeightPt, widthMm, heightMm)

    /** Bounds of the lines in points of the PDF page. */
    val boundsPoints: Rect = rects.reduceOrNull { a, b ->
        Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
    } ?: Rect.Zero

    /** Bounds of the lines in the millimeters of a [widthMm] x [heightMm] page. */
    fun boundsMm(widthMm: Float, heightMm: Float): Rect = pointsToPageMm(widthMm, heightMm).mapRect(boundsPoints)

    fun boundsMm(page: DrawPage): Rect = boundsMm(page.widthMm, page.heightMm)

    /** Whether [pointMm] (millimeters of [page]) is on a line of the passage, or at most [toleranceMm] away. */
    fun containsPoint(page: DrawPage, pointMm: Offset, toleranceMm: Float = 0f): Boolean {
        val toPage = pointsToPageMm(page)
        return rects.any { toPage.mapRect(it).inflate(toleranceMm).contains(pointMm) }
    }
}

/**
 * Transformation from the points of a [pdfWidthPt] x [pdfHeightPt] PDF page to the millimeters of
 * a [widthMm] x [heightMm] page showing it.
 */
fun pdfPointsToPageMm(pdfWidthPt: Float, pdfHeightPt: Float, widthMm: Float, heightMm: Float): Matrix2D {
    val target = fitPdfPage(pdfWidthPt.toDouble(), pdfHeightPt.toDouble(), Rect(0f, 0f, widthMm, heightMm))
    return Matrix2D().setRectToRect(Rect(0f, 0f, pdfWidthPt, pdfHeightPt), target)
}
