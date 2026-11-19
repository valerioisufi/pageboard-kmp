package io.github.valerioisufi.drawview.session

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.session.EditorSessions
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * The screen of the editor saves the text being written when it stops (the application in the
 * background) and when it goes (a window or an activity closed).
 */
@OptIn(ExperimentalTestApi::class)
class EditorSessionScreenSavesTest {

    /** A lifecycle moved by the test, as the activity or the window moves theirs. */
    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private class Scene(val controller: DrawViewController, val lifecycle: TestLifecycle, val hide: () -> Unit) {
        /** The texts of the document, as saved in it. */
        val texts: List<String> get() = controller.document.pages.single().texts.filter { it.removed == null }.map { it.text }

        fun write(text: String) {
            controller.textEditorManager.startDrawnText(0, Rect(20f, 30f, 120f, 40f))
            controller.textEditorManager.updateValue(TextFieldValue(text, TextRange(text.length)))
        }
    }

    private fun scene(block: DesktopComposeUiTest.(Scene) -> Unit) = runDesktopComposeUiTest(width = 1000, height = 800) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val documents = OpenDocuments(store, dispatcher = Dispatchers.Unconfined)
        val documentId = runBlocking { store.createDocument("Notes", 210f, 297f, PageBackground.Solid(), null).id }
        val viewModel = DocumentEditorViewModel(documents, EditorSessions())
        val lifecycle = TestLifecycle()
        var shown by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                DrawViewTheme(darkTheme = false, dynamicColor = false) {
                    AdaptiveRoot(modifier = Modifier.fillMaxSize()) {
                        val session = viewModel.session
                        if (shown && session != null) EditorSessionScreen(session, onClose = {})
                    }
                }
            }
        }
        viewModel.openDocument(documentId)
        val end = System.currentTimeMillis() + 20_000
        while (viewModel.session?.controller == null && System.currentTimeMillis() < end) {
            Thread.sleep(20)
            waitForIdle()
        }
        waitForIdle()
        val controller = checkNotNull(viewModel.session?.controller)
        try {
            block(Scene(controller, lifecycle) { shown = false })
        } finally {
            runOnIdle { viewModel.closeSession() }
            scope.cancel()
        }
    }

    @Test
    fun theTextIsSavedWhenTheScreenStops() = scene { scene ->
        runOnIdle { scene.write("written, then the application in the background") }
        waitForIdle()
        assertTrue(scene.texts.isEmpty(), "not saved yet")
        runOnIdle { scene.lifecycle.registry.currentState = Lifecycle.State.CREATED }
        waitForIdle()
        assertEquals(listOf("written, then the application in the background"), scene.texts)
        assertTrue(scene.controller.textEditorManager.isEditing, "the box still open")
    }

    @Test
    fun theTextIsSavedWhenTheEditorGoes() = scene { scene ->
        runOnIdle { scene.write("written, then the window closed") }
        waitForIdle()
        assertTrue(scene.texts.isEmpty(), "not saved yet")
        runOnIdle { scene.hide() }
        waitForIdle()
        assertEquals(listOf("written, then the window closed"), scene.texts)
    }
}
