package io.github.valerioisufi.drawview.explorer

import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileType
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.platform.PAGEBOARD_DATA_DIRECTORY_PROPERTY
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** The files dropped onto the library or the editor: the name of their note, their import. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileImportTest {

    @Test
    fun aDroppedFileGetsANameFreeInTheFolder() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val viewModel = FileExplorerViewModel(OpenDocuments(KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)))
            viewModel.createFile(FileType.FILE, "Slides")
            viewModel.createFile(FileType.FILE, "Slides (2)")
            assertEquals("Slides (3)", viewModel.availableName("Slides"))
            assertEquals("Lecture", viewModel.availableName(" Lecture "))
            assertEquals("a-b", viewModel.availableName("a/b"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aPdfImportedIntoANewNoteReplacesItsEmptyPage() {
        val data = Files.createTempDirectory("drawview").toFile()
        val previous = System.getProperty(PAGEBOARD_DATA_DIRECTORY_PROPERTY)
        System.setProperty(PAGEBOARD_DATA_DIRECTORY_PROPERTY, data.path)
        try {
            val pdf = File(data, "slides.pdf")
            PdfiumEngine.create().use { writer ->
                repeat(3) { writer.beginPage(842f, 595f) }
                writer.save(pdf.path)
            }
            val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined)).apply {
                tileManager.tileRenderer.pdfAdapter = PdfiumEngine.createTileAdapter()
            }
            assertEquals(1, controller.document.pages.size)

            assertFalse(controller.mediaManager.importFile(pdf.readBytes(), "docx"))
            assertTrue(controller.mediaManager.importFile(pdf.readBytes(), "PDF"))
            awaitPages(controller, 3)
            // The pages of the PDF, in landscape, without the empty page of the note
            assertTrue(controller.document.pages.all { it.pdfs.size == 1 && it.widthMm > it.heightMm })

            // A note with something written keeps its pages: the PDF follows them
            controller.mediaManager.importFile(pdf.readBytes(), "pdf")
            awaitPages(controller, 6)
        } finally {
            if (previous == null) System.clearProperty(PAGEBOARD_DATA_DIRECTORY_PROPERTY) else System.setProperty(PAGEBOARD_DATA_DIRECTORY_PROPERTY, previous)
            data.deleteRecursively()
        }
    }

    /** Waits for the import (on other threads) to give the document [count] pages. */
    private fun awaitPages(controller: DrawViewController, count: Int) {
        val end = System.currentTimeMillis() + 10_000
        while (controller.document.pages.size != count) {
            check(System.currentTimeMillis() < end) { "${controller.document.pages.size} pages, not $count" }
            Thread.sleep(20)
        }
    }
}
