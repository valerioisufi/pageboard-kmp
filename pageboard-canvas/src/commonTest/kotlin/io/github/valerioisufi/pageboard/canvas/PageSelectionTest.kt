package io.github.valerioisufi.pageboard.canvas

import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.canvas.controller.EditorClipboard
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.CopiedPages
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.MovePages
import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.OperationId
import io.github.valerioisufi.pageboard.model.change.RemovePage
import io.github.valerioisufi.pageboard.model.geometry.Dimension
import io.github.valerioisufi.pageboard.model.geometry.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** Selecting several pages and the actions on all of them (see `docs/plans/page-selection.md`). */
class PageSelectionTest {

    /** A document of [pages] pages, a text on each one naming it ("1", "2"...). */
    private fun controller(scope: CoroutineScope, pages: Int = 5, clipboard: EditorClipboard = EditorClipboard()): DrawViewController {
        val document = DrawDocument(defaultBackground = PageBackground.Ruled())
        repeat(pages - 1) { document.addPage() }
        val controller = DrawViewController(document = document, coroutineScope = scope, clipboard = clipboard)
        controller.viewport.setSize(800f, 1200f)
        for ((i, page) in document.pages.withIndex()) {
            val text = DrawText().apply { text = "${i + 1}"; x = 10f; y = 10f; width = 20f; fontSize = 16f }
            controller.session.submit(AddElements.of(page, listOf(text)))
        }
        return controller
    }

    private fun DrawViewController.names() = document.pages.map { it.texts.single().text }

    private fun DrawViewController.ids(vararg names: String) = names.map { name -> document.pages.single { it.texts.single().text == name }.id }

    @Test
    fun pagesAreSelectedOneByOneInRangesOrAll() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val selection = controller.pageManager.selection
        val (a, b, c, d, e) = controller.document.pages.map { it.id }
        assertFalse(selection.isActive)

        selection.toggle(d)
        selection.toggle(b)
        assertTrue(selection.isActive)
        assertEquals(listOf(b, d), selection.pageIds, "in the order of the document")
        selection.toggle(d)
        assertEquals(listOf(b), selection.pageIds)
        // From the last one toggled
        selection.toggle(e)
        selection.selectRange(c)
        assertEquals(listOf(b, c, d, e), selection.pageIds)
        selection.selectAll()
        assertEquals(listOf(a, b, c, d, e), selection.pageIds)
        selection.clear()
        assertEquals(emptyList(), selection.pageIds)
        assertFalse(selection.isActive)
    }

    @Test
    fun theSelectionFollowsThePages() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val selection = controller.pageManager.selection
        val (b, d) = controller.ids("2", "4")
        selection.toggle(b)
        selection.toggle(d)
        // Moved: kept, in the new order
        controller.pageManager.movePage(1, 4)
        assertEquals(listOf(d, b), selection.pageIds)
        // Removed by another device: it leaves the selection
        controller.session.receive(otherDevice(controller, RemovePage(d)))
        assertEquals(listOf(b), selection.pageIds)
        assertEquals(1, selection.count)
    }

    /** The operation of another device making [change] on the document of [controller]. */
    private fun otherDevice(controller: DrawViewController, change: DocumentChange) =
        Operation(
            OperationId("other", 1),
            controller.session.clock.next().copy(replica = "other"),
            change
        )

    @Test
    fun selectedPagesAreDeletedInOneStep() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val pages = controller.pageManager
        val ids = controller.ids("2", "4")
        assertTrue(pages.canDeletePages(ids))
        pages.deletePages(ids)
        assertEquals(listOf("1", "3", "5"), controller.names())
        controller.undo()
        assertEquals(listOf("1", "2", "3", "4", "5"), controller.names())

        // Never every page
        val all = controller.document.pages.map { it.id }
        assertFalse(pages.canDeletePages(all))
        pages.deletePages(all)
        assertEquals(5, controller.document.pages.size)
    }

    @Test
    fun selectedPagesAreCopiedInTheirOrderAndPastedAfterTheLast() = runTest(UnconfinedTestDispatcher()) {
        val clipboard = EditorClipboard()
        val controller = controller(backgroundScope, clipboard = clipboard)
        val other = controller(backgroundScope, pages = 2, clipboard = clipboard)
        controller.pageManager.copyPages(controller.ids("4", "2"))
        assertEquals(2, assertIs<CopiedPages>(clipboard.content).pages.size)

        other.pageManager.pastePagesAfter(other.ids("1"))
        assertEquals(listOf("1", "2", "4", "2"), other.names())
        other.undo()
        assertEquals(listOf("1", "2"), other.names())
    }

    @Test
    fun selectedPagesTakeOneFormatInOneStep() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val ids = controller.ids("1", "3")
        controller.pageManager.changePagesTemplate(ids, Dimension(148f.mm, 210f.mm), PageBackground.Grid())
        val changed = controller.document.pages.filter { it.id in ids }
        assertTrue(changed.all { it.widthMm == 148f && it.background == PageBackground.Grid() && it.hasCustomBackground })
        assertEquals(PageBackground.Ruled(), controller.document.pages[1].background)
        controller.undo()
        assertTrue(controller.document.pages.all { it.background == PageBackground.Ruled() })
    }

    @Test
    fun selectedPagesMoveAsOneBlock() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val moves = mutableListOf<MovePages>()
        controller.session.addObserver { (it.change as? MovePages)?.let(moves::add) }
        // Apart: gathered, in their order, after the first of the others
        controller.pageManager.movePages(controller.ids("4", "2"), 1)
        assertEquals(listOf("1", "2", "4", "3", "5"), controller.names())
        assertEquals(1, moves.size, "one change")
        // At the end, then at the start
        controller.pageManager.movePages(controller.ids("1", "2"), 3)
        assertEquals(listOf("4", "3", "5", "1", "2"), controller.names())
        controller.pageManager.movePages(controller.ids("5"), 0)
        assertEquals(listOf("5", "4", "3", "1", "2"), controller.names())
        controller.undo()
        controller.undo()
        controller.undo()
        assertEquals(listOf("1", "2", "3", "4", "5"), controller.names())
    }

    @Test
    fun aReadOnlyDocumentIsCopiedOnly() = runTest(UnconfinedTestDispatcher()) {
        val clipboard = EditorClipboard()
        val controller = controller(backgroundScope, clipboard = clipboard)
        controller.session.isReadOnly = true
        val ids = controller.ids("2", "3")
        controller.pageManager.deletePages(ids)
        controller.pageManager.movePages(ids, 0)
        controller.pageManager.changePagesTemplate(ids, Dimension(148f.mm, 210f.mm), PageBackground.Grid())
        assertEquals(listOf("1", "2", "3", "4", "5"), controller.names())
        assertTrue(controller.document.pages.all { it.background == PageBackground.Ruled() })
        controller.pageManager.copyPages(ids)
        assertEquals(2, assertIs<CopiedPages>(clipboard.content).pages.size)
    }

    @Test
    fun aSelectionDraggedInTheGridIsOneChangeAtTheDrop() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val moves = mutableListOf<MovePages>()
        controller.session.addObserver { (it.change as? MovePages)?.let(moves::add) }
        val pages = controller.pageManager
        val block = controller.ids("2", "4")
        pages.startPageReorderMode()
        // Gathered where the drag starts, then moved over the others as it goes
        pages.moveBlockDuringReorder(block, 1)
        assertEquals(listOf("1", "2", "4", "3", "5"), controller.names())
        pages.moveBlockDuringReorder(block, 3)
        assertEquals(listOf("1", "3", "5", "2", "4"), controller.names())
        assertTrue(moves.isEmpty(), "nothing recorded while dragging")
        pages.finishPageReorderMode()
        assertEquals(listOf("1", "3", "5", "2", "4"), controller.names())
        assertEquals(1, moves.size)
        controller.undo()
        assertEquals(listOf("1", "2", "3", "4", "5"), controller.names())
    }
}
