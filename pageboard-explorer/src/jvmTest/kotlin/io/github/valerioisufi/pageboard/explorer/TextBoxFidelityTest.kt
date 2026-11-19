package io.github.valerioisufi.pageboard.explorer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.canvas.render.PageboardEngines
import io.github.valerioisufi.pageboard.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.pageboard.latex.LatexEngine
import io.github.valerioisufi.pageboard.model.TextElement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * The text being written laid out as the page lays it out (docs/plans/text-fidelity.md): the lines of the
 * field of the editor, at every zoom, are those the page draws, also after a zoom or a new size
 * while the text is written; the caret is as wide on the screen at every zoom.
 */
@OptIn(ExperimentalTestApi::class)
class TextBoxFidelityTest {

    private val paragraphs = (1..8).joinToString("\n") { "Una riga di testo abbastanza lunga da andare a capo sulla larghezza della casella, come negli appunti." }

    /** The layout of the field of the text being written, in its pixels (20 per millimeter). */
    private fun DesktopComposeUiTest.fieldLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** The baselines of the lines with text of the field, in millimeters from the top of the box. */
    private fun DesktopComposeUiTest.fieldBaselines(): List<Float> {
        val layout = fieldLayout()
        val chars = layout.layoutInput.text.text
        return (0 until layout.lineCount).filter { line ->
            (layout.getLineStart(line) until layout.getLineEnd(line)).any { chars[it].isLetterOrDigit() }
        }.map { layout.getLineBaseline(it) / PX_PER_MM }
    }

    /** The baselines of the lines with text of the text being written as the page lays it out. */
    private fun pageBaselines(controller: PageboardController): List<Float> {
        val editor = controller.textEditorManager
        val text = TextElement().apply {
            text = editor.value.text
            fontSize = editor.fontSize
            width = editor.boxWidth
            alignment = editor.alignment
        }
        val layout = controller.textAdapter?.layout(text, TextTileAdapter.NO_PAPER) ?: fail("no text adapter")
        return layout.words.filter { word -> word.text.any { it.isLetterOrDigit() } }.map { it.baseline }.distinct().sorted()
    }

    private fun DesktopComposeUiTest.assertLinesOfThePage(controller: PageboardController, what: String) {
        val field = fieldBaselines()
        val page = pageBaselines(controller)
        assertEquals(page.size, field.size, "the lines $what: page $page, field $field")
        for ((p, f) in page.zip(field)) {
            if (abs(p - f) > TOLERANCE_MM) fail("a line $what at $f mm in the field, $p mm on the page: page $page, field $field")
        }
    }

    @Test
    fun theLinesWrittenAreThoseOfThePageAtEveryZoom() {
        for (factor in listOf(0.48f, 0.73f, 1f, 1.6f, 2.7f)) textBoxScene { controller ->
            startBox(controller, 20f, 20f, widthMm = 100f, factor = factor)
            type("# Appunti\n$paragraphs\n- una voce con `codice` in mezzo\n- un'altra **voce**")
            assertLinesOfThePage(controller, "at a zoom of $factor")
            // Its height is the page's
            val measured = controller.measureText(TextElement().apply {
                text = controller.textEditorManager.value.text
                fontSize = controller.textEditorManager.fontSize
                width = controller.textEditorManager.boxWidth
            })?.height ?: fail("not measured")
            assertEquals(measured, controller.textEditorManager.boxHeight, TOLERANCE_MM, "the height at a zoom of $factor")
        }
    }

    @Test
    fun aZoomOrANewSizeWhileWritingKeepsTheLinesOfThePage() = textBoxScene(engines = FORMULAS) { controller ->
        startBox(controller, 20f, 20f, widthMm = 120f)
        type("Testo \$\\frac{a}{b}\$ con formula \$x^2 + y^2\$ dentro\n\$\$\\int_0^1 x^2 dx\$\$\nE ancora \$\\sum_i i\$ testo\n# Titolo\ntesto")
        assertLinesOfThePage(controller, "written")
        runOnIdle { controller.viewport.camera.onDrag(0f, 0f, 1.6f, 300f, 200f) }
        waitForIdle()
        assertLinesOfThePage(controller, "after a zoom")
        runOnIdle { controller.textEditorManager.changeFontSize(20f) }
        waitForIdle()
        assertLinesOfThePage(controller, "at a new size")
    }

    /** How many columns of the caret's color the caret takes, [factor] times the zoom of the opening. */
    private fun caretColumns(factor: Float): Int {
        var columns = 0
        textBoxScene { controller ->
            startBox(controller, 30f, 30f, widthMm = 100f, factor = factor)
            type("Prima\n\nDopo")
            // The caret alone on the empty line
            runOnIdle { controller.textEditorManager.updateValue(controller.textEditorManager.value.copy(selection = TextRange(6))) }
            waitForIdle()
            val field = fieldBounds()
            val layout = fieldLayout()
            val scale = field.width / layout.size.width
            val y = (field.top + (layout.getLineTop(1) + layout.getLineBottom(1)) / 2f * scale).toInt()
            val pixels = onRoot().captureToImage().toPixelMap()
            for (x in field.left.toInt() - 4 until (field.left + 40f).toInt()) {
                val c = pixels[x, y]
                // The primary color of the theme: green, as no paper or text is
                if (c.green > c.red + 0.15f && c.green > c.blue + 0.15f && c.alpha > 0.5f) columns++
            }
        }
        return columns
    }

    @Test
    fun theCaretIsAsWideAtEveryZoom() {
        for (factor in listOf(0.4f, 1f, 3f)) {
            val columns = caretColumns(factor)
            assertTrue(columns in 1..3, "the caret ${columns} px wide at a zoom of $factor (2 dp)")
        }
    }

    private companion object {
        /** The resolution of the layouts of the page and of the field. */
        const val PX_PER_MM = 20f

        /** Half a pixel of the layout. */
        const val TOLERANCE_MM = 0.5f / PX_PER_MM

        val FORMULAS: PageboardEngines by lazy {
            val latex = LatexEngine()
            runBlocking { latex.initialize() }
            PageboardEngines(formulas = latex)
        }
    }
}
