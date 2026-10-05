package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.Wav
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LectureLibraryTest {
    private val root: File = Files.createTempDirectory("lectures").toFile()
    private val library = LectureLibrary(root.absolutePath)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun save(directory: String, record: LectureRecord) {
        File(directory).mkdirs()
        File(directory, "lecture.json").writeText(Lecture.recordJson.encodeToString(LectureRecord.serializer(), record))
    }

    @Test
    fun lecturesAreListedNewestFirstAndDeleted() {
        val first = library.newLectureDirectory(5, 1_000)
        save(first, LectureRecord(id = "a", documentId = 5, startedAt = 1_000, durationMs = 60_000, finalReview = "# Review"))
        val second = library.newLectureDirectory(5, 2_000)
        save(second, LectureRecord(id = "b", documentId = 5, startedAt = 2_000, durationMs = 30_000))
        save(library.newLectureDirectory(6, 3_000), LectureRecord(id = "c", documentId = 6, startedAt = 3_000))

        val lectures = library.list(5)
        assertEquals(listOf(second, first), lectures.map { it.directory })
        assertTrue(lectures[1].hasReview)
        assertFalse(lectures[0].hasReview)
        assertEquals(emptyList(), library.list(42))

        assertTrue(library.delete(first))
        assertFalse(File(first).exists())
        assertEquals(listOf(second), library.list(5).map { it.directory })
    }

    @Test
    fun theCourseContextIsKeptNextToTheLectures() {
        assertEquals(null, library.loadContext(5))
        val context = CourseContext("Sicurezza informatica\nMAC; funzioni hash\nHMAC, SHA-256", edited = true, generatedAt = 7)
        assertTrue(library.saveContext(5, context))
        assertEquals(context, library.loadContext(5))
        // Not a lecture
        save(library.newLectureDirectory(5, 1_000), LectureRecord(id = "a", documentId = 5, startedAt = 1_000))
        assertEquals(1, library.list(5).size)
    }

    @Test
    fun twoLecturesStartedInTheSameSecondGetTwoFolders() {
        val first = library.newLectureDirectory(5, 1_000)
        File(first).mkdirs()
        assertNotEquals(first, library.newLectureDirectory(5, 1_000))
    }

    @Test
    fun anInterruptedRecordingIsRecovered() {
        val directory = library.newLectureDirectory(5, 1_000)
        save(directory, LectureRecord(id = "a", documentId = 5, startedAt = 1_000, durationMs = 1_000, status = LectureStatus.RECORDING))
        // Two seconds of raw audio, never converted
        File(directory, "audio.pcm").writeBytes(ByteArray(2 * LECTURE_SAMPLE_RATE * 2))

        assertTrue(library.list(5).single().wasRecording)
        val record = assertNotNull(library.load(directory))
        assertEquals(LectureStatus.FINISHED, record.status)
        assertEquals(2_000, record.durationMs)
        assertFalse(File(directory, "audio.pcm").exists())
        assertEquals(Wav.HEADER_SIZE + 2L * LECTURE_SAMPLE_RATE * 2, File(directory, "audio.wav").length())
        assertNull(library.load("$directory-missing"))
    }

    @Test
    fun suggestionsFindTheirPageAfterAReorder() {
        val document = DrawDocument()
        document.addPage()
        document.addPage()
        document.pages.forEachIndexed { i, page -> page.dbId = 100 + i }
        val suggestion = NoteSuggestion("n", NoteKind.OTHER, "t", "c", pageIndex = 0, pageDbId = 100)
        assertEquals(0, suggestion.resolvedPageIndex(document))

        // The first page moved to the end
        document.pages.add(document.pages.removeAt(0))
        assertEquals(2, suggestion.resolvedPageIndex(document))
        // Unknown page: the saved index
        assertEquals(0, suggestion.copy(pageDbId = 999).resolvedPageIndex(document))
        assertNull(suggestion.copy(pageDbId = 0, pageIndex = 7).resolvedPageIndex(document))
    }
}
