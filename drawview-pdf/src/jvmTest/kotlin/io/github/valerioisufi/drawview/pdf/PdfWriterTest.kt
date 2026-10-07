package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    private fun render(pdf: File): Page = PdfiumEngine.open(pdf.path).use { document ->
        val size = document.getPageSize(0)
        Page(size.widthPt.roundToInt(), size.heightPt.roundToInt()) { w, h, out -> document.renderPage(0, w, h)!!.readPixels(out) }
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
        val NOTO_SANS = File("../drawview-ui/src/commonMain/composeResources/font/noto_sans_regular.ttf").readBytes()

        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val YELLOW = 0xFFFFF176.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

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
