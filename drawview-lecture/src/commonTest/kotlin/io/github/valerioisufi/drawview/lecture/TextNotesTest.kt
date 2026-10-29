package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.model.PromptPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A text written elsewhere turned into notes by a model (see `docs/text-import.md`), with a fake answer. */
class TextNotesTest {

    private val text = "La derivata misura la pendenza della retta tangente. Il teorema di Lagrange richiede continuità e derivabilità."

    @Test
    fun theRequestHoldsTheNotebookTheTextAndTheRules() {
        val pages = listOf(PageContent(index = 0, pageId = "p1", typedTexts = listOf("Derivate")), PageContent(index = 1, pageId = "p2"))
        val prompt = LecturePrompts.textNotesPrompt(AssistantSettings(), "Analisi 1", pages, text)
        val parts = prompt.parts.map { (it as PromptPart.Text).text }
        assertTrue(parts.first().contains("### Page 1") && parts.first().contains("Derivate"))
        assertTrue(parts.last().endsWith(text))
        assertTrue("anchor" in prompt.system && "Never add" in prompt.system)
        assertEquals(LectureAnalysis.textNotesSchema, prompt.schema)
    }

    @Test
    fun theAnswerBecomesNotesOnTheirPagesWithTheShareOfTheTextTheyHold() {
        val answer = """{"notes":[
            {"kind":"DEFINITION","title":"Derivata","content":"Pendenza della retta tangente.","page":1,"importance":3,"anchor":"Derivate"},
            {"kind":"THEOREM","title":"Lagrange","content":"Richiede continuità e derivabilità.","page":9},
            {"kind":"OTHER","title":"Empty","content":"  "}
        ]}"""
        val notes = TextNotes.of(LectureAnalysis.parseTextNotes(answer), pageIds = listOf("p1", "p2"), text = text)
        assertEquals(listOf("Derivata", "Lagrange"), notes.notes.map { it.title })
        val first = notes.notes.first()
        assertEquals(NoteKind.DEFINITION, first.kind)
        assertEquals(0, first.pageIndex)
        assertEquals("p1", first.pageId)
        assertEquals("Derivate", first.anchor)
        // A page the notebook does not have: none
        assertEquals(null, notes.notes[1].pageIndex)
        assertTrue(notes.coveredShare > 0.5 && notes.coveredShare < 1.0, "covered: ${notes.coveredShare}")
    }

    @Test
    fun aTextLeftOutIsSeenInTheShare() {
        val notes = listOf(NoteSuggestion(id = "a", kind = NoteKind.OTHER, title = "Derivata", content = "pendenza"))
        assertTrue(TextNotes.coverage(text, notes) < 0.3)
        assertEquals(1.0, TextNotes.coverage("a b c", notes))
    }
}
