package io.github.valerioisufi.drawview.explorer

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The formatting bar on a selection of the text being written, its buttons pressed with a mouse
 * (docs/plans/text-fidelity.md): a button taking the focus made the field drop its selection, and the
 * delimiters went at its end.
 */
@OptIn(ExperimentalTestApi::class)
class TextBoxFormatBarTest {

    private fun formats(button: String, expected: String) = textBoxScene { controller ->
        startBox(controller, 30f, 60f, widthMm = 120f)
        type("una parola qui")
        val editor = controller.textEditorManager
        runOnIdle { editor.updateValue(editor.value.copy(selection = TextRange(4, 10))) }
        waitForIdle()
        val center = onNodeWithContentDescription(button).fetchSemanticsNode().boundsInRoot.center
        onRoot().performMouseInput {
            moveTo(center)
            press()
            release()
        }
        waitForIdle()
        assertEquals(expected, editor.value.text)
        val word = expected.indexOf("parola")
        assertEquals(TextRange(word, word + "parola".length), editor.value.selection, "the selection on the word")
    }

    @Test
    fun theCodeButtonSurroundsTheSelection() = formats("Code", "una `parola` qui")

    @Test
    fun theBoldButtonSurroundsTheSelection() = formats("Bold", "una **parola** qui")

    @Test
    fun theFormulaButtonSurroundsTheSelection() = formats("Formula", "una \$parola\$ qui")
}
