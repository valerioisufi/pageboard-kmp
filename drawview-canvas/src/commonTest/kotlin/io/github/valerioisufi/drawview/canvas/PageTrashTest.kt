package io.github.valerioisufi.drawview.canvas

import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.TrashRetention
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** The pages deleted of a document, put back from its trash (see `docs/trash.md`). */
class PageTrashTest {

    /** A document of [pages] pages, a text on each one naming it ("1", "2"...). */
    private fun controller(scope: CoroutineScope, pages: Int = 4): DrawViewController {
        val document = DrawDocument()
        repeat(pages - 1) { document.addPage() }
        val controller = DrawViewController(document = document, coroutineScope = scope)
        for ((i, page) in document.pages.withIndex()) {
            val text = DrawText().apply { text = "${i + 1}"; x = 10f; y = 10f; width = 20f; fontSize = 16f }
            controller.session.submit(AddElements.of(page, listOf(text)))
        }
        return controller
    }

    private fun DrawViewController.names() = document.pages.map { it.texts.single().text }

    @Test
    fun thePagesDeletedArePutBackWhereTheyWereInOneStep() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val pages = controller.pageManager
        val (b, d) = listOf(1, 3).map { controller.document.pages[it].id }
        val told = mutableListOf<List<String>>()
        backgroundScope.launch { pages.deleted.collect { told += it } }

        pages.deletePages(listOf(d))
        pages.deletePages(listOf(b))
        assertEquals(listOf("1", "3"), controller.names())
        assertEquals(listOf(listOf(d), listOf(b)), told, "each deletion told")
        val now = currentTimeMillis()
        assertEquals(listOf(b, d), pages.deletedPagesAt(now).map { it.id }, "the most recent first")
        assertTrue(pages.deletedPagesAt(now + TrashRetention.MILLIS + 60_000).isEmpty(), "out of the trash after 30 days")

        pages.restorePages(listOf(b, d))
        assertEquals(listOf("1", "2", "3", "4"), controller.names(), "where they were, with their content")
        assertTrue(pages.deletedPagesAt(now).isEmpty())
        controller.undo()
        assertEquals(listOf("1", "3"), controller.names(), "one step to undo")
    }

    @Test
    fun aDocumentReadOnlyPutsNothingBack() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val b = controller.document.pages[1].id
        controller.pageManager.deletePages(listOf(b))
        controller.session.isReadOnly = true
        controller.pageManager.restorePages(listOf(b))
        assertEquals(listOf("1", "3", "4"), controller.names())
    }
}
