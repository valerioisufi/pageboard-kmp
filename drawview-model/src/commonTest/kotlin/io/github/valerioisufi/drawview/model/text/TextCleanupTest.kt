package io.github.valerioisufi.drawview.model.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** A plain text written elsewhere turned into the Markdown of the notebook (see `docs/text-import.md`). */
class TextCleanupTest {

    @Test
    fun theLinesOfAParagraphCopiedFromAPdfAreJoined() {
        val copied = "Il limite di una funzione descrive il com-\nportamento di f vicino ad a,\nnon in a.\n\nSecondo paragrafo\nsu due righe."
        assertEquals(
            "Il limite di una funzione descrive il comportamento di f vicino ad a, non in a.\n\nSecondo paragrafo su due righe.",
            TextCleanup.toMarkdown(copied)
        )
    }

    @Test
    fun aHyphenBetweenWordsIsKept() {
        assertEquals("Il teorema di Bolzano-\nWeierstrass".let { TextCleanup.toMarkdown(it) }, "Il teorema di Bolzano- Weierstrass")
        assertEquals("un caso ben-noto", TextCleanup.toMarkdown("un caso ben-noto"))
    }

    @Test
    fun bulletsAndNumberedItemsBecomeAListTheirWrappedLinesJoined() {
        val copied = "Proprietà:\n• linearità del\nlimite\n◦ prodotto\n– quoziente\n1) primo\n2) secondo"
        assertEquals("Proprietà:\n- linearità del limite\n- prodotto\n- quoziente\n1. primo\n2. secondo", TextCleanup.toMarkdown(copied))
    }

    @Test
    fun shortLinesBeforeAParagraphNumberedTitlesAndCapitalsAreHeadings() {
        val copied = "INTRODUZIONE\n\nLimiti di funzioni\n\nUna funzione ha limite L in a se i suoi valori si avvicinano a L.\n\n2.3 Limiti notevoli\n\nIl più noto è sin(x)/x."
        assertEquals(
            "## Introduzione\n\n## Limiti di funzioni\n\nUna funzione ha limite L in a se i suoi valori si avvicinano a L.\n\n### 2.3 Limiti notevoli\n\nIl più noto è sin(x)/x.",
            TextCleanup.toMarkdown(copied)
        )
    }

    @Test
    fun markdownCodeAndFormulasAreLeftAsTheyAre() {
        val markdown = "# Titolo\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\n$$\nx = 1\n$$\n\n> una citazione\n\n| a | b |\n|---|---|"
        assertEquals(markdown, TextCleanup.toMarkdown(markdown))
    }

    @Test
    fun windowsLineEndingsAndNonBreakingSpaces() {
        assertEquals("Una riga sola.", TextCleanup.toMarkdown("Una\r\nriga sola."))
    }

    @Test
    fun aTextLooksLikeMarkdownByItsMarks() {
        assertEquals(true, TextCleanup.looksLikeMarkdown("# Title\nText"))
        assertEquals(true, TextCleanup.looksLikeMarkdown("Some **bold** words"))
        assertEquals(true, TextCleanup.looksLikeMarkdown("The formula ${'$'}x^2${'$'} is"))
        assertEquals(false, TextCleanup.looksLikeMarkdown("It costs 5 $ and 3 $ more.\nA plain text."))
        assertEquals(false, TextCleanup.looksLikeMarkdown("• a bullet\nplain"))
    }
}
