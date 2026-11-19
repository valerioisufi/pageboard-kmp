package io.github.valerioisufi.pageboard.pdf

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** One A4 page with a 100x100 pt black square in its bottom-left corner. */
internal val SAMPLE_PDF = """
%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>
endobj
4 0 obj
<< /Length 20 >>
stream
q
0 0 100 100 re
f
Q
endstream
endobj
xref
0 5
0000000000 65535 f 
0000000009 00000 n 
0000000058 00000 n 
0000000115 00000 n 
0000000204 00000 n 
trailer
<< /Size 5 /Root 1 0 R >>
startxref
275
%%EOF
""".trimIndent()

/**
 * Opens and renders [pdfPath] (a file containing [SAMPLE_PDF]) through [PdfiumEngine]: shared by
 * the platform tests, which only differ in how they write the file.
 */
internal fun runPdfiumSmokeTest(pdfPath: String) {
    assertTrue(PdfiumEngine.isAvailable(), "PDFium should be available on this platform")

    val doc = PdfiumEngine.open(pdfPath)
    try {
        assertEquals(1, doc.pageCount)

        val size = doc.getPageSize(0)
        assertEquals(595.0, size.widthPt)
        assertEquals(842.0, size.heightPt)
        assertTrue(size.widthMm > 200f) // A4 width is ~210 mm
        assertTrue(size.heightMm > 290f) // A4 height is ~297 mm

        // Page rasterization
        val pageBmp = assertNotNull(doc.renderPage(0, 300, 424))
        assertEquals(300, pageBmp.width)
        assertEquals(424, pageBmp.height)

        // Slice rasterization
        val sliceBmp = assertNotNull(doc.renderPageSlice(0, 0, 0, 150, 200, 150, 200))
        assertEquals(150, sliceBmp.width)
        assertEquals(200, sliceBmp.height)

        // Tile adapter: a slice of the page rendered into a tile, with the black square drawn
        // by the sample content (bottom-left 100x100 pt) in the expected position
        val adapter = assertNotNull(PdfiumEngine.createTileAdapter())
        val sizes = adapter.getPageSizesMm(pdfPath)
        assertEquals(1, sizes.size)
        assertEquals(209.9f, sizes[0].first, 0.2f)

        val tileBmp = ImageBitmap(512, 512)
        val canvas = Canvas(tileBmp)
        // Page scaled to 595x842 px (1 px per pt), tile showing its bottom-left corner
        adapter.renderPdfPage(canvas, pdfPath, 0, Rect(0f, -330f, 595f, 512f), Rect(0f, 0f, 512f, 512f))
        val pixels = IntArray(512 * 512)
        tileBmp.readPixels(pixels)
        val inside = pixels[(512 - 50) * 512 + 50]   // inside the square
        val outside = pixels[(512 - 200) * 512 + 300] // white paper
        assertEquals(0xFF000000.toInt(), inside)
        assertEquals(0xFFFFFFFF.toInt(), outside)
        adapter.close()
    } finally {
        doc.close()
    }
}
