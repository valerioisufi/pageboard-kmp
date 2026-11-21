package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.pageboard.lecture.audio.RecordingFile
import io.github.valerioisufi.pageboard.lecture.testing.MemorySecrets
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class RecordingExportTest {
    private val directory: File = Files.createTempDirectory("recording-export-test").toFile()
    private val stateContext = newSingleThreadContext("recording-export-state")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
        stateContext.close()
    }

    private fun newAssistant(library: LectureLibrary): LectureAssistant = LectureAssistant(
        settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()),
        captureFactory = { null },
        audioOutput = null,
        library = library,
        stateContext = stateContext
    )

    private fun createLectureWithAudio(
        library: LectureLibrary,
        documentId: String,
        startedAt: Long,
        seconds: Int = 2,
        status: LectureStatus = LectureStatus.FINISHED,
        transcript: List<TranscriptSegment> = emptyList(),
        withAudio: Boolean = true,
        stamps: Map<LecturePart, Stamp> = emptyMap(),
        recordedBy: String = library.deviceId
    ): LectureRecord {
        val dir = library.newLectureDirectory(documentId, startedAt)
        File(dir).mkdirs()
        if (withAudio) {
            RecordingFile(dir).apply {
                open()
                append(ShortArray(seconds * LECTURE_SAMPLE_RATE) { (6000 * sin(2 * PI * 300 * it / LECTURE_SAMPLE_RATE)).toInt().toShort() })
                finish()
            }
        }
        val record = LectureRecord(
            id = File(dir).name,
            documentId = documentId,
            documentTitle = "Notebook $documentId",
            startedAt = startedAt,
            durationMs = seconds * 1000L,
            status = status,
            transcript = transcript,
            recordedBy = recordedBy,
            stamps = stamps
        )
        assertTrue(writeLectureRecord(dir, record))
        return record
    }

    private suspend fun openLecture(assistant: LectureAssistant, library: LectureLibrary, documentId: String): Lecture {
        val summary = library.list(documentId).single()
        withContext(stateContext) { assistant.show(documentId, summary) }
        return withTimeout(5_000) {
            while (true) {
                val shown = withContext(stateContext) { assistant.shownLecture(documentId) }
                if (shown != null) return@withTimeout shown
                delay(10)
            }
            error("unreachable")
        }
    }

    @Test
    fun concurrentAudioExportsSerializeWithoutConflictsAndKeepAllResults() = runBlocking {
        val library = LectureLibrary(File(directory, "lectures").absolutePath)
        val assistant = newAssistant(library)
        createLectureWithAudio(library, "doc-a", 1_773_500_000_000L, seconds = 2)
        createLectureWithAudio(library, "doc-b", 1_773_500_100_000L, seconds = 2)
        val emptyDir = library.newLectureDirectory("doc-c", 1_773_500_200_000L).also { File(it).mkdirs() }
        val rEmpty = LectureRecord(id = File(emptyDir).name, documentId = "doc-c", startedAt = 1_773_500_200_000L)
        writeLectureRecord(emptyDir, rEmpty)

        val l1 = openLecture(assistant, library, "doc-a")
        val l2 = openLecture(assistant, library, "doc-b")
        val lEmpty = openLecture(assistant, library, "doc-c")
        assertTrue(l1.hasAudio && l2.hasAudio)

        val out1 = File(directory, "first.m4a")
        val outSameAs1 = File(directory, "first.m4a")
        val out2 = File(directory, "second.m4a")
        val outFail = File(directory, "empty.m4a")

        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondDelivered = CompletableDeferred<Unit>()
        val thirdDelivered = CompletableDeferred<Unit>()

        // Failing export completes and remains in exportResults when subsequent exports start
        assistant.exportAudio(lEmpty, outFail.absolutePath)
        withTimeout(10_000) {
            while (withContext(stateContext) { assistant.exportResults.size } < 1 || withContext(stateContext) { assistant.exportingFileName } != null) delay(10)
        }

        assistant.exportAudio(l1, out1.absolutePath) {
            firstStarted.complete(Unit)
            releaseFirst.await()
        }
        // Wait until first valid export is delivering (holding exportQueue) before queueing the others
        withTimeout(10_000) { firstStarted.await() }
        assistant.exportAudio(l1, outSameAs1.absolutePath) { secondDelivered.complete(Unit) }
        assistant.exportAudio(l2, out2.absolutePath) { thirdDelivered.complete(Unit) }

        withTimeout(10_000) {
            while (withContext(stateContext) { assistant.queuedExports } < 2) delay(10)
        }
        // Second export to the exact same path must not run yet until first finishes
        assertFalse(secondDelivered.isCompleted)

        releaseFirst.complete(Unit)
        withTimeout(10_000) {
            secondDelivered.await()
            thirdDelivered.await()
            while (withContext(stateContext) { assistant.exportResults.size < 4 || assistant.exportingFileName != null }) delay(10)
        }

        val results = withContext(stateContext) { assistant.exportResults.toList() }
        assertEquals(4, results.size)
        assertTrue(results[0].failure != null, "Empty lecture export should keep its failure in exportResults")
        assertNull(results[1].failure)
        assertNull(results[2].failure)
        assertNull(results[3].failure)
        assertEquals(listOf("first.m4a", "first.m4a", "second.m4a"), results.drop(1).map { it.fileName })
        assertEquals("second.m4a", withContext(stateContext) { assistant.exportResult?.fileName })

        for (file in listOf(out1, out2)) {
            assertTrue(file.isFile && file.length() > 100, "${file.name} should be written")
            assertEquals("ftyp", file.readBytes().copyOfRange(4, 8).decodeToString())
        }
        val leftoverFrames = directory.listFiles()?.filter { it.name.endsWith(".frames") }.orEmpty()
        assertTrue(leftoverFrames.isEmpty(), "Unexpected leftover frames: $leftoverFrames")
    }

    @Test
    fun exportableRecordingsFiltersUnfinishedProtectsFromDeleteAndHonorsCancellation() = runBlocking {
        val library = LectureLibrary(File(directory, "lectures").absolutePath)
        val assistant = newAssistant(library)

        val tOld = 1_773_500_000_000L
        val tNew = 1_773_586_400_000L
        val tNoAudio = 1_773_600_000_000L
        createLectureWithAudio(
            library,
            "doc",
            startedAt = tNew,
            transcript = listOf(TranscriptSegment(5_000, 7_000, "Second"))
        )
        val oldest = createLectureWithAudio(
            library,
            "doc",
            startedAt = tOld,
            transcript = listOf(TranscriptSegment(1_000, 3_000, "First"))
        )
        createLectureWithAudio(
            library,
            "doc",
            startedAt = tNoAudio,
            withAudio = false,
            transcript = listOf(TranscriptSegment(2_000, 4_000, "Text only"))
        )
        // Still recording here and active elsewhere must be excluded
        createLectureWithAudio(library, "doc", startedAt = tNoAudio + 60_000, status = LectureStatus.RECORDING)
        createLectureWithAudio(
            library,
            "doc",
            startedAt = tNoAudio + 120_000,
            status = LectureStatus.RECORDING,
            recordedBy = "other-device",
            stamps = mapOf(LecturePart.RECORDING to Stamp(at = 1L, by = "other-device"))
        )

        val exportable = assistant.exportableRecordings("doc")
        assertEquals(listOf(tOld, tNew, tNoAudio), exportable.map { it.startedAt })
        assertEquals(listOf(true, true, false), exportable.map { it.hasAudio })
        assertEquals("[0:01] First\n", exportable[0].transcript)
        assertEquals("[0:05] Second\n", exportable[1].transcript)

        val expectedStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(tOld))
        assertEquals(expectedStamp, formatRecordingTimestamp(tOld))

        // Delete is ignored while writeRecordingAudio is running on that directory
        val targetM4a = File(directory, "batch.m4a")
        val summary = library.list("doc").first { File(it.directory).name == oldest.id }
        var deletedDuringExportAttempted = false
        assistant.writeRecordingAudio(exportable[0].directory, targetM4a.absolutePath) {
            if (!deletedDuringExportAttempted) {
                deletedDuringExportAttempted = true
                assistant.delete("doc", summary)
            }
        }
        assertTrue(deletedDuringExportAttempted)
        assertTrue(File(exportable[0].directory, "lecture.json").exists(), "Lecture must not be deleted while exporting")
        assertTrue(targetM4a.isFile && targetM4a.length() > 100)
        assertEquals("ftyp", targetM4a.readBytes().copyOfRange(4, 8).decodeToString())

        // Cancellation interrupts writeRecordingAudio cleanly and leaves no .m4a or .frames files
        createLectureWithAudio(library, "doc2", startedAt = tOld, seconds = 15)
        val longDir = library.list("doc2").single().directory
        val cancelledTarget = File(directory, "cancelled.m4a")
        val started = CompletableDeferred<Unit>()
        val exportJob = launch {
            assistant.writeRecordingAudio(longDir, cancelledTarget.absolutePath) {
                started.complete(Unit)
            }
        }
        withTimeout(10_000) { started.await() }
        exportJob.cancel()
        exportJob.join()
        assertTrue(exportJob.isCancelled)
        assertFalse(cancelledTarget.exists(), "Cancelled writeRecordingAudio should not leave output m4a")
        val leftoverFrames = directory.listFiles()?.filter { it.name.endsWith(".frames") }.orEmpty()
        assertTrue(leftoverFrames.isEmpty())

        // After export is done, delete removes it normally
        withContext(stateContext) { assistant.delete("doc", summary) }
        withTimeout(5_000) {
            while (File(exportable[0].directory).exists()) delay(10)
        }
        assertFalse(File(exportable[0].directory).exists())
    }
}
