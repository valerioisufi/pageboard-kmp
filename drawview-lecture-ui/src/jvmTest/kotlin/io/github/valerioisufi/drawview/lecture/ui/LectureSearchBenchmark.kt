package io.github.valerioisufi.drawview.lecture.ui

import io.github.valerioisufi.drawview.lecture.LectureLibrary
import io.github.valerioisufi.drawview.lecture.LectureRecord
import io.github.valerioisufi.drawview.lecture.NoteKind
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.writeLectureRecord
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import java.io.File
import kotlin.test.Test
import kotlin.time.measureTime
import kotlinx.coroutines.test.runTest

/**
 * The cost of the lectures in a pass of the search (see `docs/plans/performance.md`): the version of
 * the lectures of every document, which the indexer asks at every pass.
 */
class LectureSearchBenchmark {

    @Test
    fun theVersionOfTheLectures() = runTest {
        val root = File(checkNotNull(System.getProperty("drawview.dataDir")), "benchmarks/${newElementId()}/lectures")
        val library = LectureLibrary(root.absolutePath)
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        repeat(DOCUMENTS) { index ->
            val document = store.createDocument("Course $index")
            if (index < WITH_LECTURES) repeat(LECTURES_EACH) { lecture -> writeLecture(library, document.id, document.title, lecture) }
        }
        store.flush()
        val size = root.walk().filter { it.name == "lecture.json" }.sumOf { it.length() } / (WITH_LECTURES * LECTURES_EACH)
        val source = LectureSearchSource(library, store)
        val documents = store.documents.value

        repeat(2) { documents.forEach { source.version(it) } }
        val times = List(5) { measureTime { documents.forEach { source.version(it) } } }.sorted()
        report(
            "M2 the versions of the lectures in a pass ($DOCUMENTS notes, $WITH_LECTURES with $LECTURES_EACH lectures of 90 min, ${size / 1024} KiB each)",
            "${times[times.size / 2]}"
        )
    }

    private fun writeLecture(library: LectureLibrary, documentId: String, title: String, index: Int) {
        val startedAt = 1_700_000_000_000L + index * 86_400_000L
        val directory = library.newLectureDirectory(documentId, startedAt)
        val words = "la derivata della funzione composta si calcola con la regola della catena applicata ai limiti".split(' ')
        val record = LectureRecord(
            id = directory.substringAfterLast('/'),
            documentId = documentId,
            documentTitle = title,
            startedAt = startedAt,
            durationMs = 90 * 60_000L,
            // A segment every 5 seconds, of about 25 words
            transcript = List(90 * 12) { s ->
                TranscriptSegment(s * 5_000L, s * 5_000L + 4_800L, List(25) { words[(s + it) % words.size] }.joinToString(" "))
            },
            suggestions = List(20) { s ->
                NoteSuggestion(id = "s$s", kind = NoteKind.DEFINITION, title = "Definizione $s", content = List(60) { words[(s + it) % words.size] }.joinToString(" "), atMs = s * 240_000L)
            },
            finalReview = List(700) { words[it % words.size] }.joinToString(" ")
        )
        check(writeLectureRecord(directory, record))
    }

    private fun report(measure: String, value: String) = println("BENCHMARK $measure: $value")

    private companion object {
        const val DOCUMENTS = 101
        const val WITH_LECTURES = 20
        const val LECTURES_EACH = 3
    }
}
