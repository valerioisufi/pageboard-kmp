package io.github.valerioisufi.pageboard.pdf

import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

class PdfWriterTest {

    @Test
    fun pathsAreFilledAndStrokedWhereThePageDrawsThem() {
        val pdf = write(200f, 100f) {
            fillPath(PdfPath().apply { addPolygon(floatArrayOf(10f, 10f, 30f, 10f, 30f, 30f, 10f, 30f)) }, RED)
            strokePath(PdfPath().apply { moveTo(0f, 80f); lineTo(200f, 80f) }, BLUE, 4f)
            fillPath(PdfPath().apply { addCircle(150f, 30f, 10f) }, GREEN)
        }
        val page = render(pdf)
        assertEquals(200, page.width)
        assertEquals(100, page.height)
        assertColor(RED, page.at(20, 20))
        assertColor(BLUE, page.at(100, 80))
        assertColor(GREEN, page.at(150, 30))
        assertColor(WHITE, page.at(150, 50))
        assertColor(WHITE, page.at(5, 5))
    }

    @Test
    fun overlappingPolygonsOfATranslucentPathAreFilledOnce() {
        val halfRed = (0x80 shl 24) or (RED and 0xFFFFFF)
        val pdf = write(100f, 100f) {
            fillPath(
                PdfPath().apply {
                    addPolygon(floatArrayOf(10f, 10f, 60f, 10f, 60f, 60f, 10f, 60f))
                    // Turning the same way
                    addPolygon(floatArrayOf(40f, 40f, 90f, 40f, 90f, 90f, 40f, 90f))
                },
                halfRed
            )
        }
        val page = render(pdf)
        assertColor(page.at(20, 20), page.at(50, 50))
        assertColor(page.at(20, 20), page.at(80, 80))
        assertTrue(page.at(20, 20) != WHITE && page.at(20, 20) != RED)
    }

    @Test
    fun imagesKeepTheirPixelsAndTheirTransparency() {
        // Red, transparent / blue, green: drawn on 20 x 20 points each
        val pixels = intArrayOf(RED, 0x00000000, BLUE, GREEN)
        val jpeg = Bitmap().run {
            allocPixels(ImageInfo.makeN32(8, 8, ColorAlphaType.OPAQUE))
            erase(BLUE)
            Image.makeFromBitmap(this).encodeToData(EncodedImageFormat.JPEG, 95)!!.bytes
        }
        val pdf = write(100f, 100f) {
            assertTrue(drawImage(pixels, 2, 2, Matrix2D().postScale(40f, 40f).postTranslate(10f, 10f)))
            assertTrue(drawJpeg(jpeg, Matrix2D().postScale(20f, 20f).postTranslate(70f, 70f)))
            assertFalse(drawJpeg(byteArrayOf(1, 2, 3), Matrix2D()))
        }
        val bytes = pdf.readBytes().decodeToString()
        assertTrue("/DCTDecode" in bytes, "the JPEG is embedded as it is")
        val page = render(pdf)
        assertColor(RED, page.at(20, 20))
        assertColor(WHITE, page.at(40, 20))
        assertColor(BLUE, page.at(20, 40))
        assertColor(GREEN, page.at(40, 40))
        assertColor(BLUE, page.at(80, 80), tolerance = 24)
    }

    @Test
    fun pagesOfAnotherPdfAreCopiedAsTheyAreDisplayed() {
        for (rotation in listOf("", "/Rotate 90", "/Rotate 270 /CropBox [20 10 300 200]")) {
            val source = tempFile("source", ".pdf").apply { writeText(SOURCE_PDF.replace("/MediaBox [0 0 300 200]", "/MediaBox [0 0 300 200] $rotation")) }
            val expected = PdfiumEngine.open(source.path).use { document ->
                val size = document.getPageSize(0)
                Page(size.widthPt.roundToInt(), size.heightPt.roundToInt()) { w, h, out -> document.renderPage(0, w, h)!!.readPixels(out) }
            }
            // Drawn 20 points from the corner of a larger page
            val pdf = write(expected.width + 40f, expected.height + 40f) {
                assertTrue(drawPdfPage(source.path, 0, Matrix2D().postTranslate(20f, 20f)))
                assertFalse(drawPdfPage(source.path + ".missing", 0, Matrix2D()))
            }
            val page = render(pdf)
            var differences = 0
            for (y in 0 until expected.height) for (x in 0 until expected.width) {
                if (!sameColor(expected.at(x, y), page.at(x + 20, y + 20), 64)) differences++
            }
            assertTrue(differences < expected.width * expected.height / 200, "$rotation: $differences pixels differ")
            assertTrue(expected.count { it != WHITE } > 500, "the source page has ink")
            // Its text is kept
            PdfiumEngine.open(pdf.path).use { assertEquals("Hash functions", it.getPageText(0)?.trim()) }
        }
    }

    @Test
    fun importedPagesShareTheirPicturesAndKeepTheirLinks() {
        val source = sharedSource()
        val expected = PdfiumEngine.open(source.path).use { document -> (0 until 5).map { render(document, it) } }
        val pdf = tempFile("imported", ".pdf")
        PdfiumEngine.create().use { writer ->
            val pages = assertNotNull(writer.importPdfPages(source.path, (0 until 5).toList()))
            assertNull(writer.importPdfPages(source.path + ".missing", listOf(0)))
            for (page in pages) {
                writer.beginPage(page)
                // Over the page, at its top-left corner as it is displayed
                writer.fillPath(PdfPath().apply { addPolygon(floatArrayOf(10f, 10f, 30f, 10f, 30f, 30f, 10f, 30f)) }, RED)
            }
            writer.save(pdf.path)
        }
        // The picture is written once (about 200 KB), not once per page
        assertTrue(pdf.length() < PICTURE_BYTES * 3 / 2, "${pdf.length()} bytes")
        val copied = tempFile("copied", ".pdf")
        PdfiumEngine.create().use { writer ->
            for (index in 0 until 5) {
                val size = assertNotNull(writer.pdfPageSize(source.path, index))
                writer.beginPage(size.widthPt.toFloat(), size.heightPt.toFloat())
                assertTrue(writer.drawPdfPage(source.path, index, Matrix2D()))
            }
            writer.save(copied.path)
        }
        assertTrue(copied.length() > PICTURE_BYTES * 4, "copied for every page: ${copied.length()} bytes")

        PdfiumEngine.open(pdf.path).use { document ->
            assertEquals(5, document.pageCount)
            for (index in 0 until 5) {
                val page = render(document, index)
                val original = expected[index]
                assertEquals(original.width to original.height, page.width to page.height, "page $index as it is displayed")
                assertColor(RED, page.at(20, 20))
                var differences = 0
                for (y in 0 until page.height) for (x in 0 until page.width) {
                    if ((x > 32 || y > 32) && !sameColor(original.at(x, y), page.at(x, y), 8)) differences++
                }
                assertEquals(0, differences, "page $index as the original")
                assertEquals("Page $index", document.getPageText(index)?.trim())
            }
        }
        val bytes = pdf.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertEquals(1, Regex(Regex.escape(LINK)).findAll(bytes).count(), "the link of the first page, once")
        assertTrue(Regex("/Subtype\\s*/Link").containsMatchIn(bytes))
    }

    @Test
    fun importedPagesAreWhereTheyAreBegunAndTheOthersAreLeftOut() {
        val source = sharedSource()
        val pdf = tempFile("order", ".pdf")
        PdfiumEngine.create().use { writer ->
            writer.beginPage(100f, 100f)
            val (third, first) = assertNotNull(writer.importPdfPages(source.path, listOf(3, 1)))
            assertNotNull(writer.importPdfPages(source.path, listOf(4)))
            writer.beginPage(first)
            writer.beginPage(100f, 100f)
            writer.beginPage(third)
            writer.save(pdf.path)
        }
        PdfiumEngine.open(pdf.path).use { document ->
            assertEquals(4, document.pageCount)
            assertEquals(listOf("", "Page 1", "", "Page 3"), (0 until 4).map { document.getPageText(it)?.trim() })
            // Turned a quarter: 200 x 300 points
            assertEquals(200.0, document.getPageSize(1).widthPt, 0.5)
        }
    }

    @Test
    fun marksAreAnnotationsWithTheirAppearance() {
        val pdf = write(200f, 100f) {
            assertTrue(addMarkup(PdfMarkupKind.HIGHLIGHT, listOf(Rect(10f, 10f, 90f, 30f), Rect(10f, 40f, 50f, 60f)), YELLOW))
            assertTrue(addMarkup(PdfMarkupKind.UNDERLINE, listOf(Rect(110f, 10f, 190f, 30f)), BLUE))
        }
        val bytes = pdf.readBytes().decodeToString()
        assertTrue("/Highlight" in bytes && "/Underline" in bytes)
        assertTrue("/AP" in bytes, "written with their appearance")
        val page = render(pdf)
        assertColor(YELLOW, page.at(50, 20), tolerance = 8)
        assertColor(YELLOW, page.at(30, 50), tolerance = 8)
        assertColor(WHITE, page.at(70, 50))
        assertTrue((10 until 40).any { page.at(150, it) != WHITE }, "the underline is drawn")
    }

    @Test
    fun aMarkIsDrawnAsItIsGiven() {
        val pdf = write(200f, 100f) {
            // A strikeout drawn through the upper part of its line
            assertTrue(addMarkup(PdfMarkupKind.STRIKEOUT, listOf(Rect(10f, 10f, 190f, 50f)), BLUE, appearance = listOf(Rect(10f, 18f, 190f, 22f))))
        }
        val bytes = pdf.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertTrue("/StrikeOut" in bytes)
        // With resources: PDFKit draws an appearance without them in black
        assertTrue(Regex("/BBox[^>]*/Resources").containsMatchIn(bytes))
        val page = render(pdf)
        assertColor(BLUE, page.at(100, 20))
        assertColor(WHITE, page.at(100, 30))
    }

    @Test
    fun theTitleAndTheApplicationAreWritten() {
        val pdf = tempFile("info", ".pdf")
        PdfiumEngine.create().use { writer ->
            writer.beginPage(100f, 100f)
            writer.save(pdf.path, PdfInfo(title = "Lezione 3: σ", creator = "PencilNotes"))
        }
        val bytes = pdf.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertTrue("/Info" in bytes && "/Title <FEFF004C0065007A0069006F006E006500200033003A002003C3>" in bytes, bytes.takeLast(400))
        assertTrue("/Creator <FEFF00500065006E00630069006C004E006F007400650073>" in bytes)
        // Still read as a whole
        PdfiumEngine.open(pdf.path).use { assertEquals(1, it.pageCount) }
    }

    @Test
    fun theDatesAreWritten() {
        val pdf = tempFile("dates", ".pdf")
        PdfiumEngine.create().use { writer ->
            writer.beginPage(100f, 100f)
            writer.save(pdf.path, PdfInfo(title = "Dated", createdAt = 1_709_208_000_000L, modifiedAt = 1_773_500_967_123L))
        }
        val bytes = pdf.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertTrue("/CreationDate (D:20240229120000Z)" in bytes, bytes.takeLast(400))
        assertTrue("/ModDate (D:20260314150927Z)" in bytes, bytes.takeLast(400))
        PdfiumEngine.open(pdf.path).use { assertEquals(1, it.pageCount) }
    }

    @Test
    fun datesAreWrittenInUtc() {
        assertEquals("(D:19700101000000Z)", pdfDate(0L))
        assertEquals("(D:19691231235959Z)", pdfDate(-1L))
        assertEquals("(D:20000301000000Z)", pdfDate(951_868_800_000L))
        assertEquals("(D:21071231235958Z)", pdfDate(4_354_819_198_000L))
        assertEquals("(D:99991231235959Z)", pdfDate(Long.MAX_VALUE / 2))
        // Every second of four centuries, as java.time tells them
        val format = java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(java.time.ZoneOffset.UTC)
        var ms = -2_208_988_800_000L // 1900-01-01
        while (ms < 10_413_792_000_000L) { // 2300-01-01
            assertEquals("(D:${format.format(java.time.Instant.ofEpochMilli(ms))}Z)", pdfDate(ms), "$ms")
            ms += 86_399_999L * 7 + 13_000L
        }
    }

    @Test
    fun numbersAreWrittenAsDecimals() {
        assertEquals(listOf("1.5", "0", "12", "0.123", "-2.25", "100000"), listOf(1.5f, -0.0004f, 12f, 0.1234f, -2.25f, 1e5f).map(::pdfNumber))
    }

    @Test
    fun theCharactersOfAFontAreReadWithTheirOutlines() {
        val font = TrueTypeFont(NOTO_SANS)
        assertTrue(font.unitsPerEm > 0)
        assertTrue(font.covers('A'.code) && font.covers('é'.code) && font.covers('Ω'.code))
        assertFalse(font.covers('中'.code))
        // An "o" is two contours, a space none
        assertEquals(2, font.outline(font.glyphId('o'.code)).size)
        assertEquals(0, font.outline(font.glyphId(' '.code)).size)
        // "é" is composite: the "e" and the accent
        assertEquals(font.outline(font.glyphId('e'.code)).size + 1, font.outline(font.glyphId('é'.code)).size)
        assertTrue(font.advance("mm") > font.advance("ii"))
    }

    @Test
    fun aSubsetKeepsTheGlyphsOfItsCharactersOnly() {
        val font = TrueTypeFont(NOTO_SANS)
        val subset = TrueTypeFont(font.subset("Perché Ωμ".codePointSet()))
        assertTrue(NOTO_SANS.size > 0, "font read")
        for (ch in "Perché Ωμ") {
            assertEquals(font.glyphId(ch.code), subset.glyphId(ch.code))
            assertEquals(font.advance(font.glyphId(ch.code)), subset.advance(subset.glyphId(ch.code)))
        }
        assertFalse(subset.covers('z'.code))
        // The glyphs of the components of "é" are kept with it
        assertEquals(font.outline(font.glyphId('é'.code)).size, subset.outline(subset.glyphId('é'.code)).size)
        val size = font.subset("Perché Ωμ".codePointSet()).size
        assertTrue(size < NOTO_SANS.size / 5, "subset of $size bytes")
    }

    @Test
    fun textIsWrittenInAnEmbeddedFont() {
        val text = "Perché Ωμ ok"
        val subset = TrueTypeFont(NOTO_SANS).subset(text.codePointSet())
        val pdf = write(300f, 100f) {
            val font = assertNotNull(loadFont(subset))
            assertTrue(addText(font, text, 20f, Matrix2D().postTranslate(20f, 40f)))
            // Stretched to 200 points, and invisible
            assertTrue(addText(font, text, 20f, Matrix2D().postTranslate(20f, 80f), width = 200f, invisible = true))
            assertNull(loadFont(byteArrayOf(1, 2, 3)))
        }
        PdfiumEngine.open(pdf.path).use { document ->
            val read = document.getPageText(0).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
            assertEquals(listOf(text, text), read)
            // The invisible text spans the width it was given
            val layout = assertNotNull(document.getPageTextLayout(0))
            val second = (0 until layout.charCount).filter { layout.hasBox(it) }.map { layout.boxOf(it) }.filter { it.top > 50f }
            assertEquals(220f, second.maxOf { it.right }, 4f)
        }
        val page = render(pdf)
        val ink = (10 until 50).sumOf { y -> (0 until 300).count { x -> page.at(x, y) != WHITE } }
        assertTrue(ink > 300, "the text is drawn")
        assertEquals(0, (60 until 100).sumOf { y -> (0 until 300).count { x -> page.at(x, y) != WHITE } }, "the invisible text is not")
    }

    // ==========================================================

    private fun write(widthPt: Float, heightPt: Float, content: PdfWriter.() -> Unit): File {
        val file = tempFile("written", ".pdf")
        PdfiumEngine.create().use { writer ->
            writer.beginPage(widthPt, heightPt)
            writer.content()
            writer.save(file.path)
        }
        return file
    }

    private fun tempFile(prefix: String, suffix: String) = File.createTempFile(prefix, suffix).apply { deleteOnExit() }

    /** The first page of [pdf] at one pixel per point. */
    private fun render(pdf: File): Page = PdfiumEngine.open(pdf.path).use { render(it, 0) }

    /** Page [index] of [document] at one pixel per point. */
    private fun render(document: PdfDocument, index: Int): Page {
        val size = document.getPageSize(index)
        return Page(size.widthPt.roundToInt(), size.heightPt.roundToInt()) { w, h, out -> document.renderPage(index, w, h)!!.readPixels(out) }
    }

    /**
     * A PDF of five 300 x 200 pages showing the same picture ([PICTURE_BYTES]) and "Page <n>",
     * displayed as [PAGE_BOXES] say; the first with a link to [LINK].
     */
    private fun sharedSource(): File {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
        fun obj(body: String) {
            offsets += out.size()
            write("${offsets.size} 0 obj $body endobj\n")
        }
        write("%PDF-1.4\n")
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [${(0 until 5).joinToString(" ") { "${6 + it * 2} 0 R" }}] /Count 5 >>")
        // Not compressible: written as it is
        val pixels = Random(7).nextBytes(PICTURE_BYTES)
        offsets += out.size()
        write("3 0 obj << /Type /XObject /Subtype /Image /Width 260 /Height 260 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Length ${pixels.size} >>\nstream\n")
        out.write(pixels)
        write("\nendstream endobj\n")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        obj("<< /Type /Annot /Subtype /Link /Rect [40 140 200 170] /Border [0 0 0] /A << /S /URI /URI ($LINK) >> >>")
        for (index in 0 until 5) {
            val content = "q 100 0 0 100 150 40 cm /Im Do Q 0 0 1 rg 260 160 30 30 re f BT /F1 24 Tf 40 150 Td (Page $index) Tj ET"
            val annots = if (index == 0) " /Annots [5 0 R]" else ""
            obj(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] ${PAGE_BOXES[index]} /Contents ${7 + index * 2} 0 R " +
                    "/Resources << /XObject << /Im 3 0 R >> /Font << /F1 4 0 R >> >>$annots >>"
            )
            obj("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        }
        val xref = out.size()
        write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) write("${offset.toString().padStart(10, '0')} 00000 n \n")
        write("trailer << /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return tempFile("shared", ".pdf").apply { writeBytes(out.toByteArray()) }
    }

    private class Page(val width: Int, val height: Int, read: (Int, Int, IntArray) -> Unit) {
        val pixels = IntArray(width * height).also { read(width, height, it) }
        fun at(x: Int, y: Int) = pixels[y * width + x] or (0xFF shl 24)
        fun count(predicate: (Int) -> Boolean) = pixels.count { predicate(it or (0xFF shl 24)) }
    }

    private fun sameColor(a: Int, b: Int, tolerance: Int): Boolean =
        (0..2).all { abs(((a shr (it * 8)) and 0xFF) - ((b shr (it * 8)) and 0xFF)) <= tolerance }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 2) {
        assertTrue(sameColor(expected, actual, tolerance), "expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}")
    }

    private fun String.codePointSet(): Set<Int> = map { it.code }.toSet()

    private companion object {
        val NOTO_SANS = File("../pageboard-ui/src/commonMain/composeResources/font/noto_sans_regular.ttf").readBytes()

        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val YELLOW = 0xFFFFF176.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        /** The picture of the pages of [sharedSource]: 260 x 260 RGB pixels. */
        const val PICTURE_BYTES = 260 * 260 * 3
        const val LINK = "https://example.com/notes"

        /** How the pages of [sharedSource] are displayed: turned, cropped. */
        val PAGE_BOXES = listOf("", "/Rotate 90", "/Rotate 180", "/Rotate 270 /CropBox [20 10 290 190]", "/CropBox [10 0 300 190]")

        /** A 300 x 200 page with a black square in its bottom-left corner and "Hash functions". */
        val SOURCE_PDF = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
            2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj
            4 0 obj << /Length 80 >>
            stream
            30 20 60 40 re f BT /F1 24 Tf 40 150 Td (Hash functions) Tj ET
            endstream
            endobj
            5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj
            trailer << /Root 1 0 R >>
            %%EOF
        """.trimIndent()
    }
}
