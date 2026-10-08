package io.github.valerioisufi.drawview.explorer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.data.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.data.KeyValueDocumentStore
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.adaptive.PlatformConventions
import io.github.valerioisufi.drawview.ui.adaptive.ReferenceWindow
import io.github.valerioisufi.drawview.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.drawview.ui.session.DocumentEditorViewModel
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Screenshots of the library and of the editor in the reference windows ([ReferenceWindow]), with
 * their input, in the light theme and, on a phone and a desktop, in the dark one: the reference of
 * the adaptive interface (docs/adaptive-ui.md).
 *
 * `./gradlew :drawview-explorer:recordRoborazziJvm` records them in `screenshots/`,
 * `verifyRoborazziJvm` compares the interface with them, `compareRoborazziJvm` writes the
 * differences in `build/outputs/roborazzi`.
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveScreenshotTest {

    @BeforeTest
    fun setUp() {
        // The same strings and dates on every computer
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test
    fun library() = forEachConfiguration { window, dark ->
        capture("library", window, dark)
    }

    /** The editor opens in drawing mode: the bar of the tools under the top bar. */
    @Test
    fun editor() = forEachConfiguration { window, dark ->
        capture("editor", window, dark) { viewModels -> openNote(viewModels) }
    }

    @Test
    fun documentBackgroundSheet() = listOf(ReferenceWindow.PHONE, ReferenceWindow.TABLET_LANDSCAPE, ReferenceWindow.DESKTOP).forEach { window ->
        capture("document-background", window, dark = false) { viewModels ->
            openNote(viewModels)
            onNodeWithContentDescription("More options").performClick()
            onNodeWithText("Document Background").performClick()
        }
    }

    /** A note clicked with the secondary button of a mouse: its commands in a menu. */
    @Test
    fun libraryContextMenu() = capture("library-context-menu", ReferenceWindow.DESKTOP, dark = false) {
        onNodeWithText(NOTE).performMouseInput { rightClick(Offset(240f, centerY)) }
    }

    /** The pointer resting on a button of the editor: its tooltip, with the shortcut. */
    @Test
    fun editorTooltip() = capture("editor-tooltip", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        onNodeWithContentDescription("Grid View").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(1_000)
    }

    /** Every reference window in the light theme, the phone and the desktop in the dark one too. */
    private fun forEachConfiguration(capture: (ReferenceWindow, dark: Boolean) -> Unit) {
        ReferenceWindow.entries.forEach { capture(it, false) }
        listOf(ReferenceWindow.PHONE, ReferenceWindow.DESKTOP).forEach { capture(it, true) }
    }

    /**
     * Shows a library with folders and notes in [window], does [actions] and records the window
     * once the pages are drawn, as `screenshots/<screen>-<window>-<theme>.png`.
     */
    private fun capture(
        screen: String,
        window: ReferenceWindow,
        dark: Boolean,
        actions: DesktopComposeUiTest.(viewModels: ViewModelStoreOwner) -> Unit = {}
    ) = runDesktopComposeUiTest(width = window.width.value.toInt(), height = window.height.value.toInt()) {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = storeScope, clock = { FIXED_TIME })
        store.createFolder("Analysis", parentId = null)
        store.createFolder("Physics", parentId = null)
        store.createDocument(NOTE, background = PageBackground.Ruled())
        store.createDocument("Exercises", background = PageBackground.Grid())
        store.createDocument("Lab report")
        val viewModels = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }

        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides viewModels) {
                DrawViewTheme(darkTheme = dark, dynamicColor = false) {
                    // The shortcuts written as on a Mac on every system
                    AdaptiveRoot(hints = window.input, conventions = PlatformConventions(ShortcutModifier.COMMAND)) {
                        DocumentLibrary(
                            title = { Text("DrawView", modifier = Modifier.padding(horizontal = 16.dp)) },
                            store = store,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        waitForIdle()
        actions(viewModels)
        awaitPagesDrawn(viewModels)

        val theme = if (dark) "dark" else "light"
        captureToImage().captureRoboImage("screenshots/$screen-${window.name.lowercase().replace('_', '-')}-$theme.png")
        viewModels.viewModelStore.clear()
        storeScope.cancel()
    }

    /** Opens [NOTE] in the editor. */
    private fun DesktopComposeUiTest.openNote(viewModels: ViewModelStoreOwner) {
        // Below the fold of a short window (a phone in landscape)
        onNode(hasScrollToNodeAction()).performScrollToNode(hasText(NOTE))
        onNodeWithText(NOTE).performClick()
        // While the document loads, its progress bar never lets the interface be idle
        awaitInRealTime { viewModels.editor()?.session?.controller != null }
        waitForIdle()
    }

    /** Waits for the tiles of the open document (if any) to be rasterized and drawn. */
    private fun DesktopComposeUiTest.awaitPagesDrawn(viewModels: ViewModelStoreOwner) {
        // The animations end first (the bar of the tools appearing resizes the canvas)
        waitForIdle()
        val tiles = viewModels.editor()?.session?.controller?.tileManager ?: return
        // A tile drawn may ask for others (a sharper level): ten frames in a row with none to draw
        var quietFrames = 0
        awaitInRealTime {
            quietFrames = if (tiles.isRasterizing) 0 else quietFrames + 1
            quietFrames >= 10
        }
        waitForIdle()
    }

    /**
     * Waits for [condition], the frames going on: the documents are loaded and the tiles drawn by
     * other threads, in real time, while the clock of the test is virtual.
     */
    private fun DesktopComposeUiTest.awaitInRealTime(timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out after $timeoutMillis ms" }
            Thread.sleep(20)
            mainClock.advanceTimeByFrame()
        }
    }

    /** The editor of the library, once a document has been opened. */
    private fun ViewModelStoreOwner.editor(): DocumentEditorViewModel? =
        viewModelStore.keys().firstNotNullOfOrNull { viewModelStore[it] as? DocumentEditorViewModel }

    private companion object {
        /** The note opened in the editor. */
        const val NOTE = "Lecture 1 — Limits"

        /** 14 September 2026, 9:30 UTC: the date of every change. */
        const val FIXED_TIME = 1_789_378_200_000L
    }
}
