package io.github.valerioisufi.drawview.pdf

import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A new PDF document written page by page (see [PdfiumEngine.create]). Every coordinate is in
 * points from the top-left corner of the page being written, y going down; a color is ARGB.
 *
 * The elements are drawn in the order they are added; the `draw` and `add` functions return
 * `false` when the element cannot be written (an unreadable PDF or image), the others go on.
 * Not thread-safe.
 */
public interface PdfWriter : AutoCloseable {
    /** Starts a page of [widthPt] x [heightPt] after the others, finishing the previous one. */
    public fun beginPage(widthPt: Float, heightPt: Float)

    /**
     * Starts a page after the others on [base], a page of another PDF imported by
     * [importPdfPages], finishing the previous one: the page is [base] as it is displayed (its
     * size, its rotation, its content and its links as they are), and what is drawn goes over it.
     */
    public fun beginPage(base: PdfImportedPage)

    /**
     * Imports the pages [pageIndices] (distinct) of the PDF file [sourcePath] at once, for pages
     * begun on them ([beginPage]): the fonts and the images they share are written once, where
     * [drawPdfPage] copies them for every page. Their annotations become part of them, but their
     * links to the web, which stay links. Returns them in the order of [pageIndices], or `null`
     * when they cannot be imported; those no page is begun on are left out of the document.
     */
    public fun importPdfPages(sourcePath: String, pageIndices: List<Int>): List<PdfImportedPage>?

    /** Fills [path] in [argb] (the non-zero winding rule: overlapping parts are filled once). */
    public fun fillPath(path: PdfPath, argb: Int): Boolean

    /** Strokes [path] [widthPt] wide in [argb]. */
    public fun strokePath(path: PdfPath, argb: Int, widthPt: Float): Boolean

    /**
     * Draws page [pageIndex] of the PDF file [sourcePath] as it is displayed (rotated, cropped,
     * with its annotations), keeping its vectors and its text: [transform] maps its points (from
     * its top-left corner) to the page.
     */
    public fun drawPdfPage(sourcePath: String, pageIndex: Int, transform: Matrix2D): Boolean

    /** Size of page [pageIndex] of the PDF file [sourcePath] as it is displayed, or `null` when it cannot be read. */
    public fun pdfPageSize(sourcePath: String, pageIndex: Int): PdfSize?

    /** Draws the JPEG [data], embedded as it is: [transform] maps the unit square of the image (its top-left corner at the origin) to the page. */
    public fun drawJpeg(data: ByteArray, transform: Matrix2D): Boolean

    /**
     * Draws [width] x [height] ARGB [pixels] (unpremultiplied, top row first; the transparent ones
     * stay transparent): [transform] maps the unit square of the image (its top-left corner at the
     * origin) to the page.
     */
    public fun drawImage(pixels: IntArray, width: Int, height: Int, transform: Matrix2D): Boolean

    /**
     * Marks the passage of text on [lines] as [kind] in [argb]: an annotation the readers know,
     * drawn by filling [appearance] (or as PDFium draws it when `null`).
     */
    public fun addMarkup(kind: PdfMarkupKind, lines: List<Rect>, argb: Int, appearance: List<Rect>? = null): Boolean

    /**
     * Embeds the TrueType font [data] (typically a subset, see [TrueTypeFont.subset]): the text
     * written with it is searched and copied in every language it covers. `null` when it cannot be
     * read.
     */
    public fun loadFont(data: ByteArray): PdfFont?

    /**
     * Writes one line of [text] in [font], [fontSize] high, filled with [argb] or [invisible] (to
     * be searched and selected over a picture of it): [transform] maps the start of its baseline
     * (the origin, y going down) and the sizes to the page. With a [width], the text is stretched
     * or narrowed to it (where the layout that placed it ends it).
     */
    public fun addText(
        font: PdfFont,
        text: String,
        fontSize: Float,
        transform: Matrix2D,
        argb: Int = 0xFF000000.toInt(),
        width: Float = 0f,
        invisible: Boolean = false
    ): Boolean

    /** Finishes the last page and writes the document to [path], with its [info]. */
    public fun save(path: String, info: PdfInfo = PdfInfo())

    /** Releases the document (also when it was not saved). */
    override fun close()
}

/**
 * What a PDF tells of itself: its [title], the application that made it ([creator]), when its
 * content was created ([createdAt]) and last changed ([modifiedAt]), in milliseconds since the
 * epoch.
 */
public class PdfInfo(
    public val title: String? = null,
    public val creator: String? = null,
    public val createdAt: Long? = null,
    public val modifiedAt: Long? = null
)

/** A page of another PDF imported by [PdfWriter.importPdfPages], [widthPt] x [heightPt] as it is displayed. */
public class PdfImportedPage(public val widthPt: Float, public val heightPt: Float)

/** A font embedded by a [PdfWriter] (see [PdfWriter.loadFont]), with its [metrics]. */
public class PdfFont internal constructor(internal val handle: Long, public val metrics: TrueTypeFont)

/** How an annotation of a PDF marks a passage of text. */
public enum class PdfMarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT }

/**
 * Lines and cubic Bézier curves for [PdfWriter.fillPath] and [PdfWriter.strokePath], in points
 * from the top-left corner of the page.
 */
public class PdfPath {
    internal var verbs = ByteArray(16)
        private set
    internal var verbCount = 0
        private set
    internal var points = FloatArray(32)
        private set
    internal var pointCount = 0
        private set

    public val isEmpty: Boolean get() = verbCount == 0

    public fun moveTo(x: Float, y: Float): Unit = add(MOVE, x, y)

    public fun lineTo(x: Float, y: Float): Unit = add(LINE, x, y)

    public fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): Unit = add(CUBIC, x1, y1, x2, y2, x3, y3)

    public fun close(): Unit = add(CLOSE)

    /** Adds the closed polygon of the vertices [xy] (x, y pairs). */
    public fun addPolygon(xy: FloatArray) {
        if (xy.size < 6) return
        moveTo(xy[0], xy[1])
        var i = 2
        while (i + 1 < xy.size) {
            lineTo(xy[i], xy[i + 1])
            i += 2
        }
        close()
    }

    /** Adds the circle of center ([cx], [cy]) and [radius] (four Bézier curves). */
    public fun addCircle(cx: Float, cy: Float, radius: Float) {
        val k = radius * CIRCLE_KAPPA
        moveTo(cx + radius, cy)
        cubicTo(cx + radius, cy + k, cx + k, cy + radius, cx, cy + radius)
        cubicTo(cx - k, cy + radius, cx - radius, cy + k, cx - radius, cy)
        cubicTo(cx - radius, cy - k, cx - k, cy - radius, cx, cy - radius)
        cubicTo(cx + k, cy - radius, cx + radius, cy - k, cx + radius, cy)
        close()
    }

    private fun add(verb: Byte, vararg xy: Float) {
        if (verbCount == 0 && verb != MOVE) moveTo(0f, 0f)
        if (verbCount == verbs.size) verbs = verbs.copyOf(verbs.size * 2)
        verbs[verbCount++] = verb
        while (pointCount + xy.size > points.size) points = points.copyOf(points.size * 2)
        xy.copyInto(points, pointCount)
        pointCount += xy.size
    }

    internal companion object {
        const val MOVE: Byte = 0
        const val LINE: Byte = 1
        const val CUBIC: Byte = 2
        const val CLOSE: Byte = 3

        /** Distance of the control points of a quarter of a circle of radius 1. */
        private const val CIRCLE_KAPPA = 0.5522848f
    }
}

/**
 * [PdfWriter] of PDFium. The pages of another PDF are copied from documents opened once each,
 * closed with the writer. The pages imported wait at the end of the document, after the pages
 * begun, until a page is begun on them.
 */
internal class PdfiumWriter(private var document: Long) : PdfWriter {
    private var page = 0L
    private var begun = 0

    /** From the points of the page (from its top-left corner) to its user space. */
    private var toUser = Matrix2D()
    private val imported = mutableListOf<PdfImportedPage>()
    private val sources = mutableMapOf<String, Long>()
    private val fonts = mutableListOf<Long>()

    override fun beginPage(widthPt: Float, heightPt: Float) {
        endPage()
        check(document != 0L) { "The PDF has been closed" }
        page = PdfiumBindings.newPage(document, begun, widthPt.toDouble(), heightPt.toDouble())
        check(page != 0L) { "Cannot add a page of $widthPt x $heightPt points" }
        begun++
        toUser = Matrix2D(1f, 0f, 0f, -1f, 0f, heightPt)
    }

    override fun beginPage(base: PdfImportedPage) {
        endPage()
        check(document != 0L) { "The PDF has been closed" }
        val position = imported.indexOf(base)
        require(position >= 0) { "The page has not been imported, or a page has been begun on it" }
        val matrix = DoubleArray(6)
        page = PdfiumBindings.placePage(document, begun + position, begun, matrix)
        check(page != 0L) { "Cannot write on an imported page" }
        imported.removeAt(position)
        begun++
        toUser = Matrix2D(matrix[0].toFloat(), matrix[1].toFloat(), matrix[2].toFloat(), matrix[3].toFloat(), matrix[4].toFloat(), matrix[5].toFloat())
    }

    override fun importPdfPages(sourcePath: String, pageIndices: List<Int>): List<PdfImportedPage>? {
        check(document != 0L) { "The PDF has been closed" }
        require(pageIndices.toSet().size == pageIndices.size) { "The pages are imported once" }
        if (pageIndices.isEmpty()) return emptyList()
        val source = source(sourcePath)
        if (source == 0L) return null
        val sizes = pageIndices.map { PdfiumBindings.pageSize(source, it) ?: return null }
        if (!PdfiumBindings.importPages(document, source, pageIndices.toIntArray())) return null
        return sizes.map { PdfImportedPage(it.widthPt.toFloat(), it.heightPt.toFloat()) }.also { imported += it }
    }

    override fun fillPath(path: PdfPath, argb: Int): Boolean = addPath(path, fillArgb = argb, strokeArgb = 0, strokeWidth = 0f)

    override fun strokePath(path: PdfPath, argb: Int, widthPt: Float): Boolean = addPath(path, fillArgb = 0, strokeArgb = argb, strokeWidth = widthPt)

    private fun addPath(path: PdfPath, fillArgb: Int, strokeArgb: Int, strokeWidth: Float): Boolean {
        if (path.isEmpty) return true
        val points = path.points.copyOf(path.pointCount)
        toUser.mapPoints(points)
        return PdfiumBindings.pageAddPath(currentPage(), path.verbs, path.verbCount, points, fillArgb, strokeArgb, strokeWidth)
    }

    override fun pdfPageSize(sourcePath: String, pageIndex: Int): PdfSize? {
        val source = source(sourcePath)
        return if (source != 0L) PdfiumBindings.pageSize(source, pageIndex) else null
    }

    override fun drawPdfPage(sourcePath: String, pageIndex: Int, transform: Matrix2D): Boolean {
        val page = currentPage()
        val source = source(sourcePath)
        if (source == 0L) return false
        val size = PdfiumBindings.pageSize(source, pageIndex) ?: return false
        // The page is copied with its points from its bottom-left corner, y going up
        val fromTop = Matrix2D(1f, 0f, 0f, -1f, 0f, size.heightPt.toFloat())
        return PdfiumBindings.pageAddPdfPage(document, page, source, pageIndex, toPdf(fromTop.postConcat(transform)))
    }

    override fun drawJpeg(data: ByteArray, transform: Matrix2D): Boolean {
        // PDFium embeds what it is given without telling whether it can be read
        if (jpegSize(data) == null) return false
        return PdfiumBindings.pageAddJpeg(document, currentPage(), data, toPdf(imageToTop().postConcat(transform)))
    }

    override fun drawImage(pixels: IntArray, width: Int, height: Int, transform: Matrix2D): Boolean =
        PdfiumBindings.pageAddBitmap(document, currentPage(), pixels, width, height, toPdf(imageToTop().postConcat(transform)))

    override fun addMarkup(kind: PdfMarkupKind, lines: List<Rect>, argb: Int, appearance: List<Rect>?): Boolean {
        if (lines.isEmpty()) return true
        val quads = FloatArray(lines.size * 8)
        lines.forEachIndexed { i, line ->
            // Upper-left, upper-right, lower-left and lower-right corners of the line
            floatArrayOf(line.left, line.top, line.right, line.top, line.left, line.bottom, line.right, line.bottom).copyInto(quads, i * 8)
        }
        toUser.mapPoints(quads)
        return PdfiumBindings.pageAddMarkup(currentPage(), kind.ordinal, quads, argb, appearance?.let { filling(it, argb) })
    }

    /** The content stream filling [areas] in [argb], in the user space of the page. */
    private fun filling(areas: List<Rect>, argb: Int): String = buildString {
        // A translucent one is so by the graphics state PDFium gives it (the opacity of the annotation)
        if (argb ushr 24 != 0xFF) append("/GS gs ")
        for (shift in intArrayOf(16, 8, 0)) append(pdfNumber(((argb shr shift) and 0xFF) / 255f)).append(' ')
        append("rg")
        for (area in areas) {
            val corners = floatArrayOf(area.left, area.top, area.right, area.top, area.right, area.bottom, area.left, area.bottom)
            toUser.mapPoints(corners)
            for (i in 0 until 4) {
                append(' ').append(pdfNumber(corners[i * 2])).append(' ').append(pdfNumber(corners[i * 2 + 1]))
                append(if (i == 0) " m" else " l")
            }
            append(" h f")
        }
    }

    override fun loadFont(data: ByteArray): PdfFont? {
        val metrics = try {
            TrueTypeFont(data)
        } catch (_: IllegalArgumentException) {
            return null
        }
        check(document != 0L) { "The PDF has been closed" }
        val handle = PdfiumBindings.loadFont(document, data)
        if (handle == 0L) return null
        fonts += handle
        return PdfFont(handle, metrics)
    }

    override fun addText(font: PdfFont, text: String, fontSize: Float, transform: Matrix2D, argb: Int, width: Float, invisible: Boolean): Boolean {
        if (text.isEmpty()) return true
        val natural = font.metrics.advance(text) * fontSize / font.metrics.unitsPerEm
        val stretch = if (width > 0f && natural > 0f) width / natural else 1f
        // The glyphs are drawn with y going up
        val glyphs = Matrix2D(stretch, 0f, 0f, -1f, 0f, 0f)
        return PdfiumBindings.pageAddText(document, currentPage(), font.handle, text, fontSize, argb, invisible, toPdf(glyphs.postConcat(transform)))
    }

    override fun save(path: String, info: PdfInfo) {
        endPage()
        check(document != 0L) { "The PDF has been closed" }
        // The pages imported that no page has been begun on
        repeat(imported.size) { PdfiumBindings.deletePage(document, begun) }
        imported.clear()
        val entries = listOfNotNull(
            info.title?.let { "/Title ${pdfText(it)}" },
            info.creator?.let { "/Creator ${pdfText(it)}" },
            info.createdAt?.let { "/CreationDate ${pdfDate(it)}" },
            info.modifiedAt?.let { "/ModDate ${pdfDate(it)}" }
        )
        check(PdfiumBindings.saveDocument(document, path, entries.joinToString(" ").ifEmpty { null })) { "Cannot write the PDF to $path" }
    }

    override fun close() {
        if (page != 0L) {
            PdfiumBindings.endPage(page)
            page = 0L
        }
        fonts.forEach(PdfiumBindings::closeFont)
        fonts.clear()
        sources.values.filter { it != 0L }.forEach(PdfiumBindings::closeDocument)
        sources.clear()
        if (document != 0L) {
            PdfiumBindings.closeDocument(document)
            document = 0L
        }
    }

    private fun endPage() {
        if (page == 0L) return
        val written = PdfiumBindings.endPage(page)
        page = 0L
        check(written) { "Cannot write the content of a page" }
    }

    /** The document of the PDF file [path], opened once (0 when it cannot be read). */
    private fun source(path: String): Long = sources.getOrPut(path) { PdfiumBindings.openDocument(path, null) }

    private fun currentPage(): Long {
        check(page != 0L) { "No page has been begun" }
        return page
    }

    /** The unit square of an image from its bottom-left corner (PDF) to its top-left corner. */
    private fun imageToTop() = Matrix2D(1f, 0f, 0f, -1f, 0f, 1f)

    /** [transform] (to the page from its top-left corner) followed by the page to its user space: a PDF matrix. */
    private fun toPdf(transform: Matrix2D): DoubleArray {
        val m = Matrix2D().set(transform).postConcat(toUser)
        return doubleArrayOf(m.a.toDouble(), m.b.toDouble(), m.c.toDouble(), m.d.toDouble(), m.tx.toDouble(), m.ty.toDouble())
    }
}

/** [value] as a number of PDF: a decimal of up to three digits after the point. */
internal fun pdfNumber(value: Float): String {
    val thousandths = (value * 1000f).roundToLong()
    val magnitude = abs(thousandths)
    val fraction = (magnitude % 1000).toString().padStart(3, '0').trimEnd('0')
    return (if (thousandths < 0) "-" else "") + (magnitude / 1000) + (if (fraction.isEmpty()) "" else ".$fraction")
}

/** [text] as a string of PDF: UTF-16BE in hexadecimal, after its byte order mark. */
internal fun pdfText(text: String): String = buildString {
    append("<FEFF")
    for (unit in text) append(unit.code.toString(16).uppercase().padStart(4, '0'))
    append('>')
}

/**
 * The instant [epochMs] as a date string of a PDF, in UTC: `(D:YYYYMMDDHHmmSSZ)`, the years held
 * within 0000-9999.
 */
internal fun pdfDate(epochMs: Long): String {
    val seconds = epochMs.floorDiv(1000L)
    val days = seconds.floorDiv(86_400L)
    val secondOfDay = seconds.mod(86_400L).toInt()
    // The civil date of the days since 1970-01-01 (H. Hinnant, days_from_civil reversed)
    val z = days + 719_468L
    val era = z.floorDiv(146_097L)
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    if (year < 0) return "(D:00000101000000Z)"
    if (year > 9999) return "(D:99991231235959Z)"
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "(D:${year.toString().padStart(4, '0')}${two(month)}${two(day)}" +
        "${two(secondOfDay / 3600)}${two(secondOfDay / 60 % 60)}${two(secondOfDay % 60)}Z)"
}

/**
 * Width and height of the JPEG [data], read from its frame header, or `null` when it is not a
 * JPEG.
 */
internal fun jpegSize(data: ByteArray): IntArray? {
    fun byte(i: Int) = data[i].toInt() and 0xFF
    if (data.size < 4 || byte(0) != 0xFF || byte(1) != 0xD8) return null
    var i = 2
    while (i + 3 < data.size) {
        if (byte(i) != 0xFF) return null
        val marker = byte(i + 1)
        when {
            // Fill byte
            marker == 0xFF -> i++
            // Markers without a segment
            marker == 0x01 || marker in 0xD0..0xD8 -> i += 2
            // End of the image, or its data, before a frame
            marker == 0xD9 || marker == 0xDA -> return null
            // Start of a frame (not the tables of Huffman and arithmetic coding)
            marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                if (i + 8 >= data.size) return null
                val height = (byte(i + 5) shl 8) or byte(i + 6)
                val width = (byte(i + 7) shl 8) or byte(i + 8)
                return if (width > 0 && height > 0) intArrayOf(width, height) else null
            }
            else -> {
                val length = (byte(i + 2) shl 8) or byte(i + 3)
                if (length < 2) return null
                i += 2 + length
            }
        }
    }
    return null
}
