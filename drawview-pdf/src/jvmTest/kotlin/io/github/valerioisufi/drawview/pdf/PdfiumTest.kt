package io.github.valerioisufi.drawview.pdf

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
