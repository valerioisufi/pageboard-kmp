package io.github.valerioisufi.pageboard.store.testing

import io.github.valerioisufi.pageboard.model.DrawResource
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.AddPage
import io.github.valerioisufi.pageboard.model.change.AddResource
import io.github.valerioisufi.pageboard.model.change.CompositeChange
import io.github.valerioisufi.pageboard.model.change.CustomElementData
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.DocumentSession
import io.github.valerioisufi.pageboard.model.change.ImageData
import io.github.valerioisufi.pageboard.model.change.PageData
import io.github.valerioisufi.pageboard.model.change.PlaceElements
import io.github.valerioisufi.pageboard.model.change.RemoveElements
import io.github.valerioisufi.pageboard.model.change.RemovePage
import io.github.valerioisufi.pageboard.model.change.RenameDocument
import io.github.valerioisufi.pageboard.model.change.ReorderElements
import io.github.valerioisufi.pageboard.model.change.SetDocumentDefaults
import io.github.valerioisufi.pageboard.model.change.SetPageFormat
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.change.StrokePlacement
import io.github.valerioisufi.pageboard.model.change.TextPlacement
import io.github.valerioisufi.pageboard.model.change.UpdateElementContent
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.change.toData
import io.github.valerioisufi.pageboard.model.geometry.AffineTransform
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.store.DocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers

/**
 * What every [DocumentStore] reads of a page of a document without loading the document: its
 * fingerprint ([DocumentStore.pageFingerprint]) changes with every change of the page as it is
 * drawn and with no change of another page, and the page loaded alone ([DocumentStore.loadPage])
 * is the one of the document loaded whole. Run by the tests of each store, of DrawView or of an
 * application, on an empty store.
 */
public suspend fun checkPageFingerprint(store: DocumentStore) {
    assertNull(store.pageFingerprint("missing", 0), "no document")
    assertNull(store.loadPage("missing", 0), "no document")

    val document = SyntheticDocuments.notebook(pages = 2, strokesPerPage = 6, textsPerPage = 1, title = "Fingerprint")
    store.insertDocument(document)
    val id = document.id
    assertNull(store.pageFingerprint(id, 2), "no third page")
    assertEquals(store.pageFingerprint(id, 0), store.pageFingerprint(id, 0), "read again, the same")
    assertNotEquals(store.pageFingerprint(id, 0), store.pageFingerprint(id, 1), "another page")

    val documents = OpenDocuments(store, Dispatchers.Unconfined)
    val open = assertNotNull(documents.open(id))

    /** Applies the change [make] makes of the session and returns the fingerprints of the first two pages, before and after. */
    suspend fun apply(what: String, make: (DocumentSession) -> DocumentChange): Pair<List<String?>, List<String?>> {
        val before = listOf(store.pageFingerprint(id, 0), store.pageFingerprint(id, 1))
        open.edit { session -> assertNotNull(session.submit(make(session)), "$what: applied") }
        open.flush()
        val after = listOf(store.pageFingerprint(id, 0), store.pageFingerprint(id, 1))
        assertEquals(after[0], store.pageFingerprint(id, 0), "$what: read again, the same")
        return before to after
    }

    suspend fun changesTheFirstPage(what: String, make: (DocumentSession) -> DocumentChange) {
        val (before, after) = apply(what, make)
        assertNotEquals(before[0], after[0], "$what: the first page changed")
    }

    suspend fun leavesTheFirstPage(what: String, make: (DocumentSession) -> DocumentChange) {
        val (before, after) = apply(what, make)
        assertEquals(before[0], after[0], "$what: the first page as it was")
    }

    changesTheFirstPage("a stroke added") { s -> AddElements.of(s.document.pages[0], listOf(stroke())) }
    changesTheFirstPage("a stroke erased") { s -> RemoveElements.of(s.document.pages[0], listOf(s.document.pages[0].strokes.first())) }
    changesTheFirstPage("a stroke erased shown again") { s ->
        val page = s.document.pages[0]
        AddElements(page.id, listOf(page.removedElements.first().toData()))
    }
    changesTheFirstPage("a stroke moved") { s ->
        val stroke = s.document.pages[0].strokes.first()
        PlaceElements(s.document.pages[0].id, listOf(StrokePlacement(stroke.id, AffineTransform(tx = 5f, ty = 3f))))
    }
    changesTheFirstPage("a stroke brought to the top") { s ->
        val page = s.document.pages[0]
        ReorderElements.onTop(page.id, listOf(page.strokes.first().id))
    }
    changesTheFirstPage("a stroke recolored") { s ->
        val page = s.document.pages[0]
        AddElements(page.id, listOf((page.strokes.last().toData() as StrokeData).copy(color = 0xFFFF0000.toInt())))
    }
    changesTheFirstPage("a text written") { s ->
        val page = s.document.pages[0]
        UpdateTexts(page.id, listOf(page.texts.first().toData().copy(text = "written again")))
    }
    changesTheFirstPage("a formula added") { s ->
        AddElements(s.document.pages[0].id, listOf(CustomElementData(newElementId(), "formula", 1, "x".encodeToByteArray(), 30f, 40f, 20f, 8f)))
    }
    changesTheFirstPage("a formula changed") { s ->
        val page = s.document.pages[0]
        UpdateElementContent(page.id, page.customElements.first().id, 1, "x^2".encodeToByteArray())
    }
    changesTheFirstPage("an image added") { s ->
        val resource = newElementId()
        CompositeChange(
            listOf(
                AddResource.of(resource, DrawResource.ResourceType.IMAGE, "fingerprint.png", "hash-of-the-image"),
                AddElements(s.document.pages[0].id, listOf(ImageData(newElementId(), resource, 20f, 20f, 40f, 30f)))
            )
        )
    }
    changesTheFirstPage("the default background of the document, followed by the page") { s ->
        SetDocumentDefaults(s.document.defaultWidthMm, s.document.defaultHeightMm, PageBackground.Grid())
    }
    changesTheFirstPage("the format of the page") { s -> SetPageFormat(s.document.pages[0].id, 148f, 210f, null) }
    changesTheFirstPage("the background of the page") { s -> SetPageFormat(s.document.pages[0].id, 148f, 210f, PageBackground.Dotted()) }

    leavesTheFirstPage("a stroke added to the second page") { s -> AddElements.of(s.document.pages[1], listOf(stroke())) }
    leavesTheFirstPage("a stroke of the second page erased") { s -> RemoveElements.of(s.document.pages[1], listOf(s.document.pages[1].strokes.first())) }
    leavesTheFirstPage("the format of the second page") { s -> SetPageFormat(s.document.pages[1].id, 297f, 210f, null) }
    leavesTheFirstPage("the default background, the page having its own") { s ->
        SetDocumentDefaults(s.document.defaultWidthMm, s.document.defaultHeightMm, PageBackground.Ruled())
    }
    leavesTheFirstPage("the document renamed") { RenameDocument("Fingerprint renamed") }

    val (beforeMove, afterMove) = apply("a text moved to the second page") { s ->
        val text = s.document.pages[0].texts.first()
        PlaceElements(
            s.document.pages[0].id,
            listOf(TextPlacement(text.id, text.x, text.y + 10f, text.width, text.rotation, text.fontSize, text.autoWidth)),
            toPageId = s.document.pages[1].id
        )
    }
    assertNotEquals(beforeMove[0], afterMove[0], "a text moved to the second page: the first page changed")
    assertNotEquals(beforeMove[1], afterMove[1], "a text moved to the second page: the second page changed")
    changesTheFirstPage("a page put before it") { s -> AddPage(PageData(newElementId(), s.document.orderKeyAt(0), 210f, 297f)) }
    changesTheFirstPage("the first page removed") { s -> RemovePage(s.document.pages[0].id) }
    assertEquals(afterMove[0], store.pageFingerprint(id, 0), "the first page first again, as it was")
    documents.close(open)

    // The pages loaded alone are the ones of the document loaded whole
    val whole = assertNotNull(store.loadDocument(id))
    assertEquals(2, whole.pages.size)
    assertNull(store.loadPage(id, whole.pages.size), "no third page")
    for ((index, expected) in whole.pages.withIndex()) {
        val page = assertNotNull(store.loadPage(id, index))
        val content = page.content
        assertEquals(expected.id, page.id)
        assertEquals(listOf(expected.widthMm, expected.heightMm), listOf(content.widthMm, content.heightMm), "the format of ${expected.id}")
        assertEquals(expected.background, content.background, "the background of ${expected.id}")
        assertEquals(expected.strokes.map { listOf(it.id, it.transform, it.brushConfig.color) }, content.strokes.map { listOf(it.id, it.transform, it.brushConfig.color) }, "the strokes of ${expected.id}")
        assertEquals(expected.texts.map { listOf(it.id, it.text, it.x, it.y) }, content.texts.map { listOf(it.id, it.text, it.x, it.y) }, "the texts of ${expected.id}")
        assertEquals(expected.images.map { listOf(it.id, it.resourceId) }, content.images.map { listOf(it.id, it.resourceId) }, "the images of ${expected.id}")
        assertEquals(expected.customElements.map { it.id }, content.customs.map { it.id }, "the formulas of ${expected.id}")
        assertEquals(expected.stack.map { it.id }, content.stack.map { it.id }, "the stack of ${expected.id}")
        assertEquals(whole.resources.map { listOf(it.id, it.path, it.hash) }, page.resources.map { listOf(it.id, it.path, it.hash) })
    }
    assertEquals(1, whole.pages[0].images.size, "the image shown")
}

private fun stroke() = StrokeData(
    id = newElementId(),
    points = List(20) { i -> StrokePoint(30f + i * 0.5f, 60f, 0.5f, i * 8L) },
    tool = StrokeTool.PRESSURE_PEN,
    size = 0.5f,
    color = 0xFF000000.toInt(),
    inputToolType = InputToolType.STYLUS
).toElement(SyntheticDocuments.NoMeshes)
