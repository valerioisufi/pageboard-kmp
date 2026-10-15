package io.github.valerioisufi.drawview.explorer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.adaptive.InputHints
import io.github.valerioisufi.drawview.ui.adaptive.PlatformConventions
import io.github.valerioisufi.drawview.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.drawview.ui.adaptive.platformConventions
import io.github.valerioisufi.drawview.ui.command.CommandHost
import io.github.valerioisufi.drawview.ui.command.CommandRoot
import io.github.valerioisufi.drawview.ui.session.DocumentEditorViewModel
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/**
 * The keyboard shortcuts of the library and of the editor, pressed on the interface: the root of
 * the commands takes the focus and runs the commands of the screen shown.
 */
@OptIn(ExperimentalTestApi::class)
class ShortcutsTest {

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    /** The modifier of the shortcuts on this system (⌘ on a Mac, Ctrl elsewhere). */
    private val primaryKey = if (platformConventions.shortcutModifier == ShortcutModifier.COMMAND) Key.MetaLeft else Key.CtrlLeft

    private fun KeyInjectionScope.pressPrimary(key: Key) = withKeyDown(primaryKey) { pressKey(key) }

    @Test
    fun theLibraryCreatesANoteWithItsShortcut() = library { _, _ ->
        onRoot().performKeyInput { pressPrimary(Key.N) }
        waitForIdle()
        // The sheet of a new note (the floating button says "New note" too)
        onNodeWithText("Create note").assertExists()
    }

    @Test
    fun theEditorChoosesItsToolsAndShowsItsPagesWithTheKeys() = library { viewModels, host ->
        onNodeWithText("Note").performClick()
        val editor = viewModels.viewModelStore.keys().firstNotNullOf { viewModels.viewModelStore[it] as? DocumentEditorViewModel }
        val end = System.currentTimeMillis() + 20_000
        while (editor.session?.controller == null) {
            check(System.currentTimeMillis() < end) { "The note did not open" }
            Thread.sleep(20)
            mainClock.advanceTimeByFrame()
        }
        waitForIdle()
        val controller = editor.session!!.controller!!
        // The editor registered its commands in place of those of the library
        assertTrue(host.commands.any { it.id == "tool-eraser" })
        assertTrue(host.commands.none { it.id == "new-note" })

        onRoot().performKeyInput { pressKey(Key.E) }
        waitForIdle()
        assertEquals(Tool.ERASER, controller.selectedTool)

        onRoot().performKeyInput { pressPrimary(Key.G) }
        waitForIdle()
        assertTrue(controller.isPageGridVisible)
    }

    /** A library with a note, its commands in [host]. */
    private fun library(test: DesktopComposeUiTest.(ViewModelStoreOwner, CommandHost) -> Unit) = runDesktopComposeUiTest(1440, 900) {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage())
        store.createDocument("Note")
        val viewModels = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val host = CommandHost()
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides viewModels) {
                DrawViewTheme(dynamicColor = false) {
                    AdaptiveRoot(hints = InputHints.PointerAndKeyboard, conventions = platformConventions) {
                        CommandRoot(host = host) {
                            // The documents read on the thread asking (the scene of a test has no main thread)
                            DocumentLibrary(
                                title = { Text("DrawView") },
                                documents = remember { OpenDocuments(store, dispatcher = Dispatchers.Unconfined) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        test(viewModels, host)
        viewModels.viewModelStore.clear()
    }
}
