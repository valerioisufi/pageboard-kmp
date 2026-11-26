package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Canvas
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.render.tile.PdfTileAdapter
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.platform.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** The text of a PDF chosen as a text: its pages one after the other, waiting for where it goes. */
class PdfTextImportTest {

    /** A PDF of [pages] read by the engine, the paths it was asked to read. */
    private class FakePdf(private val pages: List<String?>) : PdfTileAdapter {
        val read = mutableListOf<String>()
        override fun renderPdfPage(canvas: Canvas, resourcePath: String, pdfPageIndex: Int, pageRect: ComposeRect, clipRect: ComposeRect) {}
        override fun getPageSizesMm(resourcePath: String): List<Pair<Float, Float>> {
            read += resourcePath
            return pages.map { 210f to 297f }
        }
        override fun getPageText(resourcePath: String, pdfPageIndex: Int): String? = pages[pdfPageIndex]
    }

    private fun controller(scope: CoroutineScope, pdf: FakePdf): PageboardController {
        val controller = PageboardController(document = Document(), coroutineScope = scope)
        controller.tileManager.tileRenderer.pdfAdapter = pdf
        return controller
    }

    /** Waits, in the time of the clock, for [done]. */
    private suspend fun until(done: () -> Boolean) = withContext(Dispatchers.Default) {
        repeat(500) {
            if (done()) return@withContext
            delay(10)
        }
    }

    @Test
    fun theTextOfThePagesWaitsForWhereItGoes() = runTest(UnconfinedTestDispatcher()) {
        val pdf = FakePdf(listOf(" First page ", null, "Third page"))
        val controller = controller(backgroundScope, pdf)
        controller.mediaManager.importPdfText(byteArrayOf(1, 2, 3), "Lesson")
        until { controller.pendingTextImport != null }
        val imported = controller.pendingTextImport!!
        assertEquals("First page\n\nThird page", imported.text)
        assertEquals("Lesson", imported.name)
        assertFalse(imported.isMarkdown, "cleaned as a text from elsewhere")
        assertFalse(PlatformFiles.exists(pdf.read.single()), "the file read deleted")
    }

    @Test
    fun aPdfWithNoTextSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, FakePdf(listOf(null, " ")))
        val message = backgroundScope.async { controller.messages.first() }
        controller.mediaManager.importPdfText(byteArrayOf(1), "Scan")
        until { message.isCompleted }
        assertEquals(PageboardController.Message.PDF_WITHOUT_TEXT, message.await())
        assertNull(controller.pendingTextImport)
    }

    @Test
    fun aFileThatIsNoPdfFails() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, FakePdf(emptyList()))
        val message = backgroundScope.async { controller.messages.first() }
        controller.mediaManager.importPdfText(byteArrayOf(1), "Not")
        until { message.isCompleted }
        assertTrue(message.isCompleted)
        assertEquals(PageboardController.Message.IMPORT_PDF_FAILED, message.await())
    }
}
