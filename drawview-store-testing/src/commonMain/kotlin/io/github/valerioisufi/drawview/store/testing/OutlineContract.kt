package io.github.valerioisufi.drawview.store.testing

import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.store.DocumentOutline
import io.github.valerioisufi.drawview.store.DocumentStore
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What every [DocumentStore] reads of a document without its strokes ([DocumentStore.loadOutline]):
 * the outline of the document it loads whole, its pages shown and their elements shown in their
 * order. Run by the tests of each store, of DrawView or of an application, on an empty store.
 */
public suspend fun checkOutline(store: DocumentStore) {
    assertNull(store.loadOutline("missing"), "no document")

    val document = SyntheticDocuments.notebook(pages = 3, strokesPerPage = 20, textsPerPage = 2, removedFraction = 0.2f, title = "Outline")
    val pdf = DrawResource(newElementId(), DrawResource.ResourceType.PDF, "outline-slides.pdf", "hash-of-the-slides")
    document.addResource(pdf)
    val (first, second, third) = document.pages
    first.add(DrawPdf(pdfPageIndex = 4).apply { resourceId = pdf.id })
    first.add(DrawCustomElement("formula").apply { payload = "x^2".encodeToByteArray(); x = 30f; y = 40f; width = 20f; height = 8f })
    // A text erased: kept, hidden
    val erased = DrawText().apply { text = "erased"; x = 10f; y = 10f }
    second.add(erased)
    second.remove(erased)
    erased.removed = Stamp(2L, 0, "outline")
    second.removedElements.add(erased)
    // A page removed: kept, hidden
    document.restorePageOrder(listOf(first, second))
    third.removed = Stamp(3L, 0, "outline")
    document.removedPages[third.id] = third
    store.insertDocument(document)

    val expected = DocumentOutline.of(assertNotNull(store.loadDocument(document.id)))
    val outline = assertNotNull(store.loadOutline(document.id))
    assertEquals(document.id, outline.id)
    assertEquals(listOf(first.id, second.id), outline.pages.map { it.id }, "the pages shown, in their order")
    assertEquals(expected.pages.map { it.id }, outline.pages.map { it.id })
    for ((e, a) in expected.pages.zip(outline.pages)) {
        assertEquals(e.texts.map { listOf(it.id, it.text, it.x, it.y, it.width) }, a.texts.map { listOf(it.id, it.text, it.x, it.y, it.width) }, "the texts of ${e.id}")
        assertEquals(e.customElements.map { listOf(it.id, it.type, it.x, it.y, it.width, it.height) }, a.customElements.map { listOf(it.id, it.type, it.x, it.y, it.width, it.height) }, "the elements of ${e.id}")
        e.customElements.zip(a.customElements).forEach { (ec, ac) -> assertContentEquals(ec.payload, ac.payload) }
        assertEquals(e.pdfs.map { listOf(it.id, it.resourceId, it.pdfPageIndex) }, a.pdfs.map { listOf(it.id, it.resourceId, it.pdfPageIndex) }, "the PDF pages of ${e.id}")
    }
    assertEquals(2, outline.pages[0].texts.size)
    assertEquals(2, outline.pages[1].texts.size, "the text erased is not read")
    assertEquals(listOf(4), outline.pages[0].pdfs.map { it.pdfPageIndex })
    assertEquals(listOf("formula"), outline.pages[0].customElements.map { it.type })
    assertEquals(expected.resources.map { listOf(it.id, it.type, it.path, it.hash) }, outline.resources.map { listOf(it.id, it.type, it.path, it.hash) })
    assertEquals(pdf.hash, assertNotNull(outline.findResource(pdf.id)).hash)
}

private operator fun <T> List<T>.component3(): T = this[2]
