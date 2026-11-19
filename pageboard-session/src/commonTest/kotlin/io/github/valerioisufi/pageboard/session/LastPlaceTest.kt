package io.github.valerioisufi.pageboard.session

import io.github.valerioisufi.pageboard.canvas.camera.DocumentPlace
import io.github.valerioisufi.pageboard.canvas.controller.DrawViewController
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.session.EditorSessions
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

/**
 * A document opens where it was left on this device (see `docs/plans/last-place.md`): each "process"
 * reads what the one before left in the storage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LastPlaceTest {
    private val main = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(main)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** An application running on [storage], until [quit]. */
    private inner class Process(storage: InMemoryKeyValueStorage) {
        val scope = CoroutineScope(SupervisorJob() + main)
        val store = KeyValueDocumentStore(storage, scope = scope)
        val viewModel = DocumentEditorViewModel(OpenDocuments(store, main, scope), EditorSessions())

        /** The editor open on the document [documentId], laid out; [then] as whoever opens it asks. */
        fun TestScope.open(documentId: String, then: (DrawViewController) -> Unit = {}): DrawViewController {
            viewModel.openDocument(documentId, then)
            advanceUntilIdle()
            val controller = assertNotNull(viewModel.session?.controller)
            controller.viewport.setSize(800f, 1200f)
            return controller
        }

        /** The document closed, everything written, then the process gone. */
        suspend fun TestScope.quit() {
            val session = assertNotNull(viewModel.session)
            session.notePlace()
            session.flush()
            viewModel.closeSession()
            advanceUntilIdle()
            scope.cancel()
        }
    }

    /** A storage holding a document of [pages] pages, and its id. */
    private suspend fun TestScope.documentOf(pages: Int): Pair<InMemoryKeyValueStorage, String> {
        val storage = InMemoryKeyValueStorage()
        val process = Process(storage)
        val documentId = process.store.createDocument("Notes", 210f, 297f, PageBackground.Solid(), null).id
        with(process) {
            val controller = open(documentId)
            repeat(pages - 1) { controller.pageManager.addNewPageAtBottom() }
            quit()
        }
        return storage to documentId
    }

    /** Moves the camera to the page [index] at [scale], as the user does, and lets it rest. */
    private fun TestScope.moveTo(controller: DrawViewController, index: Int, scale: Float = 1f) {
        val page = controller.document.pages[index]
        controller.viewport.showPlace(DocumentPlace(page.id, index + 1, 0.5f, 0.5f, scale))
        runCurrent()
    }

    private fun assertNear(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) <= 0.01f, "expected $expected, was $actual")

    @Test
    fun aDocumentClosedOpensAgainWhereItWasLeft() = runTest(main) {
        val (storage, documentId) = documentOf(pages = 8)
        with(Process(storage)) {
            moveTo(open(documentId), 5, scale = 2f)
            quit()
        }
        with(Process(storage)) {
            val again = open(documentId)
            assertEquals(5, again.viewport.mostVisiblePageIndex())
            assertNear(2f, again.viewport.camera.getCurrentScale())
            quit()
        }
    }

    @Test
    fun thePlaceIsKeptWhenTheScreenStops() = runTest(main) {
        val (storage, documentId) = documentOf(pages = 6)
        val process = Process(storage)
        val controller = with(process) { open(documentId) }
        moveTo(controller, 4)
        // The application in the background (see EditorSessionScreen): nothing closed or flushed
        assertNotNull(process.viewModel.session).saveWriting()
        val reader = KeyValueDocumentStore(storage, scope = backgroundScope)
        // Written beside the editor, on another thread
        val end = TimeSource.Monotonic.markNow() + 10.seconds
        var kept: DocumentPlace? = null
        while (kept?.pageNumber != 5 && end.hasNotPassedNow()) {
            withContext(Dispatchers.Default) { delay(10) }
            kept = reader.preference(placeKey(documentId))?.let(DocumentPlace::decode)
        }

        // The process killed: what the storage has
        process.scope.cancel()
        assertEquals(controller.document.pages[4].id, assertNotNull(kept).pageId)
        assertEquals(5, kept.pageNumber)
    }

    @Test
    fun aPlaceAskedWhenOpeningWins() = runTest(main) {
        val (storage, documentId) = documentOf(pages = 8)
        with(Process(storage)) {
            moveTo(open(documentId), 6)
            quit()
        }
        with(Process(storage)) {
            // E.g. a search result
            val again = open(documentId) { it.pageManager.jumpToPage(2) }
            assertEquals(2, again.viewport.mostVisiblePageIndex())
            quit()
        }
    }

    @Test
    fun aPlaceNotUnderstoodOpensTheTopOfTheDocument() = runTest(main) {
        val (storage, documentId) = documentOf(pages = 4)
        with(Process(storage)) {
            store.setPreference(placeKey(documentId), "{\"v\":99}")
            assertEquals(0, open(documentId).viewport.mostVisiblePageIndex())
            quit()
        }
    }

    @Test
    fun eachDocumentHasItsOwnPlace() = runTest(main) {
        val (storage, documentId) = documentOf(pages = 5)
        with(Process(storage)) {
            moveTo(open(documentId), 3)
            quit()
        }
        with(Process(storage)) {
            val other = store.createDocument("Other", 210f, 297f, PageBackground.Solid(), null).id
            assertEquals(0, open(other).viewport.mostVisiblePageIndex())
            quit()
        }
    }
}
