package io.github.valerioisufi.pageboard.store.testing

import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.store.DocumentInk
import io.github.valerioisufi.pageboard.store.DocumentStore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What every [DocumentStore] reads of the strokes of a document without modeling them
 * ([DocumentStore.loadInk]): the ink of the document it loads whole, its pages shown and their
 * strokes shown in their order, with their samples and placement. Run by the tests of each store,
 * of Pageboard or of an application, on an empty store.
 */
public suspend fun checkInk(store: DocumentStore) {
    assertNull(store.loadInk("missing"), "no document")

    val document = SyntheticDocuments.notebook(pages = 3, strokesPerPage = 12, textsPerPage = 1, removedFraction = 0.25f, title = "Ink")
    // A page removed: kept, hidden
    val (first, second, third) = document.pages
    document.restorePageOrder(listOf(first, second))
    third.removed = Stamp(3L, 0, "ink")
    document.removedPages[third.id] = third
    store.insertDocument(document)

    val expected = DocumentInk.of(assertNotNull(store.loadDocument(document.id)))
    val ink = assertNotNull(store.loadInk(document.id))
    assertEquals(document.id, ink.id)
    assertEquals(listOf(first.id, second.id), ink.pages.map { it.id }, "the pages shown, in their order")
    for ((e, a) in expected.pages.zip(ink.pages)) {
        assertEquals(e.strokes.map { it.id }, a.strokes.map { it.id }, "the strokes shown of ${e.id}, in their order")
        for ((es, aa) in e.strokes.zip(a.strokes)) {
            assertEquals(es.points, aa.points, "the samples of ${es.id}")
            assertEquals(es.transform, aa.transform, "the placement of ${es.id}")
            assertEquals(listOf(es.tool, es.size, es.color, es.inputToolType), listOf(aa.tool, aa.size, aa.color, aa.inputToolType), "the brush of ${es.id}")
        }
    }
    assertEquals(true, ink.pages.all { it.strokes.isNotEmpty() }, "the strokes are read")
}

private operator fun <T> List<T>.component3(): T = this[2]
