package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.ElementOrder
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.geometry.fitPdfPage
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/**
 * How a passage of the text of a PDF is marked.
 */
public enum class TextMarkupKind {
    HIGHLIGHT,
    UNDERLINE,
    STRIKEOUT;

    public companion object {
        public fun fromName(name: String?): TextMarkupKind? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A highlight, underline or strikeout of a passage of the text of a PDF page shown on a page
 * (see [PdfElement]).
 *
 * The passage is the characters from [startIndex] until [endIndex] (exclusive) of the text layer
 * of page [pdfPageIndex] of the PDF [resourceId] ([text] is their text). Its [rects], one per
 * line, are in points of that PDF page, which is [pdfWidthPt] x [pdfHeightPt]: they follow the
 * PDF where the page draws it (fitted in the page, see [fitPdfPage]), even when the format of
 * the page changes. [id] identifies the mark on every device (see [newElementId]).
 */
public class TextMarkupElement(
    override val id: String = newElementId(),
    public val resourceId: String,
    public val pdfPageIndex: Int,
    public val pdfWidthPt: Float,
    public val pdfHeightPt: Float,
    public val startIndex: Int,
    public val endIndex: Int,
    public val text: String,
    public val rects: List<Rect>,
    kind: TextMarkupKind,
    color: Int
) : PageElement {
    override var order: ElementOrder = ElementOrder.UNASSIGNED
        set(value) {
            field = value.inLayer(ElementOrder.MARKUP_LAYER)
        }
    override var added: Stamp = Stamp.ZERO
    override var removed: Stamp? = null
    override var placed: Stamp = Stamp.ZERO
    override var ordered: Stamp = Stamp.ZERO
    override var updated: Stamp = Stamp.ZERO

    @Volatile
    public var kind: TextMarkupKind = kind

    /** ARGB color. */
    @Volatile
    public var color: Int = color

    /** Transformation from the points of the PDF page to the millimeters of [page]. */
    public fun pointsToPageMm(page: Page): Matrix2D = pointsToPageMm(page.widthMm, page.heightMm)

    /** Transformation from the points of the PDF page to the millimeters of a [widthMm] x [heightMm] page. */
    public fun pointsToPageMm(widthMm: Float, heightMm: Float): Matrix2D =
        pdfPointsToPageMm(pdfWidthPt, pdfHeightPt, widthMm, heightMm)

    /** Bounds of the lines in points of the PDF page. */
    public val boundsPoints: Rect = rects.reduceOrNull { a, b ->
        Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
    } ?: Rect.Zero

    /** Bounds of the lines in the millimeters of a [widthMm] x [heightMm] page. */
    public fun boundsMm(widthMm: Float, heightMm: Float): Rect = pointsToPageMm(widthMm, heightMm).mapRect(boundsPoints)

    public fun boundsMm(page: Page): Rect = boundsMm(page.widthMm, page.heightMm)

    /** Whether [pointMm] (millimeters of [page]) is on a line of the passage, or at most [toleranceMm] away. */
    public fun containsPoint(page: Page, pointMm: Point, toleranceMm: Float = 0f): Boolean {
        val toPage = pointsToPageMm(page)
        return rects.any { toPage.mapRect(it).inflate(toleranceMm).contains(pointMm) }
    }
}

/**
 * Transformation from the points of a [pdfWidthPt] x [pdfHeightPt] PDF page to the millimeters of
 * a [widthMm] x [heightMm] page showing it.
 */
public fun pdfPointsToPageMm(pdfWidthPt: Float, pdfHeightPt: Float, widthMm: Float, heightMm: Float): Matrix2D {
    val target = fitPdfPage(pdfWidthPt.toDouble(), pdfHeightPt.toDouble(), Rect(0f, 0f, widthMm, heightMm))
    return Matrix2D().setRectToRect(Rect(0f, 0f, pdfWidthPt, pdfHeightPt), target)
}
