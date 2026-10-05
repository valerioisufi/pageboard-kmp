package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.geometry.Offset
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfiumTest {
    @Test
    fun testPdfiumEngineOpenAndRender() {
        val tempPdf = File.createTempFile("test_sample", ".pdf")
        tempPdf.deleteOnExit()
        tempPdf.writeText(SAMPLE_PDF)
        runPdfiumSmokeTest(tempPdf.absolutePath)
    }

    @Test
    fun readsTheTextOfAPage() {
        val textPdf = File.createTempFile("text_sample", ".pdf").apply {
            deleteOnExit()
            writeText(TEXT_PDF)
        }
        PdfiumEngine.open(textPdf.absolutePath).use { document ->
            assertEquals("Hash functions", document.getPageText(0)?.trim())
        }
        val adapter = assertNotNull(PdfiumEngine.createTileAdapter())
        assertEquals("Hash functions", adapter.getPageText(textPdf.absolutePath, 0)?.trim())
        assertNull(adapter.getPageText(textPdf.absolutePath, 3))
        // A page without a text layer
        val shapes = File.createTempFile("test_sample", ".pdf").apply {
            deleteOnExit()
            writeText(SAMPLE_PDF)
        }
        assertEquals("", adapter.getPageText(shapes.absolutePath, 0))
        adapter.close()
    }

    @Test
    fun boxesOfTheCharactersCoverTheRenderedGlyphs() {
        for (source in listOf(TEXT_PDF, TEXT_PDF.replace("/MediaBox [0 0 595 842]", "/MediaBox [0 0 595 842] /Rotate 90"))) {
            val pdf = File.createTempFile("text_layout", ".pdf").apply {
                deleteOnExit()
                writeText(source)
            }
            PdfiumEngine.open(pdf.absolutePath).use { document ->
                val layout = assertNotNull(document.getPageTextLayout(0))
                assertEquals("Hash functions", layout.text(0, layout.charCount).trim())
                val size = document.getPageSize(0)
                assertEquals(size.widthPt.toFloat(), layout.widthPt)
                assertEquals(size.heightPt.toFloat(), layout.heightPt)

                // The page rendered at one pixel per point, like the boxes
                val width = size.widthPt.roundToInt()
                val height = size.heightPt.roundToInt()
                val pixels = IntArray(width * height)
                assertNotNull(document.renderPage(0, width, height)).readPixels(pixels)
                fun isInk(x: Int, y: Int): Boolean {
                    val p = pixels[y * width + x]
                    return ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF) < 384
                }
                val ink = (0 until height).sumOf { y -> (0 until width).count { x -> isInk(x, y) } }
                assertTrue(ink > 100, "the text is rendered")

                // Every pixel of ink is in the box of a character (one pixel of tolerance)
                val boxes = (0 until layout.charCount).filter { layout.hasBox(it) }.map { layout.boxOf(it).inflate(1f) }
                val covered = (0 until height).sumOf { y ->
                    (0 until width).count { x -> isInk(x, y) && boxes.any { it.contains(Offset(x + 0.5f, y + 0.5f)) } }
                }
                assertEquals(ink, covered)
                // ... and the "H" has ink
                val h = layout.boxOf(0)
                val inH = (h.top.toInt() until h.bottom.toInt()).sumOf { y -> (h.left.toInt() until h.right.toInt()).count { x -> isInk(x, y) } }
                assertTrue(inH > 10, "ink in the box of the H")
            }
        }
    }

    private companion object {
        /** One A4 page with "Hash functions" in Helvetica (PDFium rebuilds the missing cross-reference table). */
        val TEXT_PDF = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
            2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj
            4 0 obj << /Length 45 >>
            stream
            BT /F1 24 Tf 72 720 Td (Hash functions) Tj ET
            endstream
            endobj
            5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj
            trailer << /Root 1 0 R >>
            %%EOF
        """.trimIndent()
    }
}
