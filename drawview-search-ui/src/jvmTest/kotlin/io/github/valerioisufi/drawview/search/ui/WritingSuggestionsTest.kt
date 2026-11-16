package io.github.valerioisufi.drawview.search.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.search.SearchAnchor
import io.github.valerioisufi.drawview.search.SearchChunk
import io.github.valerioisufi.drawview.search.SearchChunkKind
import io.github.valerioisufi.drawview.search.SearchIndex
import io.github.valerioisufi.drawview.search.SearchSource
import io.github.valerioisufi.drawview.store.DocumentSummary
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.screen.EditorScreen
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The words of the slides of a document, indexed by the search, suggested while writing in its
 * editor (`docs/plans/writing-suggestions.md`): the whole way, from the index to the text box.
 */
@OptIn(ExperimentalTestApi::class)
class WritingSuggestionsTest {

    /** The slides of every document: a text layer that names Dijkstra twice. */
    private class Slides : SearchSource {
        override val id: String = "slides"
        override val changes: Flow<Unit> get() = emptyFlow()
        override suspend fun version(document: DocumentSummary): String = "1"
        override suspend fun chunks(document: DocumentSummary): List<SearchChunk> = listOf(
            SearchChunk(id, SearchChunkKind.PDF, SearchAnchor.Page(document.id, "page"), document.name, "L'algoritmo di Dijkstra. Dijkstra trova il cammino minimo.")
        )
    }

    private fun DesktopComposeUiTest.type(text: String) {
        onNode(hasSetTextAction()).performTextInput(text)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.pause() {
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }

    private fun DesktopComposeUiTest.tab() {
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
    }

    private fun scene(enabled: Boolean, block: DesktopComposeUiTest.(DrawViewController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val search = createContentSearch(store, extraSources = listOf(Slides()), indexPath = SearchIndex.MEMORY_PATH)
        val documents = OpenDocuments(store, dispatcher = Dispatchers.Unconfined)
        val open = runBlocking {
            val id = store.createDocument("Grafi").id
            search.start(scope)
            // The slides indexed
            withTimeout(10_000) { search.index.revision.first { search.index.texts(id, setOf(SearchChunkKind.PDF)).isNotEmpty() } }
            checkNotNull(documents.open(id))
        }
        val controller = DrawViewController(document = open.document, coroutineScope = scope, persistence = store.persistence, sharedSession = open.session)
        controller.updateTextSuggestionsEnabled(enabled)
        runDesktopComposeUiTest(width = 1200, height = 900) {
            setContent {
                DrawViewTheme(darkTheme = false, dynamicColor = false) {
                    EditorScreen(context = EditorContext(controller), onClose = {}, modifier = Modifier.fillMaxSize(), extension = search.editorExtension)
                }
            }
            waitForIdle()
            runOnIdle {
                controller.selectTool(Tool.TEXT)
                controller.textEditorManager.startDrawnText(0, Rect(30f, 40f, 150f, 50f))
            }
            waitForIdle()
            try {
                block(controller)
            } finally {
                controller.dispose()
                runBlocking { documents.close(open) }
                scope.cancel()
                search.close()
            }
        }
    }

    /** Asks once, so that the words of the document are built, and lets them be built. */
    private fun DesktopComposeUiTest.warmUp() {
        type("Dij")
        pause()
        Thread.sleep(300)
        type(" ")
    }

    @Test
    fun aWordOfTheSlidesIsSuggestedAndTakenByTab() = scene(enabled = true) { controller ->
        warmUp()
        type("il cammino di Dij")
        pause()
        tab()
        runOnIdle { assertEquals("Dij il cammino di Dijkstra", controller.textEditorManager.value.text) }
    }

    @Test
    fun turnedOffNothingIsSuggested() = scene(enabled = false) { controller ->
        warmUp()
        type("il cammino di Dij")
        pause()
        tab()
        runOnIdle { assertEquals(false, controller.textEditorManager.value.text.endsWith("Dijkstra")) }
    }
}
