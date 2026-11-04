package io.github.valerioisufi.drawview.explorer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.adaptive.PlatformConventions
import io.github.valerioisufi.drawview.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.screen.EditorScreen
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** The clipboard of a text box scene: [text] is what a paste reads. */
@OptIn(ExperimentalComposeUiApi::class)
internal class TextBoxClipboard(var text: String? = null) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = text?.let { ClipEntry(StringSelection(it)) }
    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
    override val nativeClipboard = java.awt.datatransfer.Clipboard("text box scene")
}

/**
 * The editor of a document kept in memory, in a window of 1200 × 900 with its own [clipboard], for
 * the behavior of the text boxes under real pointers and keys.
 */
@OptIn(ExperimentalTestApi::class)
internal fun textBoxScene(clipboard: TextBoxClipboard = TextBoxClipboard(), block: DesktopComposeUiTest.(DrawViewController) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
    val documents = OpenDocuments(store, dispatcher = Dispatchers.Unconfined)
    val open = runBlocking {
        val id = store.createDocument("Text boxes", background = PageBackground.Ruled()).id
        checkNotNull(documents.open(id))
    }
    val controller = DrawViewController(document = open.document, coroutineScope = scope, persistence = store.persistence, sharedSession = open.session)
    runDesktopComposeUiTest(width = 1200, height = 900) {
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                DrawViewTheme(darkTheme = false, dynamicColor = false) {
                    AdaptiveRoot(conventions = PlatformConventions(ShortcutModifier.COMMAND)) {
                        EditorScreen(context = EditorContext(controller), onClose = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        waitForIdle()
        try {
            block(controller)
        } finally {
            controller.dispose()
            runBlocking { documents.close(open) }
            scope.cancel()
        }
    }
}

/** Zooms by [factor] and starts a text in the box drawn at the corner ([xMm], [yMm]) of the first page, [widthMm] wide. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.startBox(controller: DrawViewController, xMm: Float, yMm: Float, widthMm: Float, factor: Float = 1f) {
    runOnIdle {
        controller.selectTool(Tool.TEXT)
        if (factor != 1f) controller.viewport.camera.onDrag(0f, 0f, factor, 600f, 450f)
        controller.textEditorManager.startDrawnText(0, Rect(xMm, yMm, xMm + widthMm, yMm + 10f))
    }
    waitForIdle()
}

/** Types [text] in the field of the text being written. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.type(text: String) {
    onNode(hasSetTextAction()).performTextInput(text)
    waitForIdle()
}

/** Brings the corner of the box written to ([x], [y]) of the canvas. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.bringBoxTo(controller: DrawViewController, x: Float, y: Float) {
    runOnIdle {
        val editor = controller.textEditorManager
        val corner = checkNotNull(controller.viewport.pageMmToScreen(editor.activeTextPageIndex)).mapPoint(editor.boxX, editor.boxY)
        controller.viewport.panBy(x - corner.x, y - corner.y)
    }
    waitForIdle()
}

/** The field of the text being written, in the pixels of the window. */
@OptIn(ExperimentalTestApi::class)
internal fun DesktopComposeUiTest.fieldBounds(): Rect = onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
