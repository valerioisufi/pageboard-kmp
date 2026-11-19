package io.github.valerioisufi.drawview.search

import kotlin.test.Test
import kotlin.test.assertEquals

/** The words of the notes counted for the suggestions while writing (`docs/plans/writing-suggestions.md`). */
class WordCountsTest {

    @Test
    fun wordsAreRunsOfLettersAndDigitsOfThreeCharactersAtLeast() {
        assertEquals(
            listOf("dell", "algoritmo", "mail", "perché", "Università", "x86", "2nd"),
            WordCounts.wordsOf("dell'algoritmo, e-mail: perché l'Università? x86 2nd 42 un è")
        )
    }

    @Test
    fun theNotationIsNotCounted() {
        val text = "**Teorema** di $\\alpha + \\beta$ con `codice` e [Pitagora](https://example.com/pagina)\n" +
            "```kotlin\nval variabile = 1\n```\n# Titolo"
        assertEquals(listOf("Teorema", "con", "Pitagora", "Titolo"), WordCounts.wordsOf(text))
    }

    @Test
    fun aWordIsCountedWhateverItsCaseWithTheFormWrittenMostOften() {
        val counts = WordCounts()
        counts.add("Perché no? Ecco perché. E perché sì.")
        assertEquals(3, counts.count("PERCHÉ"))
        assertEquals(listOf("perché×3"), counts.withPrefix("Perc").map { it.toString() })
    }

    @Test
    fun aNameKeepsItsCapital() {
        val counts = WordCounts()
        counts.add("Dijkstra trova il cammino. Dijkstra usa una coda.")
        assertEquals("Dijkstra", counts.withPrefix("dij").single().word)
    }

    @Test
    fun theWordsOfAPrefixAreFoundInOrderAndOnlyThose() {
        val counts = WordCounts()
        counts.add("algoritmo algebra algoritmi alto al")
        assertEquals(listOf("algebra", "algoritmi", "algoritmo"), counts.withPrefix("alg").map { it.word })
        assertEquals(emptyList(), counts.withPrefix("zeta"))
        assertEquals(emptyList(), counts.withPrefix(""))
    }

    @Test
    fun anAccentIsNotIgnored() {
        val counts = WordCounts()
        counts.add("perché")
        assertEquals(emptyList(), counts.withPrefix("perche"))
    }

    @Test
    fun countsAreAddedTogether() {
        val first = WordCounts().apply { add("grafo grafo nodo") }
        val second = WordCounts().apply { add("Grafo arco") }
        val all = WordCounts().apply { addAll(first); addAll(second); add("grafi") }
        assertEquals(3, all.count("grafo"))
        assertEquals(listOf("grafi", "grafo"), all.withPrefix("gra").map { it.word })
        assertEquals(4, all.size)
    }
}
