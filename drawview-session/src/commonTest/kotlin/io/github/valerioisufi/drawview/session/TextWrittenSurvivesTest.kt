package io.github.valerioisufi.drawview.session

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * The text of a box still open survives the process killed (see `docs/text-autosave.md`): what the
 * store has is read by another process, nothing closed or flushed before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextWrittenSurvivesTest {
    private val main = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(main)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** The editor of the document [documentId] of [documents], open. */
    private fun TestScope.editorOf(documents: OpenDocuments, documentId: String): DrawViewController {
        val viewModel = DocumentEditorViewModel(documents)
        viewModel.openDocument(documentId)
        advanceUntilIdle()
        val controller = assertNotNull(viewModel.session?.controller)
        controller.viewport.setSize(800f, 1200f)
        return controller
    }

    private fun DrawViewController.write(text: String) {
        textEditorManager.startDrawnText(0, Rect(20f, 30f, 120f, 40f))
        textEditorManager.updateValue(TextFieldValue(text, TextRange(text.length)))
    }

    @Test
    fun aTextBeingWrittenIsInTheStoreWhenTheProcessIsKilled() = runTest(main) {
        val storage = InMemoryKeyValueStorage()
        // What dies with the process: the writes of the store, the documents open
        val process = CoroutineScope(SupervisorJob() + main)
        val store = KeyValueDocumentStore(storage, scope = process)
        val documentId = store.createDocument("Notes", 210f, 297f, PageBackground.Solid(), null).id
        val controller = editorOf(OpenDocuments(store, main, process), documentId)

        controller.write("a line typed in a box still open")
        // A pause in the writing, and the time the store takes to write
        advanceTimeBy(2_000 + 500)
        runCurrent()
        assertTrue(controller.textEditorManager.isEditing, "the box is still open")
        process.cancel()

        // Another process, on the same storage
        val again = KeyValueDocumentStore(storage, scope = backgroundScope)
        val document = assertNotNull(again.loadDocument(documentId))
        assertEquals(listOf("a line typed in a box still open"), document.pages.single().texts.map { it.text })
    }
}
