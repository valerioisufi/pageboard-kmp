package io.github.valerioisufi.pageboard.lecture.ui

import io.github.valerioisufi.pageboard.lecture.LectureLibrary
import io.github.valerioisufi.pageboard.lecture.LectureRecord
import io.github.valerioisufi.pageboard.lecture.LectureStatus
import io.github.valerioisufi.pageboard.lecture.NoteKind
import io.github.valerioisufi.pageboard.lecture.NoteSuggestion
import io.github.valerioisufi.pageboard.lecture.SuggestionStatus
import io.github.valerioisufi.pageboard.lecture.TranscriptSegment
import io.github.valerioisufi.pageboard.lecture.writeLectureRecord
import io.github.valerioisufi.pageboard.platform.PlatformFiles
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import io.github.valerioisufi.pageboard.search.SearchAnchor
import io.github.valerioisufi.pageboard.search.SearchChunkKind
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LectureSearchSourceTest {
    @Test
    fun indexesTranscriptWindowsReviewNonDismissedSuggestionsAndHandwriting() = runTest {
        val root = "/tmp/pageboard-lecture-search-test-${currentTimeMillis()}-${Random.nextInt(100000)}"
        val library = LectureLibrary(root)
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val doc = store.createDocument("Analisi Matematica")
        val page1 = doc.pages.single()
        val page2 = doc.addPage()
        store.savePage(doc, page2)
        store.flush()

        val summary = store.documents.value.single()
        val source = LectureSearchSource(library = library, store = store)

        assertNull(source.version(summary))
        assertTrue(source.chunks(summary).isEmpty())

        val lectureDir = library.newLectureDirectory(doc.id, startedAt = 1_700_000_000_000L)
        val lectureId = lectureDir.substringAfterLast('/')
        val record = LectureRecord(
            id = lectureId,
            documentId = doc.id,
            documentTitle = doc.title,
            startedAt = 1_700_000_000_000L,
            durationMs = 150_000L,
            status = LectureStatus.FINISHED,
            transcript = listOf(
                TranscriptSegment(startMs = 5_000L, endMs = 20_000L, text = "Oggi dimostriamo il teorema di Bolzano-Weierstrass."),
                TranscriptSegment(startMs = 25_000L, endMs = 45_000L, text = "Ogni successione limitata ammette una sottosuccessione convergente."),
                TranscriptSegment(startMs = 75_000L, endMs = 95_000L, text = "Passiamo ora al criterio di Cauchy per le successioni reali.")
            ),
            suggestions = listOf(
                NoteSuggestion(
                    id = "s1",
                    kind = NoteKind.THEOREM,
                    title = "Teorema di Bolzano-Weierstrass",
                    content = "Ogni successione in $\\mathbb{R}^n$ limitata ha una sottosuccessione convergente.",
                    atMs = 15_000L,
                    status = SuggestionStatus.NEW
                ),
                NoteSuggestion(
                    id = "s2",
                    kind = NoteKind.OTHER,
                    title = "Scartato",
                    content = "Nota scartata dallo studente",
                    atMs = 30_000L,
                    status = SuggestionStatus.DISMISSED
                )
            ),
            finalReview = "# Riepilogo della lezione\n\nCompletezza di $\\mathbb{R}$ e successioni di Cauchy."
        )
        writeLectureRecord(lectureDir, record)

        val handwritingJson = """
            {
              "block-1": { "pageId": "${page2.id}", "text": "Dimostrazione per bisezione dell'intervallo $[a_k, b_k]$" },
              "block-2": { "pageId": "${page1.id}", "text": " Appunti iniziali " }
            }
        """.trimIndent()
        PlatformFiles.writeBytes("${library.documentDirectory(doc.id)}/handwriting.json", handwritingJson.encodeToByteArray())

        try {
            val version = source.version(summary)
            assertNotNull(version)

            val chunks = source.chunks(summary)
            val transcriptChunks = chunks.filter { it.kind == SearchChunkKind.LECTURE_TRANSCRIPT }
            assertEquals(2, transcriptChunks.size)
            assertEquals(SearchAnchor.Lecture(doc.id, lectureId, 5_000L), transcriptChunks[0].anchor)
            assertTrue("Bolzano-Weierstrass" in transcriptChunks[0].text)
            assertTrue("sottosuccessione" in transcriptChunks[0].text)
            assertEquals(SearchAnchor.Lecture(doc.id, lectureId, 75_000L), transcriptChunks[1].anchor)
            assertTrue("Cauchy" in transcriptChunks[1].text)

            val noteChunks = chunks.filter { it.kind == SearchChunkKind.LECTURE_NOTES }
            assertEquals(2, noteChunks.size)
            assertTrue(noteChunks.any { "Riepilogo della lezione" in it.text && (it.anchor as SearchAnchor.Lecture).startMs == 0L })
            assertTrue(noteChunks.any { "\\mathbb{R}^n" in it.text && (it.anchor as SearchAnchor.Lecture).startMs == 15_000L })
            assertTrue(noteChunks.none { "Scartato" in it.text })

            val handwritingChunks = chunks.filter { it.kind == SearchChunkKind.HANDWRITING }
            assertEquals(2, handwritingChunks.size)
            val page2Chunk = handwritingChunks.first { (it.anchor as SearchAnchor.Page).pageId == page2.id }
            assertEquals(2, (page2Chunk.anchor as SearchAnchor.Page).pageNumber)
            assertTrue("[a_k, b_k]" in page2Chunk.text)
        } finally {
            library.delete(lectureDir)
            PlatformFiles.delete("${library.documentDirectory(doc.id)}/handwriting.json")
            PlatformFiles.delete("${library.documentDirectory(doc.id)}/deleted.json")
            PlatformFiles.delete(library.documentDirectory(doc.id))
            PlatformFiles.delete("$root/device-id")
            PlatformFiles.delete(root)
        }
    }
}
