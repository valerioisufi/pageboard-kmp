package io.github.valerioisufi.pageboard.canvas

import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.controller.EditorClipboard
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.CopiedPages
import io.github.valerioisufi.pageboard.model.geometry.Dimension
import io.github.valerioisufi.pageboard.model.geometry.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** Duplicating pages and adding a page before another (see `docs/plans/page-actions.md`). */
class PageActionsTest {

    /** A document of [pages] pages, a text on each one naming it ("1", "2"...). */
    private fun controller(scope: CoroutineScope, pages: Int = 3, clipboard: EditorClipboard = EditorClipboard()): PageboardController {
        val document = Document(defaultBackground = PageBackground.Ruled())
        repeat(pages - 1) { document.addPage() }
        val controller = PageboardController(document = document, coroutineScope = scope, clipboard = clipboard)
        controller.viewport.setSize(800f, 1200f)
        for ((i, page) in document.pages.withIndex()) {
            val text = TextElement().apply { text = "${i + 1}"; x = 10f; y = 10f; width = 20f; fontSize = 16f }
            controller.session.submit(AddElements.of(page, listOf(text)))
        }
        return controller
    }

    private fun PageboardController.names() = document.pages.map { page -> page.texts.singleOrNull()?.text ?: "-" }

    private fun PageboardController.ids(vararg names: String) = names.map { name -> document.pages.first { it.texts.single().text == name }.id }

    @Test
    fun aPageIsDuplicatedWithItsContentRightAfterIt() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val pages = controller.pageManager
        pages.changeSinglePageTemplate(1, Dimension(297f.mm, 210f.mm), PageBackground.Grid())

        pages.duplicatePages(controller.ids("2"))

        assertEquals(listOf("1", "2", "2", "3"), controller.names())
        val (original, copy) = controller.document.pages.subList(1, 3)
        assertNotEquals(original.id, copy.id)
        assertNotEquals(original.texts.single().id, copy.texts.single().id, "a copy of the element, not the same")
        assertEquals(297f, copy.widthMm)
        assertEquals(PageBackground.Grid(), copy.background)
        // Shown, one step to undo
        controller.undo()
        assertEquals(listOf("1", "2", "3"), controller.names())
    }

    @Test
    fun pagesSelectedApartAreDuplicatedAfterTheLastOneInOneStep() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, pages = 4)

        controller.pageManager.duplicatePages(controller.ids("3", "1"))

        assertEquals(listOf("1", "2", "3", "1", "3", "4"), controller.names())
        controller.undo()
        assertEquals(listOf("1", "2", "3", "4"), controller.names())
    }

    @Test
    fun duplicatingLeavesTheClipboardAsItWas() = runTest(UnconfinedTestDispatcher()) {
        val clipboard = EditorClipboard()
        val controller = controller(backgroundScope, clipboard = clipboard)
        assertNull(clipboard.content)
        controller.pageManager.duplicatePages(controller.ids("1"))
        assertNull(clipboard.content)

        controller.pageManager.copyPages(controller.ids("3"))
        val copied = assertIs<CopiedPages>(clipboard.content)
        controller.pageManager.duplicatePages(controller.ids("2"))
        assertTrue(clipboard.content === copied)
    }

    @Test
    fun aReadOnlyDocumentIsNotDuplicated() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        controller.session.isReadOnly = true
        controller.pageManager.duplicatePages(controller.ids("1"))
        assertEquals(listOf("1", "2", "3"), controller.names())
    }

    @Test
    fun aPageAddedBeforeAnotherIsLikeIt() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val pages = controller.pageManager
        pages.changeSinglePageTemplate(1, Dimension(297f.mm, 210f.mm), PageBackground.Grid())

        val added = pages.addNewPageBefore(1)

        assertEquals(listOf("1", "-", "2", "3"), controller.names())
        assertTrue(added === controller.document.pages[1])
        assertEquals(297f, added.widthMm)
        assertEquals(PageBackground.Grid(), added.background)
        controller.undo()
        assertEquals(listOf("1", "2", "3"), controller.names())
    }

    @Test
    fun aPageAddedBeforeTheFirstGoesFirst() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)

        controller.pageManager.addNewPageBefore(0)
        assertEquals(listOf("-", "1", "2", "3"), controller.names())
        // An index that is not a page: at the end
        controller.pageManager.addNewPageBefore(99)
        assertEquals(listOf("-", "1", "2", "3", "-"), controller.names())
    }
}
