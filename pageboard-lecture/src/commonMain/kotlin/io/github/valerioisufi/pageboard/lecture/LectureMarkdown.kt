package io.github.valerioisufi.pageboard.lecture

/**
 * The lectures of a note written as Markdown (see `docs/plans/markdown-export.md`): for each lecture, the
 * oldest first, its date and length, its outline, its summary (the final review) and the notes the
 * assistant suggested that the student did not dismiss, grouped by kind, each with the minute of
 * the lecture it was said at. The transcript is left out: it is exported on its own.
 *
 * The texts written by the assistant are Markdown already (formulas between `$`): they are written
 * as they are, their headings moved under the headings of the lecture.
 */
public object LectureMarkdown {

    /**
     * The words of the Markdown, in the language of the application: [lectures] above all of them,
     * [lecture] a lecture by its date (`%1$s`) and length (`%2$s`), [outline], [summary], [notes],
     * and the name of each [kinds] of note.
     */
    public class Labels(
        public val lectures: String,
        public val lecture: String,
        public val outline: String,
        public val summary: String,
        public val notes: String,
        public val kinds: Map<NoteKind, String>
    ) {
        public companion object {
            /** The words in English. */
            public val ENGLISH: Labels = Labels(
                lectures = "Lectures",
                lecture = "Lecture of %1\$s (%2\$s)",
                outline = "Outline",
                summary = "Summary",
                notes = "Notes",
                kinds = mapOf(
                    NoteKind.DEFINITION to "Definitions",
                    NoteKind.FORMULA to "Formulas",
                    NoteKind.THEOREM to "Theorems",
                    NoteKind.EXAMPLE to "Examples",
                    NoteKind.EXPLANATION to "Explanations",
                    NoteKind.EXAM_HINT to "For the exam",
                    NoteKind.ANNOUNCEMENT to "Announcements",
                    NoteKind.REFERENCE to "References",
                    NoteKind.OTHER to "Other"
                )
            )
        }
    }

    /**
     * [records] written as Markdown with [labels], their dates written by [date] (milliseconds
     * since the epoch); empty when no lecture has anything to say.
     */
    public fun write(records: List<LectureRecord>, labels: Labels, date: (Long) -> String): String {
        val sections = records.sortedBy { it.startedAt }.mapNotNull { section(it, labels, date) }
        if (sections.isEmpty()) return ""
        return buildString {
            append("## ").append(labels.lectures).append("\n\n")
            append(sections.joinToString("\n"))
        }.trimEnd() + "\n"
    }

    private fun section(record: LectureRecord, labels: Labels, date: (Long) -> String): String? {
        val outline = record.outline.trim()
        val summary = record.finalReview?.trim().orEmpty()
        val notes = record.suggestions.filter { it.status != SuggestionStatus.DISMISSED && (it.title.isNotBlank() || it.content.isNotBlank()) }
        if (outline.isEmpty() && summary.isEmpty() && notes.isEmpty()) return null
        return buildString {
            val title = labels.lecture.replace("%1\$s", date(record.startedAt)).replace("%2\$s", formatLectureTime(record.durationMs))
            append("### ").append(title).append("\n\n")
            if (outline.isNotEmpty()) {
                append("#### ").append(labels.outline).append("\n\n").append(nested(outline)).append("\n\n")
            }
            if (summary.isNotEmpty()) {
                append("#### ").append(labels.summary).append("\n\n").append(nested(summary)).append("\n\n")
            }
            if (notes.isNotEmpty()) {
                append("#### ").append(labels.notes).append("\n\n")
                for ((kind, ofKind) in notes.sortedBy { it.atMs }.groupBy { it.kind }.toSortedMap(compareBy { it.ordinal })) {
                    append("##### ").append(labels.kinds[kind] ?: kind.name).append("\n\n")
                    for (note in ofKind) {
                        append("- ")
                        if (note.title.isNotBlank()) append("**").append(note.title.trim()).append("**")
                        append(" (").append(formatLectureTime(note.atMs)).append(")")
                        val content = note.content.trim()
                        if (content.isNotEmpty()) {
                            // The lines of the note under its item
                            append("\n").append(content.lines().joinToString("\n") { if (it.isBlank()) "" else "  $it" })
                        }
                        append("\n")
                    }
                    append("\n")
                }
            }
        }
    }

    /**
     * [markdown] with its headings moved under a heading of level 4: a `#` becomes `#####`, the
     * deepest staying at 6; the rest as it is.
     */
    internal fun nested(markdown: String): String {
        var inFence = false
        return markdown.lines().joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence
            val level = trimmed.takeWhile { it == '#' }.length
            if (!inFence && level in 1..6 && trimmed.getOrNull(level) == ' ') {
                "#".repeat((level + 4).coerceAtMost(6)) + trimmed.substring(level)
            } else {
                line
            }
        }
    }
}
