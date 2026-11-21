package io.github.valerioisufi.pageboard.export

import io.github.valerioisufi.pageboard.platform.PlatformFiles
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

class RecordingsExportTest {

    private inline fun <T> withTempDir(block: (File) -> T): T {
        val dir = File.createTempFile("recordings_export", "").apply { delete(); mkdirs() }
        return try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test
    fun streamingZipWriterHandlesLargeMultiChunkFilesAndTimestamps() {
        withTempDir { dir ->
            val payload = ByteArray(200_000) { ((it * 31) and 0xFF).toByte() }
            val sourceFile = File(dir, "audio.m4a").apply { writeBytes(payload) }
            val zipFile = File(dir, "out.zip")
            val modified = 1_773_500_967_000L
            val created = 1_770_000_000_000L

            val writer = StreamingZipWriter(Path(zipFile.absolutePath))
            writer.addDirectory("Course", ZipEntryTimes(modified, created))
            writer.addFile("Course/Lecture.m4a", Path(sourceFile.absolutePath), ZipEntryTimes(modified, created))
            writer.addEntry("Course/Lecture.txt", "hello\n".encodeToByteArray(), ZipEntryTimes(modified, created))
            writer.finish()

            ZipFile(zipFile).use { zip ->
                assertEquals(listOf("Course/", "Course/Lecture.m4a", "Course/Lecture.txt"), zip.entries().toList().map { it.name })
                val audio = assertNotNull(zip.getEntry("Course/Lecture.m4a"))
                assertTrue(payload.contentEquals(zip.getInputStream(audio).readBytes()))
                assertEquals(modified, audio.lastModifiedTime.toMillis())
                assertEquals(created, audio.creationTime.toMillis())
                val transcript = assertNotNull(zip.getEntry("Course/Lecture.txt"))
                assertEquals("hello\n", zip.getInputStream(transcript).readBytes().decodeToString())
            }
        }
    }

    @Test
    fun batchExportPreservesFolderStructureDatesAndDisambiguatesNames() = runBlocking<Unit> {
        var now = 1_760_000_000_000L
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), clock = { now })
        val export = DocumentExport(OpenDocuments(store, Dispatchers.Default))

        store.createFolder("Math", parentId = null)
        val math = store.folders.first().single { it.name == "Math" }
        now += 60_000
        store.createFolder("Geometry", parentId = math.id)
        val geometry = store.folders.first().single { it.name == "Geometry" }
        store.createFolder("EmptySubject", parentId = null)
        val emptySubject = store.folders.first().single { it.name == "EmptySubject" }

        val calc1 = store.createDocument("Calculus", folderId = math.id).also { store.closeDocument(it.id) }.id
        val calc2 = store.createDocument("Calculus", folderId = math.id).also { store.closeDocument(it.id) }.id
        val topo = store.createDocument("Topology", folderId = geometry.id).also { store.closeDocument(it.id) }.id
        val noLectures = store.createDocument("EmptyNote", folderId = emptySubject.id).also { store.closeDocument(it.id) }.id
        val loose = store.createDocument("Loose", folderId = null).also { store.closeDocument(it.id) }.id

        val t1 = 1_773_500_000_000L
        val t2 = 1_773_586_400_000L
        val byDoc = mapOf(
            loose to listOf(
                ExportedRecording("2026-11-20 09.00.m4a", t1, transcriptFileName = "2026-11-20 09.00.txt", transcript = "[0:00] Loose\n") { p, prog ->
                    File(p).writeBytes("loose-1".encodeToByteArray())
                    prog(1f)
                }
            ),
            calc1 to listOf(
                ExportedRecording("2026-11-20 09.00.m4a", t1, transcriptFileName = "2026-11-20 09.00.txt", transcript = "[0:00] Calc A\n") { p, prog ->
                    File(p).writeBytes("calc-1a".encodeToByteArray())
                    prog(1f)
                },
                // Duplicate timestamp within the same note
                ExportedRecording("2026-11-20 09.00.m4a", t1, transcriptFileName = "2026-11-20 09.00.txt", transcript = "   ") { p, prog ->
                    File(p).writeBytes("calc-1b".encodeToByteArray())
                    prog(1f)
                }
            ),
            calc2 to listOf(
                ExportedRecording("2026-11-20 11.00.m4a", t2, transcript = null) { p, prog ->
                    File(p).writeBytes("calc-2".encodeToByteArray())
                    prog(1f)
                }
            ),
            topo to listOf(
                ExportedRecording("2026-11-21 10.00.m4a", t2, transcriptFileName = "2026-11-21 10.00.txt", transcript = "[0:02] Topology\n") { p, prog ->
                    File(p).writeBytes("topo-1".encodeToByteArray())
                    prog(1f)
                }
            ),
            noLectures to emptyList()
        )
        val recordings = DocumentRecordings { id -> byDoc[id].orEmpty() }
        val writers = ExportWriters(PdfExporter(textAdapter = null), recordings = recordings)

        withTempDir { dir ->
            val contents = export.resolve(ExportScope.Folder(null), includeSubfolders = true)
            val audioOnlyZip = File(dir, "audio-only.zip").absolutePath
            val progressEvents = mutableListOf<ExportProgress>()
            val outcome = export.export(contents, ExportFormat.RECORDINGS, ExportOptions(includeTranscripts = false), writers, audioOnlyZip) {
                progressEvents += it
            }

            assertEquals(4, outcome.documents)
            assertEquals(0, outcome.skipped)
            assertTrue(progressEvents.isNotEmpty())
            assertEquals(1f, progressEvents.last().fraction)

            ZipFile(audioOnlyZip).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertEquals(
                    listOf(
                        "Loose - 2026-11-20 09.00.m4a",
                        "Math/",
                        "Math/Calculus - 2026-11-20 09.00.m4a",
                        "Math/Calculus - 2026-11-20 09.00 (2).m4a",
                        "Math/Calculus (2) - 2026-11-20 11.00.m4a",
                        "Math/Geometry/",
                        "Math/Geometry/Topology - 2026-11-21 10.00.m4a"
                    ),
                    names
                )
                assertFalse("EmptySubject/" in names)
                assertEquals("calc-1a", zip.getInputStream(zip.getEntry("Math/Calculus - 2026-11-20 09.00.m4a")).readBytes().decodeToString())
                assertEquals("calc-1b", zip.getInputStream(zip.getEntry("Math/Calculus - 2026-11-20 09.00 (2).m4a")).readBytes().decodeToString())
                assertEquals(t1, zip.getEntry("Math/Calculus - 2026-11-20 09.00.m4a").lastModifiedTime.toMillis())
            }

            // Include transcripts when requested (skips blank/null transcripts)
            val withTranscriptsZip = File(dir, "with-transcripts.zip").absolutePath
            export.export(contents, ExportFormat.RECORDINGS, ExportOptions(includeTranscripts = true), writers, withTranscriptsZip)
            ZipFile(withTranscriptsZip).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertTrue("Loose - 2026-11-20 09.00.txt" in names)
                assertTrue("Math/Calculus - 2026-11-20 09.00.txt" in names)
                assertFalse("Math/Calculus - 2026-11-20 09.00 (2).txt" in names)
                assertFalse("Math/Calculus (2) - 2026-11-20 11.00.txt" in names)
                assertTrue("Math/Geometry/Topology - 2026-11-21 10.00.txt" in names)
                assertEquals("[0:00] Calc A\n", zip.getInputStream(zip.getEntry("Math/Calculus - 2026-11-20 09.00.txt")).readBytes().decodeToString())
            }

            // Exclude subfolders when includeSubfolders = false
            val mathOnlyContents = export.resolve(ExportScope.Folder(math.id), includeSubfolders = false)
            val mathOnlyZip = File(dir, "math-direct.zip").absolutePath
            export.export(mathOnlyContents, ExportFormat.RECORDINGS, ExportOptions(), writers, mathOnlyZip)
            ZipFile(mathOnlyZip).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertEquals(
                    listOf(
                        "Calculus - 2026-11-20 09.00.m4a",
                        "Calculus - 2026-11-20 09.00 (2).m4a",
                        "Calculus (2) - 2026-11-20 11.00.m4a"
                    ),
                    names
                )
            }
        }
    }

    @Test
    fun missingAudioTranscriptsErrorsAndCancellationAreHandled() = runBlocking<Unit> {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage())
        val export = DocumentExport(OpenDocuments(store, Dispatchers.Default))
        val docWithAudio = store.createDocument("HasAudio").also { store.closeDocument(it.id) }.id
        val docMissingAudio = store.createDocument("MissingAudio").also { store.closeDocument(it.id) }.id

        val recordings = DocumentRecordings { id ->
            when (id) {
                docWithAudio -> listOf(
                    ExportedRecording("2026-11-21 09.00.m4a", 1_773_500_000_000L, hasAudio = true) { p, _ ->
                        File(p).writeBytes("audio".encodeToByteArray())
                    }
                )
                docMissingAudio -> listOf(
                    ExportedRecording("2026-11-21 10.00.m4a", 1_773_500_000_000L, hasAudio = false, transcript = "[0:00] Only text\n") { _, _ ->
                        error("should not write audio")
                    }
                )
                else -> emptyList()
            }
        }
        val writers = ExportWriters(PdfExporter(textAdapter = null), recordings = recordings)

        withTempDir { dir ->
            val contents = export.resolve(ExportScope.Folder(null))
            val path = File(dir, "partial.zip").absolutePath
            val outcome = export.export(contents, ExportFormat.RECORDINGS, ExportOptions(includeTranscripts = false), writers, path)
            assertEquals(1, outcome.documents)
            assertEquals(1, outcome.skipped)
            assertEquals(listOf("MissingAudio"), outcome.skippedIn)

            // With includeTranscripts = true, transcript-only entry is still exported while audio is counted as skipped
            val withTextPath = File(dir, "partial-with-text.zip").absolutePath
            val textOutcome = export.export(contents, ExportFormat.RECORDINGS, ExportOptions(includeTranscripts = true), writers, withTextPath)
            assertEquals(2, textOutcome.documents)
            assertEquals(1, textOutcome.skipped)
            assertEquals(listOf("MissingAudio"), textOutcome.skippedIn)
            ZipFile(withTextPath).use { zip ->
                assertNotNull(zip.getEntry("MissingAudio - 2026-11-21 10.00.txt"))
            }

            // Nothing exportable throws NOTHING_TO_EXPORT without creating output file
            val emptyPath = File(dir, "should-not-exist.zip")
            val emptyScope = export.resolve(ExportScope.Pages(docMissingAudio))
            val nothing = assertFailsWith<ExportException> {
                export.export(emptyScope, ExportFormat.RECORDINGS, ExportOptions(includeTranscripts = false), writers, emptyPath.absolutePath)
            }
            assertEquals(ExportFailure.NOTHING_TO_EXPORT, nothing.failure)
            assertFalse(emptyPath.exists())

            // Unwritable target throws NOT_WRITABLE
            val unwritable = assertFailsWith<ExportException> {
                export.export(contents, ExportFormat.RECORDINGS, ExportOptions(), writers, "/no/such/dir/recordings.zip")
            }
            assertEquals(ExportFailure.NOT_WRITABLE, unwritable.failure)

            // Cancellation cleans up partial ZIP and temporary recording files
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val cancellingWriters = ExportWriters(
                PdfExporter(textAdapter = null),
                recordings = DocumentRecordings { id ->
                    if (id != docWithAudio) emptyList() else listOf(
                        ExportedRecording("2026-11-21 09.00.m4a", 1_773_500_000_000L) { p, _ ->
                            File(p).writeBytes("partial".encodeToByteArray())
                            started.complete(Unit)
                            gate.await()
                        }
                    )
                }
            )
            val cancelledZip = File(dir, "cancelled.zip")
            val job = launch {
                export.export(export.resolve(ExportScope.Pages(docWithAudio)), ExportFormat.RECORDINGS, ExportOptions(), cancellingWriters, cancelledZip.absolutePath)
            }
            started.await()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(cancelledZip.exists())
            val leftoverTemps = File(PlatformFiles.resourcesDirectory()).listFiles()?.filter { it.name.startsWith("export_recording_") }.orEmpty()
            assertTrue(leftoverTemps.isEmpty(), "Leftover temps: $leftoverTemps")
        }
    }
}
