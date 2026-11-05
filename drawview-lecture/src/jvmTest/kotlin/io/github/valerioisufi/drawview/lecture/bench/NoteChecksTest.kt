package io.github.valerioisufi.drawview.lecture.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The checks on the notes of a run, without a model. */
class NoteChecksTest {
    private fun note(content: String, title: String = "titolo") = BenchNote(0, null, "EXPLANATION", title, content)

    private val slide = "Lo scheduler EDF assegna la priorità in base alla scadenza assoluta del job"

    @Test
    fun aNoteThatRepeatsASlideIsDropped() {
        val reason = RepeatCheck(listOf("altro", slide)).droppedBecause(note("EDF assegna priorità in base alla scadenza del job"), emptyList())
        assertNotNull(reason)
        assertTrue("slide" in reason, reason)
    }

    @Test
    fun aNoteWithWordsOfItsOwnPasses() {
        assertNull(RepeatCheck(listOf(slide)).droppedBecause(note("Il professore paragona la coda alla fila del supermercato, dove nessuno cede il posto"), emptyList()))
    }

    @Test
    fun theShareIsOfTheBestSlideNotOfAllTogether() {
        val slides = listOf("alfa beta gamma", "delta epsilon zeta")
        assertNull(RepeatCheck(slides, slideShare = 0.6).droppedBecause(note("alfa beta delta epsilon"), emptyList()))
    }

    @Test
    fun aNoteRepeatingOneKeptIsDropped() {
        val first = note("La coda dei processi pronti cresce quando il carico supera la capacità")
        val again = note("La coda dei processi pronti cresce quando il carico supera la capacità del sistema")
        val decisions = NoteChecks(listOf(RepeatCheck(emptyList()))).decide(listOf(first, again))
        assertNull(decisions[0].droppedBecause)
        assertTrue("note" in decisions[1].droppedBecause.orEmpty())
    }

    @Test
    fun aNoteDroppedDoesNotDropTheNextOne() {
        val onSlide = note(slide)
        val close = note(slide.replace("scheduler", "algoritmo"))
        val decisions = NoteChecks(listOf(RepeatCheck(listOf(slide), slideShare = 1.0))).decide(listOf(onSlide, close))
        assertTrue("slide" in decisions[0].droppedBecause.orEmpty())
        assertNull(decisions[1].droppedBecause)
    }

    @Test
    fun theFirstCheckThatDropsANoteSaysWhyAndTheOthersAreNotAsked() {
        var asked = 0
        val decisions = NoteChecks(
            listOf(
                NoteCheck { _, _ -> "first" },
                NoteCheck { _, _ ->
                    asked++
                    "second"
                }
            )
        ).decide(listOf(note("testo")))
        assertEquals("first", decisions.single().droppedBecause)
        assertEquals(0, asked)
    }

    @Test
    fun wordsIgnoreCaseCommonWordsAndEndings() {
        assertEquals(setOf("schedu", "edf", "42"), RepeatCheck.wordsOf("Lo SCHEDULER di EDF, lo scheduling: 42"))
    }

    @Test
    fun aNoteWithoutContentIsJudgedByItsTitleAndOneWithoutWordsPasses() {
        val check = RepeatCheck(listOf(slide))
        assertNotNull(check.droppedBecause(note("", title = "EDF scadenza assoluta"), emptyList()))
        assertNull(check.droppedBecause(note("", title = ""), emptyList()))
    }
}
