package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.pageboard.lecture.audio.RecordingFile
import io.github.valerioisufi.pageboard.model.Document
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        val first = library.newLectureDirectory("5", 1_000)
        save(first, LectureRecord(id = "a", documentId = "5", startedAt = 1_000, durationMs = 60_000, finalReview = "# Review"))
        val second = library.newLectureDirectory("5", 2_000)
        save(second, LectureRecord(id = "b", documentId = "5", startedAt = 2_000, durationMs = 30_000))
        save(library.newLectureDirectory("6", 3_000), LectureRecord(id = "c", documentId = "6", startedAt = 3_000))

        val lectures = library.list("5")
        assertEquals(listOf(second, first), lectures.map { it.directory })
        assertTrue(lectures[1].hasReview)
        assertFalse(lectures[0].hasReview)
        assertEquals(emptyList(), library.list("42"))

        assertTrue(library.delete(first))
        assertFalse(File(first).exists())
        assertEquals(listOf(second), library.list("5").map { it.directory })
    }

    @Test
    fun aRecordSavedWithThePathOfItsFolderIsReadWithItsName() {
        val directory = library.newLectureDirectory("5", 1_000)
        val name = File(directory).name
        // As Windows saved a lecture loaded from the library, until it split the path on '\'
        val saved = LectureRecord(
            id = "C:\\Users\\me\\.pencilnotes\\resources\\lectures\\doc-5\\$name", documentId = "5", startedAt = 1_000,
            transcript = listOf(TranscriptSegment(0, 1_000, "Buongiorno"))
        )
        save(directory, saved)
        val bytes = File(directory, "lecture.json").readBytes()

        val record = assertNotNull(library.readRecord(directory))
        assertEquals(saved.copy(id = name), record)
        // The version of the other devices, which have the name
        assertEquals(LectureRecords.version(saved.copy(id = name)), LectureRecords.version(record))
        assertEquals(name, library.load(library.list("5").single().directory)?.id)
        // Read, not written
        assertContentEquals(bytes, File(directory, "lecture.json").readBytes())
    }

    @Test
    fun theAudioAskedForWithThePathOfItsLectureIsReadWithItsName() {
        val sharing = LectureSharingSettings(
            downloadAudio = AudioDownload.ON_REQUEST,
            requestedAudio = setOf("C:\\Users\\me\\.pencilnotes\\resources\\lectures\\doc-5\\2026-10-01_10-00-00-a1b2c3", "2026-10-02_10-00-00-d4e5f6")
        )
        File(library.documentDirectory("5")).mkdirs()
        val file = File(library.documentDirectory("5"), "sharing.json").apply {
            writeText(Lecture.recordJson.encodeToString(LectureSharingSettings.serializer(), sharing))
        }
        val bytes = file.readBytes()

        assertEquals(
            sharing.copy(requestedAudio = setOf("2026-10-01_10-00-00-a1b2c3", "2026-10-02_10-00-00-d4e5f6")),
            library.sharingOf("5")
        )
        assertContentEquals(bytes, file.readBytes())
    }

    @Test
    fun theCourseContextIsKeptNextToTheLectures() {
        assertEquals(null, library.loadContext("5"))
        val context = CourseContext("Sicurezza informatica\nMAC; funzioni hash\nHMAC, SHA-256", edited = true, generatedAt = 7)
        assertTrue(library.saveContext("5", context))
        assertEquals(context, library.loadContext("5"))
        // Not a lecture
        save(library.newLectureDirectory("5", 1_000), LectureRecord(id = "a", documentId = "5", startedAt = 1_000))
        assertEquals(1, library.list("5").size)
    }

    @Test
    fun twoLecturesStartedInTheSameSecondGetTwoFolders() {
        val first = library.newLectureDirectory("5", 1_000)
        File(first).mkdirs()
        assertNotEquals(first, library.newLectureDirectory("5", 1_000))
    }

    @Test
    fun aLectureBeingRecordedOnAnotherDeviceStaysAsItIs() {
        val directory = library.newLectureDirectory("5", 1_000)
        save(
            directory,
            LectureRecord(
                id = "a", documentId = "5", startedAt = 1_000, status = LectureStatus.RECORDING, recordedBy = "tablet", recordedOn = "iPad",
                stamps = mapOf(LecturePart.RECORDING to Stamp(5, "tablet"))
            )
        )
        val summary = library.list("5").single()
        assertTrue(summary.activeElsewhere)
        assertEquals("iPad", summary.recordedElsewhere)
        assertEquals(LectureStatus.RECORDING, library.load(directory)?.status)
    }

    @Test
    fun theLecturesDeletedAreRememberedForTheOtherDevices() {
        val directory = library.newLectureDirectory("5", 1_000)
        save(directory, LectureRecord(id = "a", documentId = "5", startedAt = 1_000))
        assertTrue(library.delete(directory))
        assertEquals(setOf(File(directory).name), library.deletedLectures("5").keys)
        // Received again from a device that did not know: not listed
        save(directory, LectureRecord(id = "a", documentId = "5", startedAt = 1_000))
        assertTrue(library.list("5").isEmpty())
    }

    @Test
    fun anInterruptedRecordingIsRecovered() {
        val directory = library.newLectureDirectory("5", 1_000)
        save(directory, LectureRecord(id = "a", documentId = "5", startedAt = 1_000, durationMs = 1_000, status = LectureStatus.RECORDING))
        // Two and a half seconds of audio, the recording never finished: the pages of two seconds written
        RecordingFile(directory).apply {
            open()
            append(ShortArray(5 * LECTURE_SAMPLE_RATE / 2))
        }

        assertTrue(library.list("5").single().wasRecording)
        val record = assertNotNull(library.load(directory))
        assertEquals(LectureStatus.FINISHED, record.status)
        assertEquals(2_000, record.durationMs)
        assertEquals(2_000, RecordingFile(directory).durationMs())
        assertNull(library.load("$directory-missing"))
    }

    @Test
    fun suggestionsFindTheirPageAfterAReorder() {
        val document = Document()
        document.addPage()
        document.addPage()
        val suggestion = NoteSuggestion("n", NoteKind.OTHER, "t", "c", pageIndex = 0, pageId = document.pages[0].id)
        assertEquals(0, suggestion.resolvedPageIndex(document))

        // The first page moved to the end
        document.movePage(0, document.pages.lastIndex)
        assertEquals(2, suggestion.resolvedPageIndex(document))
        // Unknown page: the saved index
        assertEquals(0, suggestion.copy(pageId = "unknown").resolvedPageIndex(document))
        assertNull(suggestion.copy(pageId = "", pageIndex = 7).resolvedPageIndex(document))
    }
}
