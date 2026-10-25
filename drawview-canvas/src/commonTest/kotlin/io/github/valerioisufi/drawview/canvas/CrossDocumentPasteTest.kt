package io.github.valerioisufi.drawview.canvas

import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.EditorClipboard
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.AddResource
import io.github.valerioisufi.drawview.model.change.StrokeData
import io.github.valerioisufi.drawview.model.geometry.Dimension
import io.github.valerioisufi.drawview.model.geometry.mm
import io.github.valerioisufi.drawview.model.newElementId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Copy and paste between the editors of two documents sharing the clipboard of their application
 * (see `docs/cross-document-paste.md`).
 */
class CrossDocumentPasteTest {

    private fun controller(scope: CoroutineScope, clipboard: EditorClipboard, background: PageBackground = PageBackground.Solid()) =
        DrawViewController(document = DrawDocument(defaultBackground = background), coroutineScope = scope, clipboard = clipboard)
            .also { it.viewport.setSize(800f, 1200f) }

    /** A stroke at [x], 10 mm down, and a text under it on the first page of [controller]. */
    private fun addContent(controller: DrawViewController) {
        val stroke = StrokeData(
            id = newElementId(),
            points = (0 until 5).map { StrokePoint(x = 30f + it, y = 10f + it, pressure = 0.5f, timestampMs = it * 8L) },
            tool = StrokeTool.PRESSURE_PEN,
            size = 0.5f,
            color = 0xFF000000.toInt()
        ).toElement()
        val text = DrawText().apply { text = "Notes"; x = 30f; y = 40f; width = 50f; fontSize = 16f }
        controller.session.submit(AddElements.of(controller.document.pages[0], listOf(stroke, text)))
    }

    /** Selects everything on the first page of [controller] with the lasso. */
    private fun selectAll(controller: DrawViewController) {
        controller.selectionManager.selectWithLasso(0, floatArrayOf(0f, 0f, 210f, 0f, 210f, 297f, 0f, 297f))
        assertNotNull(controller.selectionManager.currentSelection)
    }

    @Test
    fun elementsCopiedInADocumentArePastedInAnother() = runTest(UnconfinedTestDispatcher()) {
        val clipboard = EditorClipboard()
        val first = controller(backgroundScope, clipboard)
        val second = controller(backgroundScope, clipboard)
        addContent(first)
        assertFalse(second.selectionManager.canPaste())

        selectAll(first)
        first.selectionManager.copySelection()
        assertTrue(second.selectionManager.canPaste())
        second.selectionManager.pasteSelection()

        val source = first.document.pages[0]
        val page = second.document.pages[0]
        assertEquals(listOf("Notes"), page.texts.map { it.text })
        assertEquals(1, page.strokes.size)
        // Where they were on their page, selected
        assertEquals(source.texts.single().x, page.texts.single().x)
        assertEquals(source.strokes.single().transform, page.strokes.single().transform)
        val selection = assertNotNull(second.selectionManager.currentSelection)
        assertEquals(2, selection.elements().size)
        // Undone in one step
        second.undo()
        assertTrue(page.stack.isEmpty())
        assertEquals(2, source.stack.size, "the source as it was")
    }

    @Test
    fun elementsCutAndPastedInTheirDocumentMoveBy10mm() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope, EditorClipboard())
        addContent(controller)
        val x = controller.document.pages[0].texts.single().x
        selectAll(controller)
        controller.selectionManager.copySelection()
        controller.selectionManager.pasteSelection()
        assertEquals(listOf(x, x + 10f), controller.document.pages[0].texts.map { it.x }.sorted())
    }

    @Test
    fun elementsPastedOnASmallerPageStayInsideIt() = runTest(UnconfinedTestDispatcher()) {
        val clipboard = EditorClipboard()
        val first = controller(backgroundScope, clipboard)
        val second = controller(backgroundScope, clipboard)
        val text = DrawText().apply { text = "Far"; x = 180f; y = 280f; width = 25f; fontSize = 16f }
        first.session.submit(AddElements.of(first.document.pages[0], listOf(text)))
        first.selectionManager.selectText(0, text)
        first.selectionManager.copySelection()
        val small = second.document.pages[0]
        second.pageManager.changeSinglePageTemplate(0, Dimension(148f.mm, 210f.mm), PageBackground.Solid())
        second.selectionManager.pasteSelection()

        val pasted = second.document.pages[0].texts.single()
        assertTrue(pasted.x >= 0f && pasted.x + pasted.width <= small.widthMm, "inside the width: ${pasted.x}")
        assertTrue(pasted.y >= 0f && pasted.y <= small.heightMm, "inside the height: ${pasted.y}")
    }

    @Test
    fun anImageWhoseFileIsNotHereIsLeftOutAndTold() = runTest(UnconfinedTestDispatcher()) {
        val clipboard = EditorClipboard()
        val first = controller(backgroundScope, clipboard)
        val second = controller(backgroundScope, clipboard)
        val messages = mutableListOf<DrawViewController.Message>()
        backgroundScope.launch { second.messages.collect { messages.add(it) } }
        addContent(first)
        first.session.submit(AddResource.of("r", DrawResource.ResourceType.IMAGE, "/not/here.png", "hash"))
        val image = DrawImage().apply { resourceId = "r"; x = 100f; y = 100f; width = 20f; height = 20f }
        first.session.submit(AddElements.of(first.document.pages[0], listOf(image)))

        selectAll(first)
        first.selectionManager.copySelection()
        second.selectionManager.pasteSelection()

        val page = second.document.pages[0]
        assertTrue(page.images.isEmpty())
        assertEquals(2, page.stack.size)
        assertEquals(listOf(DrawViewController.Message.PASTED_WITHOUT_MISSING_FILES), messages)
        // The frame around what was pasted
        assertEquals(2, assertNotNull(second.selectionManager.currentSelection).elements().size)
    }

    @Test
    fun aPageCopiedInADocumentIsPastedInAnother() = runTest(UnconfinedTestDispatcher()) {
        val clipboard = EditorClipboard()
        val first = controller(backgroundScope, clipboard, PageBackground.Ruled())
        val second = controller(backgroundScope, clipboard, PageBackground.Dotted())
        second.pageManager.addNewPageAfter(0)
        addContent(first)

        first.pageManager.copyPage(0)
        assertTrue(second.pageManager.canPastePages())
        assertFalse(second.selectionManager.canPaste(), "pages, not elements")
        second.pageManager.pastePages(0)

        val pages = second.document.pages
        assertEquals(3, pages.size)
        val copy = pages[1]
        assertEquals(PageBackground.Ruled(), copy.background, "the paper it had")
        assertEquals(listOf("Notes"), copy.texts.map { it.text })
        assertEquals(1, copy.strokes.size)
        second.undo()
        assertEquals(2, second.document.pages.size)
    }

    @Test
    fun aReadOnlyDocumentTakesNothing() = runTest(UnconfinedTestDispatcher()) {
        val clipboard = EditorClipboard()
        val first = controller(backgroundScope, clipboard)
        val second = controller(backgroundScope, clipboard)
        addContent(first)
        second.session.isReadOnly = true

        selectAll(first)
        first.selectionManager.copySelection()
        second.selectionManager.pasteSelection()
        assertTrue(second.document.pages[0].stack.isEmpty())
        first.pageManager.copyPage(0)
        second.pageManager.pastePages(0)
        assertEquals(1, second.document.pages.size)
    }

    @Test
    fun editorsGivenNoClipboardKeepTheirOwn() = runTest(UnconfinedTestDispatcher()) {
        val first = DrawViewController(document = DrawDocument(), coroutineScope = backgroundScope).also { it.viewport.setSize(800f, 1200f) }
        val second = DrawViewController(document = DrawDocument(), coroutineScope = backgroundScope)
        addContent(first)
        selectAll(first)
        first.selectionManager.copySelection()
        assertTrue(first.selectionManager.canPaste())
        assertFalse(second.selectionManager.canPaste())
    }
}
