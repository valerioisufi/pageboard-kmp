package io.github.valerioisufi.pageboard.canvas

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.pageboard.canvas.text.RichTextEditing
import kotlin.test.Test
import kotlin.test.assertEquals

/** Writing in a quote in a quote (`docs/plans/boxes-tables-quotes.md`): Enter goes on at its level, an empty line goes one level out. */
class QuoteLevelsEditingTest {

    private fun value(text: String) = TextFieldValue(text, TextRange(text.length))

    @Test
    fun enterGoesOnAtTheLevelOfItsLine() {
        val next = RichTextEditing.handleEnter(value("> one\n> > two"))!!
        assertEquals("> one\n> > two\n> > ", next.text)
        assertEquals(TextRange(next.text.length), next.selection)
    }

    @Test
    fun anEmptyLineGoesOneLevelOut() {
        val out = RichTextEditing.handleEnter(value("> > two\n> > "))!!
        assertEquals("> > two\n> ", out.text)
        assertEquals(TextRange(out.text.length), out.selection)
        val outOfAll = RichTextEditing.handleEnter(value("> one\n> "))!!
        assertEquals("> one\n", outOfAll.text)
    }
}
