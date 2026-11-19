package io.github.valerioisufi.drawview.canvas

import io.github.valerioisufi.drawview.canvas.camera.DocumentPlace
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** The place a document is looked at, kept as text and shown again (docs/plans/last-place.md). */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentPlaceTest {

    // --- The format ---

    @Test
    fun aPlaceIsReadAgainAsItWasWritten() {
        val place = DocumentPlace(pageId = "p7", pageNumber = 7, x = 0.25f, y = 0.8f, scale = 2.5f)
        assertEquals(place, DocumentPlace.decode(place.encode()))
        assertTrue(place.encode().contains("\"v\":1"))
    }

    @Test
    fun aTextNotUnderstoodGivesNoPlace() {
        assertNull(DocumentPlace.decode(""))
        assertNull(DocumentPlace.decode("not json"))
        assertNull(DocumentPlace.decode("{}"))
        assertNull(DocumentPlace.decode("""{"pageId":"a","pageNumber":1,"x":0.5,"y":0.5}"""))
        // Another version of the format
        assertNull(DocumentPlace.decode("""{"pageId":"a","pageNumber":1,"x":0.5,"y":0.5,"scale":1,"v":2}"""))
        // Values out of their range
        assertNull(DocumentPlace.decode("""{"pageId":"a","pageNumber":0,"x":0.5,"y":0.5,"scale":1,"v":1}"""))
        assertNull(DocumentPlace.decode("""{"pageId":"a","pageNumber":1,"x":0.5,"y":0.5,"scale":0,"v":1}"""))
        assertNull(DocumentPlace.decode("""{"pageId":"a","pageNumber":1,"x":0.5,"y":0.5,"scale":-2,"v":1}"""))
    }

    @Test
    fun aPointOutsideThePageIsClampedAndUnknownFieldsIgnored() {
        val place = assertNotNull(
            DocumentPlace.decode("""{"pageId":"a","pageNumber":2,"x":1.7,"y":-3,"scale":1.5,"v":1,"later":true}""")
        )
        assertEquals(1f, place.x)
        assertEquals(0f, place.y)
        assertEquals(1.5f, place.scale)
    }

    // --- The viewport ---

    private fun document(pages: Int): DrawDocument = DrawDocument().apply { repeat(pages - 1) { addPage() } }

    private fun controller(doc: DrawDocument, scope: CoroutineScope, laidOut: Boolean = true): DrawViewController =
        DrawViewController(document = doc, coroutineScope = scope).apply {
            if (laidOut) viewport.setSize(800f, 1200f)
        }

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.01f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")

    /** Zooms in by [factor] around the center of the canvas, as a pinch does. */
    private fun DrawViewController.zoom(factor: Float) {
        viewport.camera.onDrag(0f, 0f, factor, viewport.width / 2f, viewport.height / 2f)
        viewport.camera.restoreToBounds(animated = false)
    }

    @Test
    fun theDocumentIsShownAgainWhereItWasLeft() = runTest(UnconfinedTestDispatcher()) {
        val doc = document(10)
        val first = controller(doc, backgroundScope)
        first.pageManager.jumpToPage(6)
        first.zoom(2f)
        val place = assertNotNull(first.viewport.currentPlace())
        assertEquals(doc.pages[6].id, place.pageId)
        assertEquals(7, place.pageNumber)
        assertNear(2f, place.scale)

        // Opened again: the place given before the canvas is laid out
        val again = controller(doc, backgroundScope, laidOut = false)
        assertTrue(again.viewport.showPlace(DocumentPlace.decode(place.encode())!!))
        assertEquals(place, again.viewport.currentPlace())
        again.viewport.setSize(800f, 1200f)
        assertEquals(6, again.viewport.mostVisiblePageIndex())
        assertNear(place.scale, again.viewport.camera.getCurrentScale())
        val shown = assertNotNull(again.viewport.currentPlace())
        assertNear(place.x, shown.x)
        assertNear(place.y, shown.y)
    }

    @Test
    fun aPlaceIsShownAtOnceWhenTheCanvasIsLaidOut() = runTest(UnconfinedTestDispatcher()) {
        val doc = document(5)
        val controller = controller(doc, backgroundScope)
        controller.viewport.showPlace(DocumentPlace(doc.pages[3].id, 4, 0.5f, 0.5f, 1.5f))
        assertEquals(3, controller.viewport.mostVisiblePageIndex())
        assertNear(1.5f, controller.viewport.camera.getCurrentScale())
    }

    @Test
    fun aPageMovedIsFoundByItsId() = runTest(UnconfinedTestDispatcher()) {
        val doc = document(6)
        val place = DocumentPlace(doc.pages[4].id, 5, 0.5f, 0.3f, 1f)
        // Two pages added before it meanwhile (here or on another device)
        val controller = controller(doc, backgroundScope, laidOut = false)
        controller.pageManager.addNewPageAfter(0)
        controller.pageManager.addNewPageAfter(0)
        controller.viewport.showPlace(place)
        controller.viewport.setSize(800f, 1200f)
        assertEquals(6, controller.viewport.mostVisiblePageIndex())
    }

    @Test
    fun aPageDeletedFallsBackToItsNumberAndToTheLastPage() = runTest(UnconfinedTestDispatcher()) {
        val doc = document(6)
        val byNumber = controller(doc, backgroundScope, laidOut = false)
        byNumber.viewport.showPlace(DocumentPlace("gone", 3, 0.5f, 0.5f, 1f))
        byNumber.viewport.setSize(800f, 1200f)
        assertEquals(2, byNumber.viewport.mostVisiblePageIndex())

        val beyond = controller(doc, backgroundScope, laidOut = false)
        beyond.viewport.showPlace(DocumentPlace("gone", 40, 0.5f, 0.5f, 1f))
        beyond.viewport.setSize(800f, 1200f)
        assertEquals(5, beyond.viewport.mostVisiblePageIndex())
    }

    @Test
    fun anotherWindowSizeKeepsThePointAndTheZoom() = runTest(UnconfinedTestDispatcher()) {
        val doc = document(8)
        val place = DocumentPlace(doc.pages[5].id, 6, 0.4f, 0.6f, 2f)
        val controller = controller(doc, backgroundScope, laidOut = false)
        controller.viewport.showPlace(place)
        controller.viewport.setSize(1400f, 900f)
        assertEquals(5, controller.viewport.mostVisiblePageIndex())
        assertNear(2f, controller.viewport.camera.getCurrentScale())
        val shown = assertNotNull(controller.viewport.currentPlace())
        assertNear(0.4f, shown.x)
        assertNear(0.6f, shown.y)
    }

    @Test
    fun aJumpAskedAfterThePlaceWins() = runTest(UnconfinedTestDispatcher()) {
        val doc = document(8)
        val controller = controller(doc, backgroundScope, laidOut = false)
        controller.viewport.showPlace(DocumentPlace(doc.pages[5].id, 6, 0.5f, 0.5f, 2f))
        // E.g. a search result opening the document
        controller.pageManager.jumpToPage(2)
        controller.viewport.setSize(800f, 1200f)
        assertEquals(2, controller.viewport.mostVisiblePageIndex())
        assertNear(1f, controller.viewport.camera.getCurrentScale())
    }

    @Test
    fun aZoomBeyondTheCameraIsBroughtWithinItsBounds() = runTest(UnconfinedTestDispatcher()) {
        val doc = document(3)
        val controller = controller(doc, backgroundScope, laidOut = false)
        controller.viewport.showPlace(DocumentPlace(doc.pages[1].id, 2, 0.5f, 0.5f, 10_000f))
        controller.viewport.setSize(800f, 1200f)
        assertTrue(controller.viewport.camera.getCurrentScale() < 10_000f)
        assertEquals(1, controller.viewport.mostVisiblePageIndex())
    }

    @Test
    fun noPlaceBeforeTheFirstLayout() = runTest(UnconfinedTestDispatcher()) {
        val doc = document(1)
        val controller = controller(doc, backgroundScope, laidOut = false)
        assertNull(controller.viewport.currentPlace())
        controller.viewport.setSize(800f, 1200f)
        assertNotNull(controller.viewport.currentPlace())
    }
}
