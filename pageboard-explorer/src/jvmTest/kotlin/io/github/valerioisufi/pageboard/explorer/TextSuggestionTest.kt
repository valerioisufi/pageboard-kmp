package io.github.valerioisufi.pageboard.explorer

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.pageboard.ui.extension.EditorExtension
import io.github.valerioisufi.pageboard.ui.extension.TextSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals

/** The suggestion of an extension after the end of the text being written (docs/plans/text-box-hook.md). */
@OptIn(ExperimentalTestApi::class)
class TextSuggestionTest {

    /** Suggests the end of "through" after "thr", of "surface" after "sur". */
    private val words = EditorExtension {
        textSuggestions { _, writing ->
            when {
                writing.source.endsWith("thr") -> TextSuggestion("ough")
                writing.source.endsWith("sur") -> TextSuggestion("face")
                else -> null
            }
        }
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.pause() {
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }

    @Test
    fun tabTakesTheSuggestionShownAfterThePause() = textBoxScene(extension = words) { controller ->
        startBox(controller, 30f, 40f, 120f)
        type("The flux thr")
        pause()
        onRoot().captureRoboImage("screenshots/text-suggestion.png")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        runOnIdle { assertEquals("The flux through", controller.textEditorManager.value.text) }
    }

    @Test
    fun escapeDropsTheSuggestionAndTypingForgetsIt() = textBoxScene(extension = words) { controller ->
        startBox(controller, 30f, 40f, 120f)
        type("a closed sur")
        pause()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        runOnIdle { assertEquals(false, controller.textEditorManager.value.text.endsWith("face")) }

        type(" thr")
        type("x")
        pause()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        runOnIdle { assertEquals(false, controller.textEditorManager.value.text.contains("ough"), "the suggestion of a text typed over is gone") }
    }

    @Test
    fun withoutAnExtensionTabIndentsAsBefore() = textBoxScene { controller ->
        startBox(controller, 30f, 40f, 120f)
        runOnIdle { controller.textEditorManager.updateValue(TextFieldValue("- one\n- two", TextRange(11))) }
        pause()
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        runOnIdle {
            val second = controller.textEditorManager.value.text.lines()[1]
            assertEquals(true, second.first().isWhitespace(), "the second item indented: $second")
        }
    }
}
