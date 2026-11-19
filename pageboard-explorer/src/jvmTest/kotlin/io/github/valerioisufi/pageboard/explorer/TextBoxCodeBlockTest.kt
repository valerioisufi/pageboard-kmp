package io.github.valerioisufi.pageboard.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A block of code stays a block while its code is written (docs/plans/text-fidelity.md): the arrows go
 * through its code without stalling on its fences, up and down through its lines, and nothing of the
 * text moves. The walks are those of the starting point of the plan.
 */
@OptIn(ExperimentalTestApi::class)
class TextBoxCodeBlockTest {

    private val text = "Sopra il blocco\n```kotlin\nfun a() = 1\n  val b = 2\n```\nSotto il blocco\nUltima riga"
    private val firstCode = text.indexOf("fun")
    private val secondCode = text.indexOf("  val")

    private fun DesktopComposeUiTest.fieldLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun DesktopComposeUiTest.press(key: Key) {
        onNode(hasSetTextAction()).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.caret(controller: PageboardController, offset: Int) {
        runOnIdle { controller.textEditorManager.updateValue(controller.textEditorManager.value.copy(selection = TextRange(offset))) }
        waitForIdle()
    }

    /** The offsets the caret goes through, [presses] presses of [key] from [from]; nothing of the text moving. */
    private fun DesktopComposeUiTest.walk(controller: PageboardController, from: Int, key: Key, presses: Int): List<Int> {
        caret(controller, from)
        val shown = fieldLayout().layoutInput.text.text
        val height = fieldBounds().height
        val offsets = mutableListOf<Int>()
        repeat(presses) { press ->
            val before = controller.textEditorManager.value.selection.start
            press(key)
            val now = controller.textEditorManager.value.selection.start
            offsets += now
            assertEquals(shown, fieldLayout().layoutInput.text.text, "the text shown the same at press ${press + 1}")
            assertEquals(height, fieldBounds().height, "the field as high at press ${press + 1}")
            if (now == before) return offsets
        }
        return offsets
    }

    private fun DesktopComposeUiTest.writeText(controller: PageboardController) {
        startBox(controller, 30f, 30f, widthMm = 120f)
        type(text)
    }

    @Test
    fun theRightArrowGoesThroughTheCodeAndOverTheFences() = textBoxScene { controller ->
        writeText(controller)
        val offsets = walk(controller, text.indexOf("\n```"), Key.DirectionRight, 40)
        assertEquals(firstCode, offsets.first(), "from the line before into the first character of the code")
        val closing = text.indexOf("\n```\n") + 1
        assertTrue(offsets.none { it in text.indexOf("```") until firstCode || it in closing until closing + 3 }, "never on a fence: $offsets")
        assertTrue(text.indexOf("Sotto") in offsets, "on to the line after: $offsets")
        assertEquals(offsets.size, offsets.toSet().size, "every press moves: $offsets")
    }

    @Test
    fun theLeftArrowGoesBackThroughTheCode() = textBoxScene { controller ->
        writeText(controller)
        val offsets = walk(controller, text.indexOf("Sotto"), Key.DirectionLeft, 40)
        assertEquals(text.indexOf("\n```\n"), offsets.first(), "from the line after to the end of the code")
        assertTrue(text.indexOf("\n```kotlin") in offsets, "on to the end of the line before: $offsets")
        assertEquals(offsets.size, offsets.toSet().size, "every press moves: $offsets")
    }

    @Test
    fun upFromTheLineBelowGoesThroughTheLinesOfTheCode() = textBoxScene { controller ->
        writeText(controller)
        // From Ultima: Sotto, the second line of code, the first one, Sopra
        val offsets = walk(controller, text.indexOf("Ultima") + 3, Key.DirectionUp, 5)
        assertTrue(offsets[0] in text.indexOf("Sotto") until text.indexOf("\nUltima"), "the line below the block: $offsets")
        assertTrue(offsets.any { it in secondCode..text.indexOf("\n```\n") }, "the second line of code: $offsets")
        assertTrue(offsets.any { it in firstCode until secondCode }, "the first line of code: $offsets")
        assertTrue(offsets.last() < text.indexOf("\n```kotlin") + 1, "on to the line above: $offsets")
    }

    @Test
    fun downFromTheLineAboveGoesThroughTheLinesOfTheCode() = textBoxScene { controller ->
        writeText(controller)
        val offsets = walk(controller, 3, Key.DirectionDown, 4)
        assertTrue(offsets[0] in firstCode until secondCode, "into the first line of code: $offsets")
        assertTrue(offsets[1] in secondCode..text.indexOf("\n```\n"), "the second line of code: $offsets")
        assertTrue(offsets[2] in text.indexOf("Sotto") until text.indexOf("\nUltima"), "the line below: $offsets")
    }

    @Test
    fun backspaceAtTheStartOfTheCodeOpensTheFenceInTheStrip() = textBoxScene { controller ->
        writeText(controller)
        caret(controller, firstCode)
        press(Key.Backspace)
        assertEquals(text, controller.textEditorManager.value.text, "nothing deleted")
        val strip = onAllNodes(hasText("```kotlin", substring = true)).fetchSemanticsNodes().any { node -> generateSequence(node) { it.parent }.any { hasTestTag("source-strip").matches(it) } }
        assertTrue(strip, "the fence in the strip")
    }

    @Test
    fun theArrowsOutOfABlockEndingTheTextAddALineAfterIt() = textBoxScene { controller ->
        val ending = "Sopra il blocco\n```kotlin\nfun a() = 1\n  val b = 2\n```"
        startBox(controller, 30f, 30f, widthMm = 120f)
        type(ending)
        // → at the end of the code
        caret(controller, ending.indexOf("\n```", ending.indexOf("val")))
        press(Key.DirectionRight)
        assertEquals("$ending\n", controller.textEditorManager.value.text, "a line after the block")
        assertEquals(TextRange(ending.length + 1), controller.textEditorManager.value.selection)
        type("Sotto")
        assertEquals("$ending\nSotto", controller.textEditorManager.value.text, "written after the block, not in its code")
        // ↓ on its last line, the line after it there: no line added
        caret(controller, ending.indexOf("val"))
        press(Key.DirectionDown)
        assertEquals("$ending\nSotto", controller.textEditorManager.value.text)
        assertTrue(controller.textEditorManager.value.selection.start > ending.length, "on the line after: ${controller.textEditorManager.value.selection}")
    }

    @Test
    fun downOnTheLastLineOfABlockEndingTheTextAddsALine() = textBoxScene { controller ->
        val ending = "Sopra\n```\nuno\ndue\n```"
        startBox(controller, 30f, 30f, widthMm = 120f)
        type(ending)
        caret(controller, ending.indexOf("due") + 1)
        press(Key.DirectionDown)
        assertEquals("$ending\n", controller.textEditorManager.value.text)
        assertEquals(TextRange(ending.length + 1), controller.textEditorManager.value.selection)
    }

    @Test
    fun theArrowsBackOutOfABlockStartingTheTextAddALineBeforeIt() = textBoxScene { controller ->
        val starting = "```\nuno\ndue\n```\nSotto"
        startBox(controller, 30f, 30f, widthMm = 120f)
        type(starting)
        caret(controller, starting.indexOf("uno"))
        press(Key.DirectionLeft)
        assertEquals("\n$starting", controller.textEditorManager.value.text, "a line before the block")
        assertEquals(TextRange(0), controller.textEditorManager.value.selection)
    }

    private fun DesktopComposeUiTest.tap(at: Offset) {
        onRoot().performTouchInput { click(at) }
        waitForIdle()
    }

    @Test
    fun aTapJustBelowABlockEndingTheTextAddsALineAfterIt() = textBoxScene { controller ->
        val ending = "Sopra il blocco\n```kotlin\nfun a() = 1\n```"
        startBox(controller, 30f, 30f, widthMm = 120f)
        type(ending)
        // On the frame, and a little further
        for (below in listOf(4f, 30f)) {
            runOnIdle { controller.textEditorManager.updateValue(TextFieldValue(ending, TextRange(0)), smartNewline = false) }
            waitForIdle()
            val field = fieldBounds()
            tap(Offset(field.center.x, field.bottom + below))
            assertTrue(controller.textEditorManager.isEditing, "still written, $below px below")
            assertEquals("$ending\n", controller.textEditorManager.value.text, "a line after the block, $below px below")
            assertEquals(TextRange(ending.length + 1), controller.textEditorManager.value.selection)
        }
        type("Sotto")
        assertEquals("$ending\nSotto", controller.textEditorManager.value.text, "written after the block")
    }

    @Test
    fun aTapFarBelowABlockEndingTheTextEndsTheWriting() = textBoxScene { controller ->
        startBox(controller, 30f, 30f, widthMm = 120f)
        type("Sopra\n```\nuno\n```")
        val field = fieldBounds()
        tap(Offset(field.center.x, field.bottom + 200f))
        assertTrue(!controller.textEditorManager.isEditing, "saved")
    }

    @Test
    fun aDragInTheBlockSelectsItsCode() = textBoxScene { controller ->
        writeText(controller)
        val layout = fieldLayout()
        val field = fieldBounds()
        val scale = field.width / layout.size.width
        // The line of the block, from a little inside its left to its middle
        val line = layout.getLineForOffset(layout.layoutInput.text.text.indexOf('￼'))
        val y = field.top + (layout.getLineTop(line) + 0.45f * (layout.getLineBottom(line) - layout.getLineTop(line))) * scale
        onRoot().performMouseInput {
            moveTo(Offset(field.left + 60f * scale, y))
            press()
            moveTo(Offset(field.left + 400f * scale, y))
            moveTo(Offset(field.left + 800f * scale, y))
            release()
        }
        waitForIdle()
        val selection = controller.textEditorManager.value.selection
        assertTrue(!selection.collapsed && selection.min >= firstCode && selection.max <= text.indexOf("\n```\n"), "a part of the code selected: $selection")
    }
}
