package io.github.valerioisufi.drawview.explorer

import io.github.valerioisufi.drawview.store.space.StoredSpace
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.session.DocumentEditorViewModel
import io.github.valerioisufi.drawview.session.EditorSessions
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.adaptive.PlatformConventions
import io.github.valerioisufi.drawview.ui.adaptive.ReferenceWindow
import io.github.valerioisufi.drawview.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.drawview.ui.extension.EditorExtension
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import io.github.valerioisufi.drawview.ui.window.LocalWindowDecorInsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// The scenes of the screenshot tests: a library with folders and notes, a note opened in the
// editor, the window recorded once the pages are drawn.

/**
 * Shows a library with folders and notes in [window], does [actions] and records the window
 * once the pages are drawn, as `screenshots/<screen>-<window>-<theme>.png`.
 *
 * @param clipboard the text in the clipboard of the scene, which has its own: what the computer
 * holds is neither shown (the paste of the menus) nor replaced.
 * @param macWindow a window of macOS: the insets of its traffic lights ([LocalWindowDecorInsets],
 * as `DrawViewWindowDecor` gives them), the lights drawn over the window where macOS puts them,
 * recorded as `<screen>-mac-<window>-<theme>.png`.
 */
@OptIn(ExperimentalTestApi::class)
internal fun capture(
    screen: String,
    window: ReferenceWindow,
    dark: Boolean,
    extension: EditorExtension? = null,
    clipboard: String? = null,
    macWindow: Boolean = false,
    /** Run once the pages and the thumbnails are drawn, right before the capture. */
    beforeCapture: DesktopComposeUiTest.() -> Unit = {},
    actions: DesktopComposeUiTest.(viewModels: ViewModelStoreOwner) -> Unit = {}
) = runDesktopComposeUiTest(width = window.width.value.toInt(), height = window.height.value.toInt()) {
    val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // The same dates and the same folders (their tones follow their ids) at every run
    var folders = 0
    val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = storeScope, clock = { FIXED_TIME }, newFolderId = { "folder-${++folders}" })
    store.createFolder("Analysis", parentId = null)
    store.createFolder("Physics", parentId = null)
    // Physics shared with other devices (docs/shared-folders.md): its badge
    store.saveSpace(StoredSpace("space", rootFolderId = "folder-2"))
    store.createDocument(NOTE, background = PageBackground.Ruled())
    store.createDocument("Exercises", background = PageBackground.Grid())
    store.createDocument("Lab report")
    val viewModels = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    // The thumbnails of the grid, in memory, drawn from the documents of the library; the
    // documents read on the thread asking (the scene of a test has no main thread)
    val documents = OpenDocuments(store, dispatcher = Dispatchers.Unconfined)
    val thumbnails = ThumbnailsHolder()
    // The clipboard read by the editor (the one of every platform)
    @Suppress("DEPRECATION")
    val sceneClipboard = LocalClipboardManager provides SceneClipboard(clipboard)

    setContent {
        // Five days after the changes: what went to the trash has 25 days left
        CompositionLocalProvider(LocalViewModelStoreOwner provides viewModels, sceneClipboard, LocalLibraryClock provides { FIXED_TIME + 5 * DAY }) {
            DrawViewTheme(darkTheme = dark, dynamicColor = false) {
                // The shortcuts written as on a Mac on every system
                AdaptiveRoot(hints = window.input, conventions = PlatformConventions(ShortcutModifier.COMMAND)) {
                    CompositionLocalProvider(LocalWindowDecorInsets provides if (macWindow) MAC_DECOR_INSETS else PaddingValues(0.dp)) {
                        DocumentLibrary(
                            title = { Text("DrawView", modifier = Modifier.padding(horizontal = 16.dp)) },
                            documents = documents,
                            sessions = remember { EditorSessions() },
                            // The PDF pages and the formulas drawn as in the applications
                            engines = ENGINES,
                            thumbnails = rememberDocumentThumbnails(documents, directory = null, engines = ENGINES).also { thumbnails.value = it },
                            editorExtension = extension,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (macWindow) TrafficLights()
                }
            }
        }
    }
    waitForIdle()
    actions(viewModels)
    awaitPagesDrawn(viewModels, thumbnails.value)
    beforeCapture()

    val theme = if (dark) "dark" else "light"
    val mac = if (macWindow) "-mac" else ""
    captureToImage().captureRoboImage("screenshots/$screen$mac-${window.name.lowercase().replace('_', '-')}-$theme.png")
    viewModels.viewModelStore.clear()
    storeScope.cancel()
}

/** The insets of the traffic lights in a window of macOS, as `DrawViewWindowDecor` gives them. */
private val MAC_DECOR_INSETS = PaddingValues(start = 78.dp, top = 52.dp)

/**
 * The traffic lights of a window of macOS in the title bar of a toolbar: 14 points wide, 6 apart,
 * from 19 points of the edge, centered 26 points from the top (see `docs/window-integration.md`).
 */
@Composable
private fun TrafficLights() {
    Row(modifier = Modifier.padding(start = 19.dp, top = 19.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (color in listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840))) {
            Box(modifier = Modifier.size(14.dp).background(color, CircleShape))
        }
    }
}

/** Opens [NOTE] in the editor. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.openNote(viewModels: ViewModelStoreOwner) {
    // Below the fold of a short window (a phone in landscape): the list or the grid holding it
    // (a wide library also scrolls its pane of the folders)
    val containers = onAllNodes(hasScrollToNodeAction())
    for (index in containers.fetchSemanticsNodes().indices) {
        if (runCatching { containers[index].performScrollToNode(hasText(NOTE)) }.isSuccess) break
    }
    onNodeWithText(NOTE).performClick()
    // While the document loads, its progress bar never lets the interface be idle
    awaitInRealTime { viewModels.editor()?.session?.controller != null }
    waitForIdle()
    // The mouse leaves the canvas for the title of the top bar: no cursor of the pen where it
    // clicked the note
    onRoot().performMouseInput { moveTo(Offset(centerX, 4f)) }
    waitForIdle()
}

/** The engines of the applications: PDFium and MicroTeX. */
private val ENGINES = DrawViewEngines(pdfTiles = PdfiumEngine::createTileAdapter, formulas = LatexEngine())

/** The thumbnails of the library of a test. */
private class ThumbnailsHolder {
    var value: DocumentThumbnails? = null
}

/** The clipboard of a scene: [text] at first, then the text set last. */
@Suppress("DEPRECATION")
private class SceneClipboard(private var text: String?) : ClipboardManager {
    override fun setText(annotatedString: AnnotatedString) {
        text = annotatedString.text
    }

    override fun getText(): AnnotatedString? = text?.let(::AnnotatedString)
}

/** Waits for the tiles of the open document (if any) and the thumbnails of the grid to be drawn. */
@OptIn(ExperimentalTestApi::class)
private fun DesktopComposeUiTest.awaitPagesDrawn(viewModels: ViewModelStoreOwner, thumbnails: DocumentThumbnails?) {
    // The animations end first (the bar of the tools appearing resizes the canvas)
    waitForIdle()
    val tiles = viewModels.editor()?.session?.controller?.tileManager
    // A tile drawn may ask for others (a sharper level), a thumbnail drawn lets the next one be:
    // ten frames in a row with none to draw
    var quietFrames = 0
    awaitInRealTime {
        val drawing = tiles?.isRasterizing == true || (thumbnails?.rendering ?: 0) > 0
        quietFrames = if (drawing) 0 else quietFrames + 1
        quietFrames >= 10
    }
    waitForIdle()
}

/**
 * Waits for [condition], the frames going on: the documents are loaded and the tiles drawn by
 * other threads, in real time, while the clock of the test is virtual.
 */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.awaitInRealTime(timeoutMillis: Long = 20_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        check(System.currentTimeMillis() < end) { "Timed out after $timeoutMillis ms" }
        Thread.sleep(20)
        mainClock.advanceTimeByFrame()
    }
}

/** The editor of the library, once a document has been opened. */
internal fun ViewModelStoreOwner.editor(): DocumentEditorViewModel? =
    viewModelStore.keys().firstNotNullOfOrNull { viewModelStore[it] as? DocumentEditorViewModel }

/** The library. */
internal fun ViewModelStoreOwner.library(): FileExplorerViewModel =
    viewModelStore.keys().firstNotNullOf { viewModelStore[it] as? FileExplorerViewModel }

/** The controller of the document open in the editor. */
internal fun ViewModelStoreOwner.controller(): DrawViewController = checkNotNull(editor()?.session?.controller)

/** The note opened in the editor. */
internal const val NOTE = "Lecture 1 — Limits"

/** 14 September 2026, 9:30 UTC: the date of every change. */
private const val FIXED_TIME = 1_789_378_200_000L

/** A day, in milliseconds. */
private const val DAY = 24L * 60 * 60 * 1000
