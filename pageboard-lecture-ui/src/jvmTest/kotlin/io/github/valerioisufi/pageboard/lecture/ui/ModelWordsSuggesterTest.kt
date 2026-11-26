package io.github.valerioisufi.pageboard.lecture.ui

import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.ui.extension.EditorContext
import io.github.valerioisufi.pageboard.ui.extension.TextBeingWritten
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/** The model asked for the next words after a word ended, with the paragraph before the caret. */
class ModelWordsSuggesterTest {

    private val context = EditorContext(PageboardController(document = Document(), coroutineScope = CoroutineScope(Dispatchers.Unconfined)))

    @Test
    fun theParagraphBeforeTheCaretIsAskedAfterAWord() = runTest {
        val asked = mutableListOf<String>()
        val suggester = ModelWordsSuggester { paragraph -> asked += paragraph; "are real" }
        val text = "# Title\n\nThe eigenvalues "
        assertEquals("are real", suggester.suggest(context, TextBeingWritten(text, text.length, 0, null))?.text)
        assertEquals(listOf("The eigenvalues"), asked)
    }

    @Test
    fun notInAWordNorInAFormulaNorInCode() {
        assertNull(ModelWordsSuggester.paragraphBefore("The eigen", 9), "in a word")
        assertNull(ModelWordsSuggester.paragraphBefore("The  ", 5), "two spaces")
        assertNull(ModelWordsSuggester.paragraphBefore("Then \$x + ", 10), "in a formula")
        assertNull(ModelWordsSuggester.paragraphBefore("Run `ls ", 8), "in code")
        assertNull(ModelWordsSuggester.paragraphBefore("The words here", 4), "text after the caret")
        assertEquals("Line one\nline two", ModelWordsSuggester.paragraphBefore("Line one\nline two \nnext", 18), "at the end of a line")
    }
}
