package io.github.valerioisufi.drawview.explorer

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelStoreOwner
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.ui.adaptive.ReferenceWindow
import java.util.Locale
import java.util.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Screenshots of the texts (docs/text-editing.md): a text being written, the texts as the page
 * draws them, a text selected.
 */
@OptIn(ExperimentalTestApi::class)
class TextScreenshotTest {

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** A text being written: its frame, its field, the formatting bar. */
    @Test
    fun textWriting() = WINDOWS.forEach { window ->
        capture("text-writing", window, dark = false) { viewModels ->
            openNote(viewModels)
            write(viewModels, 20f, 30f, "Area of a circle: \$\\pi r^2\$\n**Bold** and *italic*")
        }
    }

    /** The caret in a formula: its source in the line, the formula rendered above it. */
    @Test
    fun textWritingFormula() = capture("text-writing-formula", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 20f, 30f, "Area: \$\\pi r^2\$ and \$\\frac{a}{b}\$ end")
        caretAfter(viewModels, "\\pi r")
    }

    /** A block of code away from the caret, drawn as the page draws it, and code in the line on its background. */
    @Test
    fun textWritingCode() = capture("text-writing-code", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 20f, 30f, CODE, drawnWidthMm = 120f)
    }

    /** The caret in a block of code: its source, its fences faded, in the colors of its language on its background. */
    @Test
    fun textWritingCodeSource() = capture("text-writing-code-source", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 20f, 30f, CODE, drawnWidthMm = 120f)
        caretAfter(viewModels, "squares(")
    }

    /** A formula that cannot be read: red source in the line, and the reason above it when the caret is in it. */
    @Test
    fun textWritingInvalidFormula() = capture("text-writing-invalid-formula", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 20f, 30f, "Good \$x^2\$, bad \$\\nocommand{x}\$ and \$\\alsobad\$")
        caretAfter(viewModels, "\\noc")
    }

    /** Texts drawn by the page: aligned in their box, with a formula in the line and one on a line of its own. */
    @Test
    fun textSaved() = capture("text-saved", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 20f, 30f, "Area of a circle: \$\\pi r^2\$ and **bold**")
        save(viewModels)
        write(viewModels, 20f, 60f, "Centered title", TextAlignment.CENTER, drawnWidthMm = 90f)
        save(viewModels)
        write(viewModels, 20f, 80f, "The integral\n\$\$\\int_0^1 x^2\\,dx = \\frac{1}{3}\$\$\nis a third.")
        save(viewModels)
    }

    /**
     * Code drawn by the page (docs/code-blocks.md): in the line on its background, a block in the
     * colors of its language with a long line wrapped under its indentation, a tab and an empty
     * line, a block without a language at the start of a centered text.
     */
    @Test
    fun textSavedCode() = capture("text-saved-code", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        val kotlin = "Call `listOf()` to make a list:\n```kotlin\n// The squares of the even numbers\nfun squares(n: Int): List<Int> {\n" +
            "\tval evens = (1..n).filter { it % 2 == 0 }\n\n\treturn evens.map { it * it } // each one times itself, a comment long enough to wrap\n}\n```\nand **after** it."
        write(viewModels, 20f, 20f, kotlin, drawnWidthMm = 120f)
        save(viewModels)
        write(viewModels, 20f, 125f, "Centered\n```\nno language: one color\n```", TextAlignment.CENTER, drawnWidthMm = 90f)
        save(viewModels)
    }

    /** A text selected: the frame of the selection. */
    @Test
    fun textSelected() = WINDOWS.forEach { window ->
        capture("text-selected", window, dark = false) { viewModels ->
            openNote(viewModels)
            write(viewModels, 20f, 30f, "Area of a circle: \$\\pi r^2\$")
            save(viewModels)
            runOnIdle {
                val controller = viewModels.controller()
                controller.selectTool(Tool.LAZO)
                controller.selectionManager.selectText(0, controller.document.pages[0].texts.single())
            }
        }
    }

    /** A text being moved, snapped to the left edge of another one: the guide shown. */
    @Test
    fun textSnapped() = capture("text-snapped", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 30f, 30f, "A first note")
        save(viewModels)
        write(viewModels, 70f, 90f, "Moved")
        save(viewModels)
        runOnIdle {
            val controller = viewModels.controller()
            val (first, second) = controller.document.pages[0].texts
            controller.selectTool(Tool.LAZO)
            controller.selectionManager.selectText(0, second)
            controller.selectionManager.beginTransformation()
            // Its left edge brought half a millimeter from the one of the first note
            controller.selectionManager.moveBy(first.x - second.x + 0.5f, -20f)
        }
    }

    /** A turned text being written: its field and its frame turned with it. */
    @Test
    fun textWritingTurned() = capture("text-writing-turned", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 20f, 30f, "A turned note")
        save(viewModels)
        runOnIdle {
            val controller = viewModels.controller()
            val text = controller.document.pages[0].texts.single()
            controller.selectionManager.selectText(0, text)
            controller.selectionManager.beginTransformation()
            controller.selectionManager.rotateBy(-12f)
            controller.selectionManager.applyTransformation()
            controller.selectionManager.clearSelection()
            controller.textEditorManager.startEditing(text, 0)
        }
    }

    /** Starts a text at ([xMm], [yMm]) of the first page and types [text] in its field. */
    private fun DesktopComposeUiTest.write(
        viewModels: ViewModelStoreOwner,
        xMm: Float,
        yMm: Float,
        text: String,
        alignment: TextAlignment = TextAlignment.START,
        drawnWidthMm: Float? = null
    ) {
        runOnIdle {
            val controller = viewModels.controller()
            controller.selectTool(Tool.TEXT)
            // Tapped (its width following the text), or drawn [drawnWidthMm] wide
            if (drawnWidthMm == null) {
                controller.textEditorManager.startNewText(0, xMm, yMm)
            } else {
                controller.textEditorManager.startDrawnText(0, Rect(xMm, yMm, xMm + drawnWidthMm, yMm + 10f))
            }
            controller.textEditorManager.changeAlignment(alignment)
        }
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput(text)
        waitForIdle()
    }

    /** Puts the caret of the text being written after the first [text] in it. */
    private fun DesktopComposeUiTest.caretAfter(viewModels: ViewModelStoreOwner, text: String) {
        runOnIdle {
            val editor = viewModels.controller().textEditorManager
            val offset = editor.value.text.indexOf(text) + text.length
            editor.updateValue(editor.value.copy(selection = TextRange(offset)))
        }
        waitForIdle()
    }

    private fun DesktopComposeUiTest.save(viewModels: ViewModelStoreOwner) {
        runOnIdle { viewModels.controller().textEditorManager.finishEditing() }
        waitForIdle()
    }

    private companion object {
        val WINDOWS = listOf(ReferenceWindow.PHONE, ReferenceWindow.TABLET_PORTRAIT, ReferenceWindow.DESKTOP)

        /** A text with a block of code and code in the line. */
        const val CODE = "Squares:\n```kotlin\nfun squares(n: Int) = (1..n).map { it * it }\n\tval list = squares(10) // a comment\n```\nThen `run` it."

    }
}
