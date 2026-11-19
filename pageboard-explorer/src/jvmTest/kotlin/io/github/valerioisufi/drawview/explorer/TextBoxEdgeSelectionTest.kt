package io.github.valerioisufi.drawview.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A pointer selects the characters it passes over, those at the ends of a format too
 * (docs/plans/pointer-selection.md): the first and the last character of a bold or an italic text, alone,
 * by a mouse, and by the handles of a selection made by a finger.
 */
@OptIn(ExperimentalTestApi::class)
class TextBoxEdgeSelectionTest {

    /** The box of a text being written, with where its characters are in the window. */
    private class Written(val test: DesktopComposeUiTest, val controller: DrawViewController, val text: String) {
        private val layout: TextLayoutResult
        private val field = test.fieldBounds()
        private val scale: Float

        init {
            val results = mutableListOf<TextLayoutResult>()
            test.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            layout = results.single()
            scale = field.width / layout.size.width
        }

        /** The character at [index] of [shown], the text as the field shows it, in the window. */
        fun box(index: Int): Rect {
            val box = layout.getBoundingBox(index)
            return Rect(field.left + box.left * scale, field.top + box.top * scale, field.left + box.right * scale, field.top + box.bottom * scale)
        }

        /** The text as the field shows it. */
        val shown: String get() = layout.layoutInput.text.text

        /** What is selected, as written. */
        val selected: String get() = controller.textEditorManager.value.let { it.text.substring(it.selection.min, it.selection.max) }

        val selection: TextRange get() = controller.textEditorManager.value.selection

        /** The caret put at [offset] by the editor, as after typing there. */
        fun caretAt(offset: Int) {
            test.runOnIdle { controller.textEditorManager.updateValue(controller.textEditorManager.value.copy(selection = TextRange(offset))) }
            test.waitForIdle()
        }

        /** A mouse dragged along the line of [from] between two places of the window. */
        fun drag(from: Float, to: Float, y: Float) {
            test.onRoot().performMouseInput {
                // Not the second click of a double click
                advanceEventTime(1_000)
                moveTo(Offset(from, y))
                press()
                // Two moves on the place it ends at, as a hand slowing down
                moveTo(Offset((from + to) / 2f, y))
                moveTo(Offset(to, y))
                moveTo(Offset(to, y))
                release()
            }
            test.waitForIdle()
        }
    }

    private fun DesktopComposeUiTest.write(controller: DrawViewController, text: String): Written {
        startBox(controller, 20f, 40f, widthMm = 180f)
        type(text)
        return Written(this, controller, text)
    }

    /** [text] with the word of it whose first and last characters are tried. */
    private class Case(val text: String, val word: String)

    private val cases = listOf(
        Case("**bold** plain", "bold"),
        Case("plain **bold**", "bold"),
        Case("plain *ital* more", "ital"),
        Case("plain **bold**\nmore", "bold"),
        Case("**Bo**ld plain", "Bo"),
        Case("plain words", "plain")
    )

    @Test
    fun aMouseSelectsTheFirstCharacterOfAFormatAlone() = textBoxScene { controller ->
        for (case in cases) {
            val written = write(controller, case.text)
            val first = case.word.take(1)
            val box = written.box(written.shown.indexOf(case.word))
            written.caretAt(case.text.length)
            // From its left edge to a little after its right one
            written.drag(box.left + 1f, box.right + 2f, box.center.y)
            assertEquals(first, written.selected, "forward over the first character of \"${case.text}\"")
            written.caretAt(case.text.length)
            written.drag(box.right + 2f, box.left + 1f, box.center.y)
            assertEquals(first, written.selected, "back over the first character of \"${case.text}\"")
            runOnIdle { controller.textEditorManager.finishEditing() }
            waitForIdle()
        }
    }

    @Test
    fun aMouseSelectsTheLastCharacterOfAFormatAlone() = textBoxScene { controller ->
        for (case in cases) {
            val written = write(controller, case.text)
            val last = case.word.takeLast(1)
            val box = written.box(written.shown.indexOf(case.word) + case.word.length - 1)
            // With the caret after the closing delimiter, as when the text was just typed, and elsewhere
            val wordEnd = case.text.indexOf(case.word) + case.word.length
            for (caret in listOf(wordEnd + case.text.drop(wordEnd).takeWhile { it == '*' }.length, 0)) {
                written.caretAt(caret)
                written.drag(box.right - 1f, box.left - 2f, box.center.y)
                assertEquals(last, written.selected, "back over the last character of \"${case.text}\", the caret at $caret")
            }
            written.caretAt(0)
            written.drag(box.left - 2f, box.right - 1f, box.center.y)
            assertEquals(last, written.selected, "forward over the last character of \"${case.text}\"")
            runOnIdle { controller.textEditorManager.finishEditing() }
            waitForIdle()
        }
    }

    @Test
    fun aHandleHeldStillKeepsTheSelection() = textBoxScene { controller ->
        val text = "plain *ital* more"
        val written = write(controller, text)
        val t = written.box(written.shown.indexOf("ital") + 1)
        onRoot().performTouchInput {
            down(t.center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        waitForIdle()
        assertEquals("ital", written.selected)
        for (tag in listOf("text-handle-end", "text-handle-start")) {
            onNodeWithTag(tag).performTouchInput {
                down(center)
                repeat(4) { moveBy(Offset(if (it % 2 == 0) 1f else -1f, 0f)) }
                up()
            }
            waitForIdle()
            assertEquals("ital", written.selected, "after holding $tag")
        }
    }
}
