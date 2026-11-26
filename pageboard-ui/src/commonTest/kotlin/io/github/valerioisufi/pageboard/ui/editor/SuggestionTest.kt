package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import io.github.valerioisufi.pageboard.ui.extension.TextBeingWritten
import io.github.valerioisufi.pageboard.ui.extension.TextSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The suggestion of a text box: asked after a pause, kept for its text only, shown after the end. */
class SuggestionTest {

    private fun writing(source: String, caret: Int = source.length) = TextBeingWritten(source, caret, pageIndex = 0, textId = "t")

    @Test
    fun aSuggestionIsAskedAfterThePauseOnly() = runTest {
        val asked = mutableListOf<String>()
        val session = SuggestionSession(backgroundScope) { asked += it.source; TextSuggestion("ough") }
        session.changed(writing("The flux thr"))
        advanceTimeBy(SuggestionSession.SUGGESTION_PAUSE_MS - 1)
        runCurrent()
        assertEquals(emptyList(), asked)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("The flux thr"), asked)
        assertEquals("ough", session.suggestionFor("The flux thr", 12))
        assertNull(session.suggestionFor("The flux thro", 13), "another text has no suggestion")
    }

    @Test
    fun typingDuringThePauseOrTheAnswerForgetsTheRequest() = runTest {
        val asked = mutableListOf<String>()
        val session = SuggestionSession(backgroundScope) { w ->
            asked += w.source
            kotlinx.coroutines.delay(1_000)
            TextSuggestion("!")
        }
        session.changed(writing("a"))
        advanceTimeBy(200)
        session.changed(writing("ab"))
        advanceTimeBy(SuggestionSession.SUGGESTION_PAUSE_MS + 10)
        runCurrent()
        assertEquals(listOf("ab"), asked, "the first was not asked")
        session.changed(writing("abc"))
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(session.suggestionFor("ab", 2), "the answer for a text changed meanwhile is dropped")
        assertEquals("!", session.suggestionFor("abc", 3))
    }

    @Test
    fun theSameTextAskedAgainKeepsItsRequestAndAFailureSuggestsNothing() = runTest {
        var asks = 0
        val session = SuggestionSession(backgroundScope) { asks++; error("the model is gone") }
        session.changed(writing("x"))
        advanceTimeBy(300)
        session.changed(writing("x"))
        advanceTimeBy(SuggestionSession.SUGGESTION_PAUSE_MS)
        runCurrent()
        assertEquals(1, asks)
        assertEquals(false, session.isShown)
    }

    @Test
    fun theGhostIsAfterTheEndAndTheCaretNeverInIt() {
        val source = "Hello"
        val identity = IntArray(source.length + 1) { it }
        val presented = PresentedText<Unit>(AnnotatedString(source), SourceMapping(identity, identity.copyOf()), emptyList(), emptyList(), emptyList(), null)
        val ghost = GhostText.append(presented, " world\nsecond line", Color.Black)

        assertEquals("Hello world", ghost.text.text, "one line of the suggestion")
        assertEquals(5 until 11, GhostText.rangeIn(ghost, " world\nsecond line"))
        assertEquals(5, ghost.mapping.originalToTransformed(5), "the caret at the end stays before the suggestion")
        for (offset in 5..11) assertEquals(5, ghost.mapping.transformedToOriginal(offset))
        assertEquals(2, ghost.mapping.transformedToOriginal(2))
    }

    @Test
    fun aGhostAtTheEndOfALineMovesWhatComesAfterIt() {
        val source = "First\nSecond"
        val identity = IntArray(source.length + 1) { it }
        val presented = PresentedText<Unit>(
            AnnotatedString(source), SourceMapping(identity, identity.copyOf()), emptyList(), emptyList(), emptyList(), null,
            quotes = listOf(PresentedQuote(6..11, 1)),
            links = listOf(PresentedLink("https://example.org", 6, 12, 6, 12))
        )
        val ghost = GhostText.insert(presented, 5, " line", Color.Black)

        assertEquals("First line\nSecond", ghost.text.text)
        assertEquals(5 until 10, GhostText.rangeAt(ghost, 5, " line"))
        assertEquals(5, ghost.mapping.originalToTransformed(5), "the caret stays before the suggestion")
        assertEquals(11, ghost.mapping.originalToTransformed(6), "the next line after it")
        for (offset in 5..10) assertEquals(if (offset < 10) 5 else 5, ghost.mapping.transformedToOriginal(offset).coerceAtMost(5))
        assertEquals(6, ghost.mapping.transformedToOriginal(11))
        assertEquals(source.length + 5, ghost.mapping.originalToTransformed(source.length))
        assertEquals(11..16, ghost.quotes.single().range, "a quote after it moved")
        assertEquals(11, ghost.links.single().shownStart)
        assertEquals(6, ghost.links.single().sourceStart, "its source the same")
    }

    @Test
    fun aGhostAtTheEndOfTheTextIsAppended() {
        val source = "Hello"
        val identity = IntArray(source.length + 1) { it }
        val presented = PresentedText<Unit>(AnnotatedString(source), SourceMapping(identity, identity.copyOf()), emptyList(), emptyList(), emptyList(), null)
        assertEquals("Hello world", GhostText.insert(presented, 5, " world", Color.Black).text.text)
    }
}
