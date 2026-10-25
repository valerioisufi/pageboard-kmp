package io.github.valerioisufi.drawview.canvas

import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.CommittedChange
import io.github.valerioisufi.drawview.model.geometry.Dimension
import io.github.valerioisufi.drawview.model.geometry.mm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * A page added after another one takes its format (docs/new-page-format.md): its size, its
 * background as that page has it (its own, or following the document), never its content.
 */
class NewPageTest {

    /** A document of [pages] A4 pages on ruled paper. */
    private fun controller(scope: CoroutineScope, pages: Int = 1): DrawViewController {
        val document = DrawDocument(defaultBackground = PageBackground.Ruled())
        repeat(pages - 1) { document.addPage() }
        return DrawViewController(document = document, coroutineScope = scope).also { it.viewport.setSize(800f, 1200f) }
    }

    /** The pages the changes committed by [controller] from now on add, as they are sent. */
    private fun addedPages(controller: DrawViewController) = mutableListOf<CommittedChange>().also { list ->
        controller.session.addObserver { list.add(it) }
    }

    private fun List<CommittedChange>.sentPage() = assertIs<AddPage>(single().operation.change).page

    private fun DrawPage.format() = Triple(widthMm, heightMm, background)

    @Test
    fun aPageAddedAfterAPageOfItsOwnFormatIsLikeIt() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val pages = controller.pageManager
        pages.changeSinglePageTemplate(0, Dimension(297f.mm, 210f.mm), PageBackground.Grid())
        val changes = addedPages(controller)

        val added = pages.addNewPageAfter(0)

        assertSame(added, controller.document.pages[1])
        assertEquals(Triple(297f, 210f, PageBackground.Grid()), added.format())
        assertTrue(added.hasCustomBackground)
        // Sent with its background, of its own
        assertEquals(PageBackground.Grid(), changes.sentPage().background)

        // The background of the document changed: the page keeps its own
        pages.applyDocumentTemplateChange(Dimension(210f.mm, 297f.mm), PageBackground.Dotted(), overrideAll = false)
        assertEquals(PageBackground.Grid(), added.background)
    }

    @Test
    fun aPageAddedAfterAPageFollowingTheDocumentFollowsItToo() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val pages = controller.pageManager
        // Landscape, on the paper of the document
        pages.changeSinglePageTemplate(0, Dimension(297f.mm, 210f.mm), PageBackground.Ruled())
        assertFalse(controller.document.pages[0].hasCustomBackground)
        val changes = addedPages(controller)

        val added = pages.addNewPageAfter(0)

        assertEquals(Triple(297f, 210f, PageBackground.Ruled()), added.format())
        assertFalse(added.hasCustomBackground)
        assertNull(changes.sentPage().background)

        // The background of the document changed: both pages follow it
        pages.applyDocumentTemplateChange(Dimension(210f.mm, 297f.mm), PageBackground.Dotted(), overrideAll = false)
        assertEquals(listOf(PageBackground.Dotted(), PageBackground.Dotted()), controller.document.pages.map { it.background })
    }

    @Test
    fun aPageAddedAfterAPdfPageTakesItsSizeNotThePdf() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val document = controller.document
        // A slide, as MediaManager.importPdf makes it: on the background of the document
        val slide = DrawPage(widthMm = 254f, heightMm = 142.875f, background = document.defaultBackground, orderKey = document.orderKeyAt(1)).apply {
            add(DrawPdf(pdfPageIndex = 0).apply { resourceId = "slides" })
        }
        controller.session.submit(AddPage.of(slide))

        val added = controller.pageManager.addNewPageAfter(1)

        assertEquals(Triple(254f, 142.875f, PageBackground.Ruled()), added.format())
        assertFalse(added.hasCustomBackground)
        assertTrue(added.elements().isEmpty())
    }

    @Test
    fun aPageAddedAtTheEndIsLikeTheLastPage() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, pages = 2)
        val pages = controller.pageManager
        pages.changeSinglePageTemplate(1, Dimension(148f.mm, 210f.mm), PageBackground.Grid())

        // The button of the page grid, at the end
        val fromGrid = pages.addNewPageAfter(controller.document.pages.lastIndex)
        // An index that is not a page: at the end too
        val fromInvalid = pages.addNewPageAfter(42)
        val atBottom = pages.addNewPageAtBottom()

        assertEquals(listOf(fromGrid, fromInvalid, atBottom), controller.document.pages.drop(2))
        for (page in listOf(fromGrid, fromInvalid, atBottom)) {
            assertEquals(Triple(148f, 210f, PageBackground.Grid()), page.format())
        }
    }

    @Test
    fun aPageWithoutTemplateTakesTheFormatOfTheDocument() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val pages = controller.pageManager
        pages.changeSinglePageTemplate(0, Dimension(297f.mm, 210f.mm), PageBackground.Grid())

        val after = pages.addNewPageAfter(0, template = null)
        val atBottom = pages.addNewPageAtBottom(template = null)

        for (page in listOf(after, atBottom)) {
            assertEquals(Triple(210f, 297f, PageBackground.Ruled()), page.format())
            assertFalse(page.hasCustomBackground)
        }
    }

    @Test
    fun undoRemovesThePageAdded() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val first = controller.document.pages[0]
        controller.pageManager.changeSinglePageTemplate(0, Dimension(297f.mm, 210f.mm), PageBackground.Grid())

        controller.pageManager.addNewPageAfter(0)
        controller.undo()

        assertEquals(listOf(first), controller.document.pages)
        assertEquals(Triple(297f, 210f, PageBackground.Grid()), first.format())
    }
}
