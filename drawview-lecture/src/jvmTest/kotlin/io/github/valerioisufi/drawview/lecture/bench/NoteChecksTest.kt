package io.github.valerioisufi.drawview.lecture.bench

import io.github.valerioisufi.drawview.lecture.LectureAnalysis
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/** The checks on the notes of a run, without a model. */
class NoteChecksTest {
    private fun note(content: String, title: String = "titolo") = BenchNote(0, null, "EXPLANATION", title, content)

    private val slide = "Lo scheduler EDF assegna la priorità in base alla scadenza assoluta del job"

    private fun judge(store: File, answer: suspend () -> String?, asked: () -> Unit = {}) = ModelCheck(
        transcript,
        300_000,
        store,
        pagesOf = { listOf("[Page 1] $slide") },
        ask = {
            asked()
            answer()
        }
    )

    private fun store() = File(Files.createTempDirectory("judge").toFile().also { it.deleteOnExit() }, "judge.json")

    @Test
    fun theModelDropsANoteWithItsReasonAndKeepsOneThatAdds() = runBlocking<Unit> {
        val heard = "la fila del supermercato somiglia alla coda"
        assertTrue("repeats" in judge(store(), { """{"lecturer":"$heard","slide":"scheduler EDF assegna priorità"}""" }).droppedBecause(note("a"), emptyList()).orEmpty())
        assertTrue("did not say" in judge(store(), { """{"lecturer":"","slide":""}""" }).droppedBecause(note("a"), emptyList()).orEmpty())
        assertNull(judge(store(), { """{"lecturer":"$heard","slide":""}""" }).droppedBecause(note("a"), emptyList()))
    }

    @Test
    fun aCopyNotReallyThereIsNotBelieved() = runBlocking<Unit> {
        assertTrue("did not say" in judge(store(), { """{"lecturer":"parole inventate altrove","slide":""}""" }).droppedBecause(note("a"), emptyList()).orEmpty())
        assertNull(judge(store(), { """{"lecturer":"fila supermercato","slide":"frase inventata altrove"}""" }).droppedBecause(note("a"), emptyList()))
    }

    @Test
    fun aJudgementThatCannotBeReadKeepsTheNote() = runBlocking<Unit> {
        assertNull(judge(store(), { "not json" }).droppedBecause(note("a"), emptyList()))
        assertNull(judge(store(), { null }).droppedBecause(note("a"), emptyList()))
    }

    @Test
    fun aJudgementIsAskedOnceEvenAfterANewRun() = runBlocking<Unit> {
        val file = store()
        var asked = 0
        judge(file, { """{"lecturer":"","slide":""}""" }, { asked++ }).droppedBecause(note("a"), emptyList())
        val again = judge(file, { """{"lecturer":"fila supermercato","slide":""}""" }, { asked++ })
        assertNotNull(again.droppedBecause(note("a"), emptyList()))
        assertEquals(1, asked)
        assertEquals(1, again.millisByInterval.size)
    }

    private fun said(words: String, fromMs: Long = 0) = BenchNote(0, null, "EXPLANATION", "t", "c", words, fromMs)

    private val transcript = listOf(
        TranscriptSegment(1_000, 2_000, "la fila del supermercato somiglia alla coda dei processi"),
        TranscriptSegment(301_000, 302_000, "il prossimo argomento riguarda le anomalie di Graham")
    )

    @Test
    fun aNoteFromWordsOfItsIntervalPasses() = runBlocking<Unit> {
        assertNull(SaidCheck(transcript, 300_000).droppedBecause(said("la fila del supermercato somiglia alla coda"), emptyList()))
    }

    @Test
    fun aNoteFromWordsNotInItsIntervalIsDropped() = runBlocking<Unit> {
        val check = SaidCheck(transcript, 300_000)
        assertTrue("did not say" in check.droppedBecause(said("anomalie di Graham"), emptyList()).orEmpty())
        assertNull(check.droppedBecause(said("anomalie di Graham", fromMs = 300_000), emptyList()))
    }

    @Test
    fun aNoteWithoutWordsOfTheLecturerIsDropped() = runBlocking<Unit> {
        assertTrue("no words" in SaidCheck(transcript, 300_000).droppedBecause(said(""), emptyList()).orEmpty())
    }

    @Test
    fun theSchemaAsksForTheWordsAndTheApplicationIgnoresThem() = runBlocking<Unit> {
        val prompt = ModelPrompt("system", emptyList(), LectureAnalysis.chunkSchema(true), 8192, "low")
        val asked = withSaid(prompt)
        assertTrue("\"said\"" in asked.schema.toString())
        assertTrue("\"said\"" !in LectureAnalysis.chunkSchema(true).toString())
        assertTrue("said" in asked.system)
        val answer = """{"currentTopic":"t","outline":"o","notes":[{"kind":"OTHER","title":"a","content":"b","said":"parole dette"},{"kind":"OTHER","title":"c","content":"d"}]}"""
        assertEquals(2, LectureAnalysis.parseChunk(answer).notes.size)
        assertEquals(listOf("parole dette", ""), saidOf(answer))
        assertEquals(emptyList(), saidOf("not json"))
    }

    @Test
    fun aNoteThatRepeatsASlideIsDropped() = runBlocking<Unit> {
        val reason = RepeatCheck(listOf("altro", slide)).droppedBecause(note("EDF assegna priorità in base alla scadenza del job"), emptyList())
        assertNotNull(reason)
        assertTrue("slide" in reason, reason)
    }

    @Test
    fun aNoteWithWordsOfItsOwnPasses() = runBlocking<Unit> {
        assertNull(RepeatCheck(listOf(slide)).droppedBecause(note("Il professore paragona la coda alla fila del supermercato, dove nessuno cede il posto"), emptyList()))
    }

    @Test
    fun theShareIsOfTheBestSlideNotOfAllTogether() = runBlocking<Unit> {
        val slides = listOf("alfa beta gamma", "delta epsilon zeta")
        assertNull(RepeatCheck(slides, slideShare = 0.6).droppedBecause(note("alfa beta delta epsilon"), emptyList()))
    }

    @Test
    fun aNoteRepeatingOneKeptIsDropped() = runBlocking<Unit> {
        val first = note("La coda dei processi pronti cresce quando il carico supera la capacità")
        val again = note("La coda dei processi pronti cresce quando il carico supera la capacità del sistema")
        val decisions = NoteChecks(listOf(RepeatCheck(emptyList()))).decide(listOf(first, again))
        assertNull(decisions[0].droppedBecause)
        assertTrue("note" in decisions[1].droppedBecause.orEmpty())
    }

    @Test
    fun aNoteDroppedDoesNotDropTheNextOne() = runBlocking<Unit> {
        val onSlide = note(slide)
        val close = note(slide.replace("scheduler", "algoritmo"))
        val decisions = NoteChecks(listOf(RepeatCheck(listOf(slide), slideShare = 1.0))).decide(listOf(onSlide, close))
        assertTrue("slide" in decisions[0].droppedBecause.orEmpty())
        assertNull(decisions[1].droppedBecause)
    }

    @Test
    fun theFirstCheckThatDropsANoteSaysWhyAndTheOthersAreNotAsked() = runBlocking<Unit> {
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
    fun wordsIgnoreCaseCommonWordsAndEndings() = runBlocking<Unit> {
        assertEquals(setOf("schedu", "edf", "42"), RepeatCheck.wordsOf("Lo SCHEDULER di EDF, lo scheduling: 42"))
    }

    @Test
    fun aNoteWithoutContentIsJudgedByItsTitleAndOneWithoutWordsPasses() = runBlocking<Unit> {
        val check = RepeatCheck(listOf(slide))
        assertNotNull(check.droppedBecause(note("", title = "EDF scadenza assoluta"), emptyList()))
        assertNull(check.droppedBecause(note("", title = ""), emptyList()))
    }
}
