package io.github.valerioisufi.drawview.lecture

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class LectureAttachmentsTest {
    private val root: File = Files.createTempDirectory("attachments").toFile()
    private val files = LectureLibrary(root.absolutePath)
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        root.deleteRecursively()
    }

    /** The assistant of this device, recording the lecture in [recording] (as the recorder has its folder). */
    private inner class Recording(private val recording: String) : LectureAssistantLibrary {
        override val files: LectureLibrary get() = this@LectureAttachmentsTest.files
        override suspend fun loadedLecture(directory: String): Lecture? = null
        override suspend fun isActiveHere(directory: String): Boolean = directory == recording
        override suspend fun onReceived(documentId: String) {}
        override suspend fun onContextReceived(documentId: String, context: CourseContext) {}
        override suspend fun onDeleted(documentId: String, directory: String) {}
        override suspend fun onArrival(arrival: LectureArrival) {}
    }

    @Test
    fun theLectureBeingRecordedHereIsNotRecoveredByTheIndex() = runBlocking<Unit> {
        // Saved while recording here: one is being recorded, the other was interrupted
        val recording = files.newLectureDirectory("5", 1_000)
        val interrupted = files.newLectureDirectory("5", 2_000)
        for (directory in listOf(recording, interrupted)) {
            File(directory).mkdirs()
            writeLectureRecord(
                directory,
                LectureRecord(
                    id = File(directory).name, documentId = "5", startedAt = 1_000, status = LectureStatus.RECORDING,
                    stamps = mapOf(LecturePart.RECORDING to Stamp(5, files.deviceId))
                )
            )
        }

        // Read from the folders (listed with '\' on Windows)
        val versions = LectureAttachments(Recording(recording), scope).versions("5")

        assertEquals(LectureStatus.RECORDING, files.readRecord(recording)?.status)
        assertEquals(LectureStatus.FINISHED, files.readRecord(interrupted)?.status)
        assertTrue(versions.keys.containsAll(listOf("lecture/${File(recording).name}", "lecture/${File(interrupted).name}")))
    }
}
