package io.github.valerioisufi.drawview.lecture.ui

import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureMarkdown
import io.github.valerioisufi.drawview.lecture.NoteKind
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.Res
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_kind_announcement
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_kind_definition
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_kind_exam_hint
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_kind_example
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_kind_explanation
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_kind_formula
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_kind_other
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_kind_reference
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_kind_theorem
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_markdown_lecture
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_markdown_lectures
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_tab_notes
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_tab_outline
import io.github.valerioisufi.drawview.lecture.ui.generated.resources.lecture_tab_review
import io.github.valerioisufi.drawview.ui.generated.resources.Res as UiRes
import io.github.valerioisufi.drawview.ui.generated.resources.date_months_short
import io.github.valerioisufi.drawview.ui.util.formatShortDateTime
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray

/**
 * The words of the Markdown of the lectures ([LectureMarkdown]) in the language of the
 * application, the names of the sections as the panel of the assistant shows them.
 */
public suspend fun lectureMarkdownLabels(): LectureMarkdown.Labels = LectureMarkdown.Labels(
    lectures = getString(Res.string.lecture_markdown_lectures),
    lecture = getString(Res.string.lecture_markdown_lecture),
    outline = getString(Res.string.lecture_tab_outline),
    summary = getString(Res.string.lecture_tab_review),
    notes = getString(Res.string.lecture_tab_notes),
    kinds = mapOf(
        NoteKind.DEFINITION to getString(Res.string.lecture_kind_definition),
        NoteKind.FORMULA to getString(Res.string.lecture_kind_formula),
        NoteKind.THEOREM to getString(Res.string.lecture_kind_theorem),
        NoteKind.EXAMPLE to getString(Res.string.lecture_kind_example),
        NoteKind.EXPLANATION to getString(Res.string.lecture_kind_explanation),
        NoteKind.EXAM_HINT to getString(Res.string.lecture_kind_exam_hint),
        NoteKind.ANNOUNCEMENT to getString(Res.string.lecture_kind_announcement),
        NoteKind.REFERENCE to getString(Res.string.lecture_kind_reference),
        NoteKind.OTHER to getString(Res.string.lecture_kind_other)
    )
)

/**
 * The lectures of the note [documentId] as Markdown in the language of the application, their
 * dates as the panel writes them ("14 Sep · 10:15"); empty when they have nothing to say (see
 * [LectureAssistant.markdownOf]). Given to the export as what it adds to the Markdown of a note.
 */
public suspend fun LectureAssistant.markdownInLanguage(documentId: String): String {
    val months = getStringArray(UiRes.array.date_months_short)
    return markdownOf(documentId, lectureMarkdownLabels()) { formatShortDateTime(it, months) }
}
