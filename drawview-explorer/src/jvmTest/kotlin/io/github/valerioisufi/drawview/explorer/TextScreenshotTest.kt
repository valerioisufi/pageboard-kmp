package io.github.valerioisufi.drawview.explorer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelStoreOwner
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.change.TextPlacement
import io.github.valerioisufi.drawview.model.change.PlaceElements
import io.github.valerioisufi.drawview.model.text.TextBackground
import io.github.valerioisufi.drawview.ui.adaptive.ReferenceWindow
import java.util.Locale
import java.util.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
     * line, a block without a language at the start of a centered text, a block with the numbers
     * of its lines.
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
        write(
            viewModels, 20f, 150f,
            "```python showLineNumbers\ndef area(r):\n    return 3.14159 * r * r  # the area of a circle of radius r, a long comment\n\nprint(area(2))\n```",
            drawnWidthMm = 120f
        )
        save(viewModels)
    }

    /** Blocks of code under the items of a list, nested too, aligned with the text of their item. */
    @Test
    fun textSavedCodeInList() = capture("text-saved-code-list", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        val list = "- Compile it:\n  ```sh\n  gcc main.c -o main\n  ```\n- Then:\n  - run it\n    ```sh\n    ./main --verbose\n    ```\n  - and read what it prints\nThe end."
        write(viewModels, 20f, 20f, list, drawnWidthMm = 120f)
        save(viewModels)
    }

    /**
     * Nested lists saved on the page: the bullets by depth, the numbers by position and level
     * (`1.`, `a.`, `i.`) right-aligned in their column, a line break inside an item.
     */
    @Test
    fun textSavedNestedLists() = capture("text-saved-nested-lists", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        val list = "- Limits\n  - one-sided\n    - from the left\n- Continuity\n  a line break in the item\n" +
            "1. First\n1. Second\n   1. nested\n   1. again\n      1. deeper\n      1. and more\n1. Third\n1. Fourth\n1. Fifth\n" +
            "1. Sixth\n1. Seventh\n1. Eighth\n1. Ninth\n1. Tenth, aligned with the ninth"
        write(viewModels, 20f, 20f, list, drawnWidthMm = 120f)
        save(viewModels)
    }

    /**
     * A list written with the keys: Shift+Enter breaks the line inside the item (at its text),
     * Enter on that line starts the next item.
     */
    @Test
    fun textListLineBreakWritten() = capture("text-list-line-break", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 20f, 20f, "- Limits", drawnWidthMm = 120f)
        onNode(hasSetTextAction()).performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        onNode(hasSetTextAction()).performTextInput("the same item, on a line of its own")
        onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        onNode(hasSetTextAction()).performTextInput("Continuity")
        waitForIdle()
        runOnIdle {
            assertEquals("- Limits\n  the same item, on a line of its own\n- Continuity", viewModels.controller().textEditorManager.value.text)
        }
    }

    /**
     * Boxes with a fill: one saved on the page, one being written (its fill drawn by the editor,
     * the fill button in the bar), their lines where they would be without it.
     */
    @Test
    fun textBackgrounds() = WINDOWS.forEach { window ->
        capture("text-backgrounds", window, dark = false) { viewModels ->
            openNote(viewModels)
            write(viewModels, 20f, 20f, "**Definition.** A function is continuous at \$a\$ when\n\$\$\\lim_{x \\to a} f(x) = f(a)\$\$", drawnWidthMm = 110f)
            runOnIdle { viewModels.controller().textEditorManager.changeBackground(TextBackground.TINTS[2]) }
            save(viewModels)
            write(viewModels, 20f, 70f, "- a note in yellow\n- written now")
            runOnIdle { viewModels.controller().textEditorManager.changeBackground(TextBackground.TINTS[0]) }
            waitForIdle()
        }
    }

    /**
     * A table saved on the page, and one in the text being written: the header in bold, the
     * alignments of the columns, a formula in a cell, a long cell wrapping, the columns sharing
     * the width of the box.
     */
    @Test
    fun textTables() = WINDOWS.forEach { window ->
        capture("text-tables", window, dark = false) { viewModels ->
            openNote(viewModels)
            val limits = "Notable limits:\n| Limit | Value | Note |\n|---|:-:|---|\n| \$\\lim_{x \\to 0} \\frac{\\sin x}{x}\$ | **1** | " +
                "the area of the sector between the two triangles |\n| \$\\lim_{x \\to 0} \\frac{e^x - 1}{x}\$ | 1 | |\nThen the derivatives."
            write(viewModels, 20f, 20f, limits, drawnWidthMm = 120f)
            save(viewModels)
            write(viewModels, 20f, 100f, "| Day | Topic |\n|--:|---|\n| 1 | Limits |\n| 2 | Continuity |\n\nWritten now", drawnWidthMm = 80f)
        }
    }

    /**
     * A table written with the keys: inserted, its cells filled with Tab, a row added after the
     * last cell, Enter to the cell below; the caret drawn in its cell, the cell outlined.
     */
    @Test
    fun textTableWritten() = capture("text-table-written", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 20f, 20f, "Plan", drawnWidthMm = 120f)
        runOnIdle { viewModels.controller().textEditorManager.insertTable(2, 2) }
        val field = onNode(hasSetTextAction())
        field.performTextInput("Day")
        field.performKeyInput { pressKey(Key.Tab) }
        field.performTextInput("Topic")
        field.performKeyInput { pressKey(Key.Tab) }
        field.performTextInput("1")
        field.performKeyInput { pressKey(Key.Tab) }
        field.performTextInput("Limits")
        field.performKeyInput { pressKey(Key.Tab) }
        field.performTextInput("2")
        field.performKeyInput { pressKey(Key.Tab) }
        field.performTextInput("Continuity, a | in it")
        waitForIdle()
        runOnIdle {
            assertEquals(
                "Plan\n| Day | Topic |\n| --- | --- |\n| 1 | Limits |\n| 2  | Continuity, a \\| in it  |\n",
                viewModels.controller().textEditorManager.value.text
            )
        }
    }

    /** A table opened where a cell is touched: the caret on the character touched in that cell. */
    @Test
    fun textTableTouched() = capture("text-table-touched", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 20f, 20f, "| Day | Topic |\n| --- | --- |\n| 1 | Limits |", drawnWidthMm = 120f)
        save(viewModels)
        runOnIdle {
            val controller = viewModels.controller()
            val text = controller.document.pages[0].texts.single()
            val word = controller.tileManager.tileRenderer.textAdapter!!.layout(text, TextTileAdapter.NO_PAPER)!!.words.first { it.text.trim() == "Limits" }
            // In the word, past its first letter
            controller.textEditorManager.startEditing(text, 0, caretAt = Offset(word.left + word.width * 0.4f, word.baseline - 1f))
        }
        waitForIdle()
        runOnIdle {
            val editor = viewModels.controller().textEditorManager
            val start = editor.value.text.indexOf("Limits")
            assertTrue(editor.value.selection.start in start + 1..start + 5, "in the word touched, in its cell")
        }
    }

    /** The menu of the table of the caret: its rows, its columns, the alignment of its column. */
    @Test
    fun textTableMenu() = capture("text-table-menu", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 20f, 20f, "| a | b |\n| --- | --- |\n| c | d |", drawnWidthMm = 120f)
        caretAfter(viewModels, "c")
        onNodeWithContentDescription("Table").performClick()
        waitForIdle()
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

    /**
     * The long press on a text selected over another one (the click of the secondary button on
     * it): cut, copy, delete and the commands arranging it in the stack of its page, the shortcuts
     * written; "Send Backward" and "Send to Back" on, nothing over it.
     */
    @Test
    fun selectionMenu() = capture("selection-menu", ReferenceWindow.DESKTOP, dark = false) { viewModels ->
        openNote(viewModels)
        write(viewModels, 20f, 30f, "Area of a circle: \$\\pi r^2\$")
        save(viewModels)
        write(viewModels, 20f, 90f, "Radius")
        save(viewModels)
        runOnIdle {
            val controller = viewModels.controller()
            val page = controller.document.pages[0]
            val (under, text) = page.texts
            // Moved over the first one
            controller.session.submit(PlaceElements(page.id, listOf(TextPlacement(text.id, under.x + 30f, under.y + 2f, text.width, 0f, text.fontSize, text.autoWidth))))
            controller.selectTool(Tool.LAZO)
            controller.selectionManager.selectText(0, text)
            val center = checkNotNull(controller.viewport.pageMmToScreen(0)).mapPoint(text.centerX, text.centerY)
            controller.contextMenuPosition = Offset(center.x, center.y)
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
