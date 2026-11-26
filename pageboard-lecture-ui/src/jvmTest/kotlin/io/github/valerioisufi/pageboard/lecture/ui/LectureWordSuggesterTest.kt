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

/** The end of the word being written, from the lecture of the document being recorded. */
class LectureWordSuggesterTest {

    private val context = EditorContext(PageboardController(document = Document(), coroutineScope = CoroutineScope(Dispatchers.Unconfined)))

    private fun writing(text: String) = TextBeingWritten(text, text.length, 0, null)

    private fun suggester(transcript: List<String>?, documentId: String = context.documentId) =
        LectureWordSuggester({ id -> transcript.takeIf { id == documentId } }, Dispatchers.Unconfined)

    @Test
    fun aWordJustSaidCompletesTheOneWritten() = runTest {
        val suggester = suggester(listOf("Today the eigenvalues of a matrix.", "An eigenvalue is a scalar.", "Two eigenvalues."))
        assertEquals("values", suggester.suggest(context, writing("The eigen"))?.text, "heard more often than eigenvalue")
        assertEquals("LAR", suggester.suggest(context, writing("A SCA"))?.text, "in the capitals typed")
    }

    @Test
    fun nothingWithoutALectureOfTheDocument() = runTest {
        assertNull(suggester(null).suggest(context, writing("The eigen")))
        assertNull(suggester(listOf("eigenvalues"), documentId = "another").suggest(context, writing("The eigen")))
        assertNull(suggester(emptyList()).suggest(context, writing("The eigen")))
    }

    @Test
    fun nothingWhenTwoWordsAreHeardAsOftenOrTooLittleIsAdded() {
        val counts = io.github.valerioisufi.pageboard.search.WordCounts().apply { add("matrix matrices") }
        assertNull(LectureWordSuggester.complete("mat", counts), "two words as often")
        val one = io.github.valerioisufi.pageboard.search.WordCounts().apply { add("graphs") }
        assertNull(LectureWordSuggester.complete("graph", one), "one letter added")
    }
}
