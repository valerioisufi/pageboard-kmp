package io.github.valerioisufi.drawview.explorer

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.latex.LatexEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A formula stays drawn in its place while its source is written, in a strip by it; so does a link,
 * its address in the strip (docs/text-fidelity.md). Nothing of the text moves.
 */
@OptIn(ExperimentalTestApi::class)
class TextBoxSourceStripTest {

    private val latex = "\\int_0^1 \\frac{x^2 + 2x + 1}{\\sqrt{x + 1}} \\, dx = \\sum_{n=0}^{\\infty} a_n"
    private val text = "Prima riga di testo\nLa formula \$$latex\$ nel testo della riga\nUna [guida](https://example.com) da leggere\nUltima riga"
    private val formulaStart = text.indexOf("\$")
    private val formulaEnd = text.indexOf("\$", formulaStart + 1) + 1

    private fun DesktopComposeUiTest.fieldLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun DesktopComposeUiTest.caret(controller: DrawViewController, offset: Int) {
        runOnIdle { controller.textEditorManager.updateValue(controller.textEditorManager.value.copy(selection = TextRange(offset))) }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.press(key: Key) {
        onNode(hasSetTextAction()).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.writeText(controller: DrawViewController) {
        startBox(controller, 20f, 40f, widthMm = 120f)
        type(text)
        caret(controller, 0)
    }

    private fun DesktopComposeUiTest.stripShows(source: String): Boolean =
        onAllNodes(hasText(source, substring = true)).fetchSemanticsNodes().any { node -> generateSequence(node) { it.parent }.any { hasTestTag("source-strip").matches(it) } }

    @Test
    fun theCaretInALongFormulaMovesNothing() = textBoxScene(engines = FORMULAS) { controller ->
        writeText(controller)
        val shown = fieldLayout().layoutInput.text.text
        val height = fieldBounds().height
        caret(controller, text.indexOf("frac") + 2)
        assertEquals(shown, fieldLayout().layoutInput.text.text, "the same text shown")
        assertEquals(height, fieldBounds().height, "the field as high")
        assertTrue(stripShows(latex), "its source in the strip")
    }

    @Test
    fun theArrowsGoThroughTheSourceOfAFormulaAndOut() = textBoxScene(engines = FORMULAS) { controller ->
        writeText(controller)
        val shown = fieldLayout().layoutInput.text.text
        caret(controller, formulaStart)
        press(Key.DirectionRight)
        assertEquals(formulaStart + 1, controller.textEditorManager.value.selection.start, "into its source")
        repeat(latex.length) { press(Key.DirectionRight) }
        assertEquals(formulaEnd - 1, controller.textEditorManager.value.selection.start, "at the end of its source")
        press(Key.DirectionRight)
        assertEquals(formulaEnd, controller.textEditorManager.value.selection.start, "out of it")
        press(Key.DirectionLeft)
        assertEquals(formulaEnd - 1, controller.textEditorManager.value.selection.start, "back into it")
        assertEquals(shown, fieldLayout().layoutInput.text.text, "nothing moved")
    }

    @Test
    fun backspaceAfterAFormulaGoesIntoItsSource() = textBoxScene(engines = FORMULAS) { controller ->
        writeText(controller)
        caret(controller, formulaEnd)
        press(Key.Backspace)
        assertEquals(text, controller.textEditorManager.value.text, "nothing deleted")
        assertEquals(TextRange(formulaEnd - 1), controller.textEditorManager.value.selection)
        assertTrue(stripShows(latex))
    }

    @Test
    fun aClickOnAFormulaOpensItsSource() = textBoxScene(engines = FORMULAS) { controller ->
        writeText(controller)
        val layout = fieldLayout()
        val field = fieldBounds()
        val scale = field.width / layout.size.width
        // The middle of the characters standing for the formula
        val box = layout.getBoundingBox(layout.layoutInput.text.text.indexOf('￼'))
        val on = field.topLeft + androidx.compose.ui.geometry.Offset(box.left + 20f, box.center.y) * scale
        onRoot().performMouseInput { click(on) }
        waitForIdle()
        assertEquals(TextRange(formulaEnd - 1), controller.textEditorManager.value.selection)
    }

    @Test
    fun theCaretInALinkShowsItsAddressInTheStrip() = textBoxScene { controller ->
        writeText(controller)
        val shown = fieldLayout().layoutInput.text.text
        caret(controller, text.indexOf("uida"))
        assertEquals(shown, fieldLayout().layoutInput.text.text, "its address not in the line")
        assertTrue(stripShows("https://example.com"))
    }

    private companion object {
        val FORMULAS: DrawViewEngines by lazy {
            runBlocking { LatexEngine.initialize() }
            DrawViewEngines(formulas = LatexEngine.formulaRenderer)
        }
    }
}
