package io.github.valerioisufi.drawview.lecture.bench

/** One test a note of a run must pass before it reaches the student. */
internal fun interface NoteCheck {
    /** Why [note] is dropped, given the notes [kept] before it; `null` when it passes. */
    fun droppedBecause(note: BenchNote, kept: List<BenchNote>): String?
}

/** What [NoteChecks] decided about a note: [droppedBecause] is `null` when the note was kept. */
internal class NoteDecision(val note: BenchNote, val droppedBecause: String?)

/** The checks a note must pass, in order: the first that drops it says why, and the others are not asked. */
internal class NoteChecks(private val checks: List<NoteCheck>) {
    /** A decision for each of [notes], in their order, each note checked against those kept before it. */
    fun decide(notes: List<BenchNote>): List<NoteDecision> {
        val kept = mutableListOf<BenchNote>()
        return notes.map { note ->
            val reason = checks.firstNotNullOfOrNull { it.droppedBecause(note, kept) }
            if (reason == null) kept.add(note)
            NoteDecision(note, reason)
        }
    }
}

/**
 * Drops a note that repeats a slide or a note kept before it, by the words they share: the share of
 * the words of the note found in the other text. Every slide of [slides] counts, also those the
 * request did not hold. It costs no model, and it reads only the content of a note, or its title
 * when the content has no words.
 *
 * [slideShare] and [noteShare] come from the verdicts of the grader that did not change on the dev
 * lectures (docs/local-notes.md, phase 3).
 */
internal class RepeatCheck(
    slides: List<String>,
    private val slideShare: Double = SLIDE_SHARE,
    private val noteShare: Double = NOTE_SHARE
) : NoteCheck {
    private val slides = slides.map { wordsOf(it) }

    override fun droppedBecause(note: BenchNote, kept: List<BenchNote>): String? {
        val words = wordsOfNote(note)
        if (words.isEmpty()) return null
        val slide = slides.maxOfOrNull { share(words, it) } ?: 0.0
        if (slide >= slideShare) return "repeats a slide (${percent(slide)} of its words)"
        val before = kept.maxOfOrNull { share(words, wordsOfNote(it)) } ?: 0.0
        if (before >= noteShare) return "repeats a note (${percent(before)} of its words)"
        return null
    }

    private fun wordsOfNote(note: BenchNote): Set<String> = wordsOf(note.content).ifEmpty { wordsOf(note.title) }

    private fun share(words: Set<String>, other: Set<String>) = words.count { it in other }.toDouble() / words.size

    private fun percent(share: Double) = "${(share * 100).toInt()}%"

    companion object {
        const val SLIDE_SHARE = 0.35
        const val NOTE_SHARE = 0.60
        private const val STEM = 6
        private val WORD = Regex("""[\p{L}\p{N}]+""")
        private val STOP_WORDS = (
            "il lo la i gli le un uno una di a da in con su per tra fra e o ma se che chi cui non più come dove quando " +
                "del dello della dei degli delle al allo alla ai agli alle dal dallo dalla dai dagli dalle " +
                "nel nello nella nei negli nelle sul sullo sulla sui sugli sulle è sono essere ha hanno si ci ne " +
                "questo questa questi queste quello quella quelli quelle anche ad ed od ogni tutti tutte tutto tutta " +
                "può possono viene vengono"
            ).split(' ').toSet()

        /** The words of [text] that tell something: in lower case, cut to their first letters, without the short and the common ones. */
        fun wordsOf(text: String): Set<String> = WORD.findAll(text.lowercase()).map { it.value }
            .filter { it !in STOP_WORDS && (it.length >= 3 || it.all(Char::isDigit)) }
            .map { it.take(STEM) }
            .toSet()
    }
}
