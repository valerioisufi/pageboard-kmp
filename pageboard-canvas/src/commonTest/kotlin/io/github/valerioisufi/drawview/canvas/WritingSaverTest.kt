package io.github.valerioisufi.drawview.canvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.controller.TextEditorManager
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.change.CommittedChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** When the text being written is saved by itself (see `WritingSaver`). */
class WritingSaverTest {

    private fun controller(scope: CoroutineScope): DrawViewController {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = scope)
        controller.viewport.setSize(800f, 1200f)
        return controller
    }

    private fun TextEditorManager.type(text: String) = updateValue(TextFieldValue(text, TextRange(text.length)))

    private fun committed(controller: DrawViewController) = mutableListOf<CommittedChange>().also { list ->
        controller.session.addObserver { list.add(it) }
    }

    private val DrawViewController.savedTexts: List<String> get() = document.pages[0].texts.map { it.text }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun theTextIsSavedAfterAPauseInTheWriting() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startDrawnText(0, Rect(20f, 30f, 120f, 40f))
        editor.type("a pause")
        wait(1_900)
        assertTrue(controller.savedTexts.isEmpty(), "not yet")
        editor.type("a pause, then")
        wait(1_900)
        assertTrue(controller.savedTexts.isEmpty(), "the pause starts again at each change")
        wait(200)
        assertEquals(listOf("a pause, then"), controller.savedTexts)
        assertTrue(editor.isEditing)
    }

    @Test
    fun aWritingWithoutPausesIsSavedToo() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        editor.startDrawnText(0, Rect(20f, 30f, 120f, 40f))
        var written = ""
        // A key every 1.5 s, never a pause of 2 s
        repeat(6) {
            written += "x"
            editor.type(written)
            wait(1_500)
        }
        assertTrue(controller.savedTexts.isEmpty())
        written += "y"
        editor.type(written)
        // 10.1 s from the first key, 1.1 s from the last one
        wait(1_100)
        assertEquals(listOf("x".repeat(6) + "y"), controller.savedTexts, "saved at 10 s from the first change")
    }

    @Test
    fun nothingIsSavedOnceTheWritingEndsOrWhenNothingChanged() = runTest(UnconfinedTestDispatcher()) {
        val controller = controller(backgroundScope)
        val editor = controller.textEditorManager
        val text = DrawText().apply {
            text = "stored"
            x = 20f
            y = 30f
            width = 100f
        }
        controller.document.pages[0].add(text)
        val changes = committed(controller)
        editor.startEditing(text, 0)
        editor.reportLayoutHeight(12f)
        wait(20_000)
        assertTrue(changes.isEmpty(), "opened, laid out, not changed")

        editor.type("stored, changed")
        editor.finishEditing()
        assertEquals(1, changes.size)
        wait(20_000)
        assertEquals(1, changes.size, "nothing after the end")
    }
}
