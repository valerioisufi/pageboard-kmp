package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.canvas.render.tile.PdfTileAdapter
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The colors of the pages of the PDF of [runLoadedPagesTest], one each, more pages than the bridge keeps loaded. */
private val PAGE_COLORS = listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFFFF00FF, 0xFF00FFFF).map { it.toInt() }

private const val WHITE = 0xFFFFFFFF.toInt()

/**
 * The pages read kept loaded by the bridge (see `docs/plans/performance.md`, phase 8), each drawn as it
 * is: pages drawn again in another order, more of them than are kept, a document closed and opened
 * again, a document whose pages change (one deleted, one added before the others) while one of its
 * pages is kept. Shared by the platform tests, which only differ in where [pdfPath] is.
 */
internal fun runLoadedPagesTest(pdfPath: String) {
    PdfiumEngine.create().use { writer ->
        for (color in PAGE_COLORS) {
            writer.beginPage(100f, 100f)
            writer.fillPath(PdfPath().apply { addPolygon(floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)) }, color)
        }
        writer.save(pdfPath)
    }

    // Every page, then again in another order
    val adapter = assertNotNull(PdfiumEngine.createTileAdapter())
    for (index in listOf(0, 1, 2, 3, 4, 5, 0, 3, 5, 1, 1, 2)) {
        assertEquals(PAGE_COLORS[index], adapter.colorOf(pdfPath, index), "Page $index")
    }
    // The text of a page kept, read from it
    assertEquals("", adapter.getPageText(pdfPath, 2))
    assertEquals(0, adapter.getTextLayout(pdfPath, 1)?.charCount)
    // Its document closed with its pages kept, then opened again
    adapter.close()
    val again = assertNotNull(PdfiumEngine.createTileAdapter())
    assertEquals(PAGE_COLORS[4], again.colorOf(pdfPath, 4))
    again.close()

    // The pages of a document changing while one of them is kept
    val document = PdfiumBindings.openDocument(pdfPath, null)
    assertNotEquals(0L, document)
    try {
        assertEquals(PAGE_COLORS[0], PdfiumBindings.colorOf(document, 0))
        assertTrue(PdfiumBindings.deletePage(document, 0))
        assertEquals(PAGE_COLORS[1], PdfiumBindings.colorOf(document, 0), "The first page deleted")
        val added = PdfiumBindings.newPage(document, 0, 100.0, 100.0)
        assertNotEquals(0L, added)
        assertTrue(PdfiumBindings.endPage(added))
        assertEquals(WHITE, PdfiumBindings.colorOf(document, 0), "A blank page added first")
        assertEquals(PAGE_COLORS[1], PdfiumBindings.colorOf(document, 1))
    } finally {
        PdfiumBindings.closeDocument(document)
    }
}

/** The color in the middle of page [index] of [path], drawn by the adapter in a tile. */
private fun PdfTileAdapter.colorOf(path: String, index: Int): Int {
    val tile = ImageBitmap(16, 16)
    renderPdfPage(Canvas(tile), path, index, Rect(0f, 0f, 16f, 16f), Rect(0f, 0f, 16f, 16f))
    return tile.center()
}

/** The color in the middle of page [index] of [document], drawn by the bridge. */
private fun PdfiumBindings.colorOf(document: Long, index: Int): Int =
    assertNotNull(renderPageSlice(document, index, 0, 0, 16, 16, 16, 16, 0)).center()

private fun ImageBitmap.center(): Int {
    val pixels = IntArray(width * height)
    readPixels(pixels)
    return pixels[(height / 2) * width + width / 2]
}
