package io.github.valerioisufi.drawview.model.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** A text pasted from elsewhere, cleaned without being rewritten. */
class PastedTextTest {

    @Test
    fun lineEndingsAndNonBreakingSpacesAreAlwaysNormalized() {
        assertEquals("a b\nc\nd", PastedText.clean("a b\r\nc\rd"))
        assertEquals("# Title\n\ntext", PastedText.clean("# Title\r\n\r\ntext"))
    }

    @Test
    fun aBlockPastedInALineGetsALineOfItsOwn() {
        assertEquals("\n\n# T", PastedText.placed("# T", "Prima ", ""))
        assertEquals("- a\n\n", PastedText.placed("- a", "", "dopo"))
        assertEquals("\n\n| a |\n| --- |\n\n", PastedText.placed("| a |\n| --- |", "x", "y"))
        assertEquals("# T", PastedText.placed("# T", "Prima\n", "\ndopo"))
        assertEquals("**a**", PastedText.placed("**a**", "Prima ", " dopo"))
        assertEquals("**a**\n\nb", PastedText.placed("**a**\n\nb", "Prima ", " dopo"))
    }

    @Test
    fun bulletsBecomeAListTheLinesUnderThemJoined() {
        assertEquals(
            "- Primo\n- Secondo che era a capo su due righe\n- Terzo",
            PastedText.clean("• Primo\r\n• Secondo che era a capo\r\nsu due righe\r\n• Terzo")
        )
        assertEquals("Punti:\n- uno\n- due", PastedText.clean("Punti:\n– uno\n– due"))
        assertEquals("1. uno\n2. due", PastedText.clean("1) uno\n2) due"))
    }

    @Test
    fun theDepthsOfAListFollowItsIndents() {
        assertEquals("- uno\n  - sotto\n    - ancora\n- due", PastedText.clean("• uno\n    ◦ sotto\n        ▪ ancora\n• due"))
    }

    @Test
    fun oneBulletAloneIsNotAList() {
        assertEquals("• una cosa sola\ne un'altra riga", PastedText.clean("• una cosa sola\ne un'altra riga"))
    }

    @Test
    fun theLinesOfAParagraphBrokenByItsColumnAreJoined() {
        val copied = "Il limite di una funzione descrive il com-\nportamento della funzione vicino ad un punto\nsenza dire nulla del suo valore nel punto,\nche potrebbe non esistere."
        assertEquals("Il limite di una funzione descrive il comportamento della funzione vicino ad un punto senza dire nulla del suo valore nel punto, che potrebbe non esistere.", PastedText.clean(copied))
    }

    @Test
    fun whatWasWrittenOnItsLinesStaysThere() {
        val verses = "Nel mezzo del cammin\ndi nostra vita\nmi ritrovai per una selva oscura"
        assertEquals(verses, PastedText.clean(verses))
        val address = "Via Roma 1\n00100 Roma\nItalia"
        assertEquals(address, PastedText.clean(address))
        val sentences = "Questa frase finisce qui ed e' abbastanza lunga.\nAnche questa finisce qui ed e' abbastanza lunga.\nE pure questa finisce qui, lunga come le altre."
        assertEquals(sentences, PastedText.clean(sentences))
        val code = "val first = compute(alpha, beta, gamma, delta)\n    val second = compute(first, beta, gamma, delta)\n    return second + first + compute(first, second)"
        assertEquals(code, PastedText.clean(code))
    }

    @Test
    fun noHeadingIsGuessed() {
        assertEquals("Ciao\n\nCome stai?", PastedText.clean("Ciao\n\nCome stai?"))
        assertEquals("INTRODUZIONE", PastedText.clean("INTRODUZIONE"))
    }

    @Test
    fun markdownIsLeftAsItIs() {
        val markdown = "## Titolo\n- uno\n- due\n\n```\ncodice\n```"
        assertEquals(markdown, PastedText.clean(markdown))
    }

    @Test
    fun theLineBreakAtTheEndStays() {
        assertEquals("- uno\n- due\n", PastedText.clean("• uno\n• due\n"))
    }
}
