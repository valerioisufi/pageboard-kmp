package io.github.valerioisufi.drawview.model

import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.AddResource
import io.github.valerioisufi.drawview.model.change.ChangeOrigin
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.Operation
import io.github.valerioisufi.drawview.model.change.StrokeData
import io.github.valerioisufi.drawview.model.change.copyElements
import io.github.valerioisufi.drawview.model.change.copyPages
import io.github.valerioisufi.drawview.model.change.pasteInto
import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Copy and paste between documents (see `docs/cross-document-paste.md`): what is copied is
 * detached from its document; pasted, it gets new identifiers, the resources of the target, its
 * place and its stack; another device of the target receives it as it was made.
 */
class ContentPasteTest {

    /** A device working on a document: its session and the operations it made. */
    private class Device(val session: DocumentSession = DocumentSession(DrawDocument())) {
        val sent = mutableListOf<Operation>()
        val document get() = session.document
        val page get() = document.pages[0]

        init {
            session.addObserver { if (it.origin != ChangeOrigin.REMOTE) sent += it.operation }
        }
    }

    private fun stroke(x: Float) = StrokeData(
        id = newElementId(),
        points = (0 until 5).map { StrokePoint(x = x + it, y = 10f + it, pressure = 0.5f, timestampMs = it * 8L) },
        tool = StrokeTool.PRESSURE_PEN,
        size = 0.5f,
        color = 0xFF0000FF.toInt()
    ).toElement()

    private fun text() = DrawText().apply {
        text = "Hello"; x = 20f; y = 30f; width = 40f; fontSize = 16f
    }

    private fun image(resourceId: String) = DrawImage().apply {
        this.resourceId = resourceId; x = 50f; y = 60f; width = 20f; height = 10f
    }

    private fun custom() = DrawCustomElement("table").apply {
        payload = byteArrayOf(1, 2, 3); x = 70f; y = 80f; width = 10f; height = 10f
    }

    /** A document with a stroke, a text, an image of the file `/files/a.png` and an element of an application. */
    private fun source(): Device {
        val device = Device()
        device.session.submit(AddResource.of("r", DrawResource.ResourceType.IMAGE, "/files/a.png", "hash-a"))
        val page = device.page
        device.session.submit(AddElements.of(page, listOf(image("r"), stroke(0f), text(), custom())))
        return device
    }

    @Test
    fun elementsPastedInAnotherDocumentKeepTheirLookAndStack() {
        val source = source()
        val page = source.page
        val copied = source.document.copyElements(page, page.stack, Rect(0f, 10f, 80f, 90f))
        val target = Device()
        val pasted = copied.pasteInto(target.document, target.page, 5f, 6f) { true }
        target.session.submit(assertNotNull(pasted.change))

        val stack = target.page.stack
        assertEquals(page.stack.map { it::class }, stack.map { it::class }, "the stack in its order")
        assertTrue(stack.none { element -> page.stack.any { it.id == element.id } }, "new identifiers")
        assertEquals(0, pasted.leftOut)
        assertEquals(Rect(5f, 16f, 85f, 96f), pasted.bounds)

        // The image on a new resource of the target, on the same file
        val image = stack.filterIsInstance<DrawImage>().single()
        val resource = assertNotNull(target.document.findResource(image.resourceId))
        assertFalse(image.resourceId == "r")
        assertEquals("/files/a.png", resource.path)
        assertEquals("hash-a", resource.hash)
        assertEquals(55f, image.x)
        assertEquals(66f, image.y)

        val text = stack.filterIsInstance<DrawText>().single()
        assertEquals("Hello", text.text)
        assertEquals(25f, text.x)
        val stroke = stack.filterIsInstance<DrawStroke>().single()
        assertEquals(5f, stroke.transform.tx)
        assertEquals(6f, stroke.transform.ty)
        val custom = stack.filterIsInstance<DrawCustomElement>().single()
        assertContentEquals(byteArrayOf(1, 2, 3), custom.payload)
        assertEquals(75f, custom.x)

        // The source as it was
        assertEquals(4, page.stack.size)
        assertEquals(50f, page.images.single().x)
    }

    @Test
    fun anotherDeviceOfTheTargetReceivesThePaste() {
        val source = source()
        val copied = source.document.copyElements(source.page, source.page.stack, Rect(0f, 0f, 1f, 1f))
        val target = Device()
        val other = Device(DocumentSession(DrawDocument(target.document.id), replica = "bob"))
        // A page both devices have
        val page = DrawPage(orderKey = target.document.orderKeyAt(1))
        target.session.submit(AddPage.of(page))
        target.session.submit(assertNotNull(copied.pasteInto(target.document, page, 0f, 0f) { true }.change))

        for (operation in target.sent) {
            assertTrue(other.session.admits(operation), "admitted: ${operation.change}")
            other.session.receive(operation)
        }
        val received = assertNotNull(other.document.pageById(page.id))
        assertEquals(4, received.stack.size)
        assertEquals(page.stack.map { it.id }, received.stack.map { it.id })
        val image = received.images.single()
        assertEquals("hash-a", assertNotNull(other.document.findResource(image.resourceId)).hash)
    }

    @Test
    fun theResourceOfTheSameContentIsReused() {
        val source = source()
        val copied = source.document.copyElements(source.page, source.page.images, Rect(50f, 60f, 70f, 70f))
        val target = Device()
        target.session.submit(AddResource.of("mine", DrawResource.ResourceType.IMAGE, "/files/a.png", "hash-a"))
        target.session.submit(assertNotNull(copied.pasteInto(target.document, target.page, 0f, 0f) { true }.change))

        assertEquals(listOf("mine"), target.document.resources.map { it.id })
        assertEquals("mine", target.page.images.single().resourceId)
    }

    @Test
    fun pastedInItsOwnDocumentItKeepsItsResource() {
        val source = source()
        val page = source.page
        val copied = source.document.copyElements(page, page.images, Rect(50f, 60f, 70f, 70f))
        source.session.submit(assertNotNull(copied.pasteInto(source.document, page, 10f, 10f) { true }.change))

        assertEquals(listOf("r"), source.document.resources.map { it.id })
        assertEquals(listOf("r", "r"), page.images.map { it.resourceId })
        assertEquals(2, page.images.map { it.id }.distinct().size)
        // On top of the stack
        assertEquals(60f, page.stack.last().let { it as DrawImage }.x)
    }

    @Test
    fun anElementOfAFileNotHereIsLeftOut() {
        val source = source()
        val copied = source.document.copyElements(source.page, source.page.stack, Rect(0f, 0f, 1f, 1f))
        val target = Device()
        val pasted = copied.pasteInto(target.document, target.page, 0f, 0f) { false }
        target.session.submit(assertNotNull(pasted.change))

        assertEquals(1, pasted.leftOut)
        assertTrue(target.page.images.isEmpty())
        assertTrue(target.document.resources.isEmpty())
        assertEquals(3, target.page.stack.size)

        val onlyImage = source.document.copyElements(source.page, source.page.images, Rect(0f, 0f, 1f, 1f))
        assertNull(onlyImage.pasteInto(target.document, target.page, 0f, 0f) { false }.change, "nothing to paste")
    }

    @Test
    fun whatWasCopiedIsDetachedFromItsDocument() {
        val source = source()
        val page = source.page
        val copied = source.document.copyElements(page, page.texts, Rect(0f, 0f, 1f, 1f))
        page.texts.single().text = "Changed"
        val target = Device()
        target.session.submit(assertNotNull(copied.pasteInto(target.document, target.page, 0f, 0f) { true }.change))
        assertEquals("Hello", target.page.texts.single().text)
    }

    /** A PDF page of the PDF `/files/b.pdf` with a mark, a stroke under a text, on the paper of the document. */
    private fun pdfSource(): Device {
        val device = Device(DocumentSession(DrawDocument().apply { defaultBackground = PageBackground.Grid() }))
        device.session.submit(AddResource.of("pdf", DrawResource.ResourceType.PDF, "/files/b.pdf", "hash-b"))
        val page = device.page
        val markup = DrawTextMarkup(
            resourceId = "pdf", pdfPageIndex = 2, pdfWidthPt = 595f, pdfHeightPt = 842f, startIndex = 0, endIndex = 5,
            text = "words", rects = listOf(Rect(1f, 2f, 3f, 4f)), kind = TextMarkupKind.HIGHLIGHT, color = 0xFFFFFF00.toInt()
        )
        val text = text()
        val stroke = stroke(0f)
        // The text over the stroke (the layers of the kinds before the one stack had them the other way)
        device.session.submit(AddElements.of(page, listOf(DrawPdf(pdfPageIndex = 2).apply { resourceId = "pdf" }, markup, stroke, text)))
        return device
    }

    @Test
    fun aPagePastedInAnotherDocumentKeepsItsContentAndBackground() {
        val source = pdfSource()
        val page = source.page
        val copied = source.document.copyPages(listOf(page))
        val target = Device(DocumentSession(DrawDocument().apply { defaultBackground = PageBackground.Dotted() }))
        target.session.submit(AddPage.of(DrawPage(orderKey = target.document.orderKeyAt(1))))
        val (first, last) = target.document.pages
        val pasted = copied.pasteInto(target.document, 1) { true }
        val inverse = target.session.submit(assertNotNull(pasted.change))

        val pages = target.document.pages
        assertEquals(3, pages.size)
        assertEquals(listOf(first.id, pasted.pages.single().id, last.id), pages.map { it.id }, "between the two pages")
        val copy = pages[1]
        assertFalse(copy.id == page.id)
        assertEquals(page.widthMm, copy.widthMm)
        assertFalse(page.hasCustomBackground)
        assertTrue(copy.hasCustomBackground, "the background it showed, of its own")
        assertEquals(page.background, copy.background)
        assertFalse(copy.background == target.document.defaultBackground)

        // The PDF and its mark on one new resource of the same file
        val pdf = copy.pdfs.single()
        val markup = copy.markups.single()
        assertEquals(2, pdf.pdfPageIndex)
        assertEquals(pdf.resourceId, markup.resourceId)
        assertEquals("/files/b.pdf", assertNotNull(target.document.findResource(pdf.resourceId)).path)
        assertEquals("words", markup.text)
        assertEquals(listOf(DrawStroke::class, DrawText::class), copy.stack.map { it::class }, "the stack as it was")

        // Undone in one step
        target.session.submit(assertNotNull(inverse), ChangeOrigin.UNDO)
        assertEquals(listOf(first.id, last.id), target.document.pages.map { it.id })
    }

    @Test
    fun pagesPastedTwiceInTheirDocumentAreDuplicates() {
        val source = pdfSource()
        val page = source.page
        val copied = source.document.copyPages(listOf(page))
        source.session.submit(assertNotNull(copied.pasteInto(source.document, 1) { true }.change))
        source.session.submit(assertNotNull(copied.pasteInto(source.document, 1) { true }.change))

        val pages = source.document.pages
        assertEquals(3, pages.size)
        assertEquals(3, pages.map { it.id }.distinct().size)
        assertEquals(listOf("pdf"), source.document.resources.map { it.id })
        for (copy in pages.drop(1)) {
            assertEquals("pdf", copy.pdfs.single().resourceId)
            assertEquals(2, copy.stack.size)
        }
        // A PDF page not here: the page with the writing
        val other = Device()
        val pasted = copied.pasteInto(other.document, 1) { false }
        other.session.submit(assertNotNull(pasted.change))
        assertEquals(2, pasted.leftOut)
        assertTrue(other.document.pages[1].pdfs.isEmpty())
        assertEquals(2, other.document.pages[1].stack.size)
    }
}
