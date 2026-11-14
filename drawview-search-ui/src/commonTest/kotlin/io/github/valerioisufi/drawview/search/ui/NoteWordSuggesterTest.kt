package io.github.valerioisufi.drawview.search.ui

import io.github.valerioisufi.drawview.search.WordCounts
import io.github.valerioisufi.drawview.search.ui.NoteWordSuggester.Companion.complete
import io.github.valerioisufi.drawview.search.ui.NoteWordSuggester.Companion.prefixAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The end of the word being written from the words of the notes (`docs/writing-suggestions.md`). */
class NoteWordSuggesterTest {

    private fun words(vararg texts: String) = WordCounts().apply { texts.forEach(::add) }

    @Test
    fun theWordBeforeTheCaretIsThePrefix() {
        assertEquals("algo", prefixAt("L'algo", 6))
        assertEquals("Dij", prefixAt("Il cammino di Dij", 17))
        assertEquals("perch", prefixAt("Ecco perch", 10))
    }

    @Test
    fun noPrefixWhenTooShortInsideAWordOrInTheNotation() {
        assertNull(prefixAt("al", 2))
        assertNull(prefixAt("algoritmo", 4))
        assertNull(prefixAt("12ab", 4))
        assertNull(prefixAt("vale \$\\alp", 10))
        assertNull(prefixAt("la formula \$x + alp", 19))
        assertNull(prefixAt("il codice `val", 14))
        assertNull(prefixAt("[Chord](https://exa", 19))
        assertNull(prefixAt("", 0))
    }

    @Test
    fun aClosedFormulaOrLinkBeforeTheWordLetsItBeSuggested() {
        assertEquals("alg", prefixAt("con \$x^2\$ alg", 13))
        assertEquals("alg", prefixAt("[Chord](https://example.com) alg", 32))
    }

    @Test
    fun theBestWordIsCompletedWhenClearlyTheOne() {
        val library = words("algoritmo algoritmo algoritmo algebra")
        assertEquals("ritmo", complete("algo", listOf(1 to library)))
        assertEquals("oritmo", complete("alg", listOf(1 to library)))
    }

    @Test
    fun aWordSeenOnceIsNotSuggested() {
        assertNull(complete("algo", listOf(1 to words("algoritmo"))))
    }

    @Test
    fun noWinnerNoSuggestion() {
        val library = words("algoritmo algoritmo algebra algebra")
        assertNull(complete("alg", listOf(1 to library)))
    }

    @Test
    fun aWordAddingOneLetterIsNotSuggested() {
        assertEquals("ci", complete("grafi", listOf(1 to words("grafici grafici"))))
        assertNull(complete("grafic", listOf(1 to words("grafico grafico"))))
    }

    @Test
    fun theDocumentWeighsMoreThanTheLibrary() {
        val document = words("matrice matrice")
        val library = words("matematica matematica matematica")
        // 3 × 2 = 6 against 1 × 3 = 3
        assertEquals("rice", complete("mat", listOf(3 to document, 2 to null, 1 to library)))
    }

    @Test
    fun theLayersAreAddedTogether() {
        // Once in the slides, once in the library: seen twice
        assertEquals("kstra", complete("Dij", listOf(3 to words(), 2 to words("Dijkstra"), 1 to words("Dijkstra"))))
    }

    @Test
    fun theFormOfTheNotesIsSuggestedAfterTheLettersTyped() {
        val library = words("perché perché")
        assertEquals("ché", complete("Per", listOf(1 to library)))
        assertEquals("CHÉ", complete("PER", listOf(1 to library)))
        assertEquals("kstra", complete("dij", listOf(1 to words("Dijkstra Dijkstra"))))
    }

    @Test
    fun nothingBuiltNothingSuggested() {
        assertNull(complete("alg", listOf(3 to null, 1 to null)))
    }
}
