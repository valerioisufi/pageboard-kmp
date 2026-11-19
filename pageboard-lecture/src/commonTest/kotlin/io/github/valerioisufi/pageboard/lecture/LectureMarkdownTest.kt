package io.github.valerioisufi.pageboard.lecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The lectures of a note written as Markdown (see `docs/plans/markdown-export.md`). */
class LectureMarkdownTest {

    private fun note(id: String, kind: NoteKind, title: String, content: String, atMs: Long, status: SuggestionStatus = SuggestionStatus.NEW) =
        NoteSuggestion(id = id, kind = kind, title = title, content = content, atMs = atMs, status = status)

    private val labels = LectureMarkdown.Labels.ENGLISH

    @Test
    fun aLectureHasItsOutlineItsSummaryAndItsNotesByKind() {
        val record = LectureRecord(
            id = "l1",
            documentId = "d",
            startedAt = 2_000,
            durationMs = 3_725_000,
            outline = "# Limits\n- definition",
            finalReview = "## What was said\nThe limit of \$\\sin x / x\$.",
            suggestions = listOf(
                note("a", NoteKind.THEOREM, "De l'Hôpital", "If \$f/g\$ is \$0/0\$…\n\nthen…", atMs = 120_000),
                note("b", NoteKind.DEFINITION, "Limit", "\$\\lim_{x \\to c} f(x) = L\$", atMs = 60_000),
                note("c", NoteKind.OTHER, "Noise", "dismissed", atMs = 1_000, status = SuggestionStatus.DISMISSED)
            )
        )
        val markdown = LectureMarkdown.write(listOf(record), labels) { "14 Sep · 10:15" }
        assertEquals(
            """
            |## Lectures
            |
            |### Lecture of 14 Sep · 10:15 (1:02:05)
            |
            |#### Outline
            |
            |##### Limits
            |- definition
            |
            |#### Summary
            |
            |###### What was said
            |The limit of ${'$'}\sin x / x${'$'}.
            |
            |#### Notes
            |
            |##### Definitions
            |
            |- **Limit** (1:00)
            |  ${'$'}\lim_{x \to c} f(x) = L${'$'}
            |
            |##### Theorems
            |
            |- **De l'Hôpital** (2:00)
            |  If ${'$'}f/g${'$'} is ${'$'}0/0${'$'}…
            |
            |  then…
            |
            """.trimMargin(),
            markdown
        )
        assertFalse("Noise" in markdown, "a note dismissed is left out")
    }

    @Test
    fun theLecturesGoFromTheOldestAndThoseWithNothingAreLeftOut() {
        val old = LectureRecord(id = "1", documentId = "d", startedAt = 1, outline = "first")
        val empty = LectureRecord(id = "2", documentId = "d", startedAt = 2)
        val recent = LectureRecord(id = "3", documentId = "d", startedAt = 3, outline = "second")
        val markdown = LectureMarkdown.write(listOf(recent, empty, old), labels) { "at $it" }
        assertTrue(markdown.indexOf("first") < markdown.indexOf("second"))
        assertFalse("at 2" in markdown)
        assertEquals("", LectureMarkdown.write(listOf(empty), labels) { "" })
    }

    @Test
    fun theHeadingsOfTheAssistantGoUnderThoseOfTheLecture() {
        assertEquals("##### A\n###### B\n###### C\n```\n# not a heading\n```", LectureMarkdown.nested("# A\n## B\n#### C\n```\n# not a heading\n```"))
    }
}
