package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.testing.FakeMicrophone
import io.github.valerioisufi.drawview.lecture.testing.FakeNotebook
import io.github.valerioisufi.drawview.lecture.testing.MemorySecrets
import io.github.valerioisufi.drawview.model.DocumentAttachments
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A lecture recorded on a tablet (without the models) and shared with a computer, which
 * transcribes and reviews it: what each one does reaches the other ([LectureAttachments]), as the
 * synchronization would carry it ([meet]).
 */
@OptIn(DelicateCoroutinesApi::class)
class LectureSharingTest {
    private val directory: File = Files.createTempDirectory("lecture-sharing").toFile()
    private val stateContext = newSingleThreadContext("lecture-state")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
        stateContext.close()
    }

    private val reviews = Collections.synchronizedList(mutableListOf<String>())
    private val engine = MockEngine { request ->
        if (!request.url.encodedPath.endsWith(":generateContent")) return@MockEngine respond("", HttpStatusCode.NotFound)
        reviews.add(request.body.toByteArray().decodeToString())
        val review = """{"notesMarkdown":"# Teorema ponte","missing":[
            {"kind":"THEOREM","title":"Teorema ponte","content":"Vale per ogni successione convergente","importance":3,"atSeconds":2},
            {"kind":"EXAM_HINT","title":"All'esame","content":"Dimostrare il teorema ponte","importance":3,"atSeconds":5}]}"""
        respond(geminiAnswer(review), HttpStatusCode.OK, jsonHeaders)
    }

    private val microphone = FakeMicrophone()

    private fun assistant(name: String, canGenerate: Boolean) = LectureAssistant(
        settingsStore = AssistantSettingsStore(File(directory, "$name-settings.json").absolutePath, MemorySecrets()).apply {
            update(
                settings.copy(apiKey = "test-key")
                    // Transcribed on this computer, reviewed online
                    .withTask(LectureTask.TRANSCRIPTION, TaskModel(provider = ModelProvider.LOCAL))
                    .withTask(LectureTask.FINAL_REVIEW, TaskModel(gemini = "test-model"))
            )
        },
        client = GeminiClient(HttpClient(engine)),
        captureFactory = { microphone },
        audioOutput = null,
        library = LectureLibrary(File(directory, name).absolutePath),
        stateContext = stateContext,
        whisper = { FakeTranscriber() },
        canGenerate = canGenerate
    )

    @Test
    fun aLectureRecordedOnATabletIsReviewedOnAComputerAndSharedBack() = runBlocking<Unit> {
        val tablet = assistant("tablet", canGenerate = false)
        val computer = assistant("computer", canGenerate = true)

        // Recorded on the tablet: seen on the computer while it is recorded, with its audio so far
        assertTrue(withContext(stateContext) { tablet.start("doc", "Analisi 1", FakeNotebook()) })
        val recorded = tablet.recorder!!.lecture
        microphone.speak(12.0, loud = true, random = Random(1))
        await { tablet.library.readRecord(recorded.directory)?.pages?.any { it.image != null } }
        meet(tablet.attachments, computer.attachments)
        val live = computer.library.list("doc").single()
        assertTrue(live.activeElsewhere)
        assertEquals(tablet.library.deviceName, live.recordedElsewhere)
        val streamed = File(live.directory, "audio.opus").readBytes()
        assertTrue(streamed.isNotEmpty())
        assertContentEquals(streamed, File(recorded.directory, "audio.opus").readBytes().copyOf(streamed.size))
        // Offered to be followed live (asked first: the default)
        val liveOffer = await { computer.generationOffers.firstOrNull() }
        assertTrue(liveOffer.live)
        // The notes of an hour, its transcript reviewed: never the audio
        assertEquals(130_000L + 20_000 + 3_600 * 4, liveOffer.estimatedTokens)
        withContext(stateContext) { computer.declineGeneration(liveOffer) }

        withContext(stateContext) { tablet.recorder!!.stop() }
        await { recorded.status == LectureStatus.FINISHED }
        meet(tablet.attachments, computer.attachments)
        val summary = computer.library.list("doc").single()
        assertFalse(summary.activeElsewhere)
        assertEquals(recorded.id, File(summary.directory).name)
        assertContentEquals(File(recorded.directory, "audio.opus").readBytes(), File(summary.directory, "audio.opus").readBytes())
        assertContentEquals(byteArrayOf(1, 2, 3), File(summary.directory, "pages/p11.jpg").readBytes())
        // Told: the lecture, its audio; offered to be transcribed and reviewed here, with its cost
        assertEquals(listOf(LectureArrival.Kind.LECTURE, LectureArrival.Kind.AUDIO), await { computer.arrivals.toList().takeIf { it.size == 2 } }.map { it.kind })
        val offer = await { computer.generationOffers.firstOrNull() }
        assertEquals(20_000L + 12 * 4, offer.estimatedTokens)
        withContext(stateContext) { computer.declineGeneration(offer) }

        // Transcribed and reviewed on the computer, with the pages seen on the tablet
        computer.refresh("doc")
        val lecture = await { computer.shownLecture("doc") }
        assertTrue(lecture.hasAudio)
        assertTrue(lecture.recordedElsewhere)
        assertTrue(withContext(stateContext) { computer.generate(lecture, ModelProvider.GEMINI) })
        await { lecture.status == LectureStatus.FINISHED && lecture.reviewStatus == ReviewStatus.DONE && computer.importing == null }
        assertEquals("Il teorema ponte vale per ogni successione", lecture.transcript.single().text)
        assertTrue(reviews.single().contains("Slide 1 of PDF 1:\\nLimiti"), reviews.single())

        // The tablet shows what the computer wrote
        meet(tablet.attachments, computer.attachments)
        assertEquals("# Teorema ponte", recorded.finalReview)
        assertEquals(lecture.transcript.toList(), recorded.transcript.toList())
        assertEquals(listOf("Teorema ponte", "All'esame"), recorded.suggestions.map { it.title })
        assertEquals(LectureArrival.Kind.REVIEW, await { tablet.arrivals.lastOrNull() }.kind)
        // Each device counts its tokens; none writes the lecture now
        assertEquals(1, lecture.usage.requests)
        assertEquals(1, recorded.otherUsage.values.single().usage.requests)
        assertEquals(1, recorded.totalUsage.requests)
        assertNull(recorded.generation)

        // A note inserted on the tablet, the other one dismissed on the computer
        withContext(stateContext) {
            recorded.updateSuggestion(recorded.suggestions[0].id, SuggestionStatus.INSERTED)
            lecture.updateSuggestion(lecture.suggestions[1].id, SuggestionStatus.DISMISSED)
        }
        await { tablet.library.readRecord(recorded.directory)?.suggestions?.get(0)?.status == SuggestionStatus.INSERTED }
        await { computer.library.readRecord(lecture.directory)?.suggestions?.get(1)?.status == SuggestionStatus.DISMISSED }
        meet(tablet.attachments, computer.attachments)
        for (shown in listOf(recorded, lecture)) {
            assertEquals(listOf(SuggestionStatus.INSERTED, SuggestionStatus.DISMISSED), shown.suggestions.map { it.status })
        }

        // The course context written on the computer
        withContext(stateContext) { computer.editContext("doc", "Analisi 1: limiti e successioni") }
        awaitFile(File(computer.library.documentDirectory("doc"), "context.json"))
        meet(tablet.attachments, computer.attachments)
        assertEquals("Analisi 1: limiti e successioni", withContext(stateContext) { tablet.contextOf("doc")?.text })

        // Deleted on the computer: on the tablet too
        withContext(stateContext) { computer.delete("doc", summary) }
        await { !File(summary.directory).exists() }
        meet(tablet.attachments, computer.attachments)
        assertFalse(File(recorded.directory).exists())
        assertNull(withContext(stateContext) { tablet.shownLecture("doc") })
        assertTrue(tablet.library.list("doc").isEmpty())
    }

    @Test
    fun aLectureRecordedOnATabletIsFollowedLiveOnAComputer() = runBlocking<Unit> {
        val tablet = assistant("tablet", canGenerate = false)
        val computer = assistant("computer", canGenerate = true)
        withContext(stateContext) { computer.settingsStore.update(computer.settingsStore.settings.copy(receivedLectures = ReceivedLectures.ALWAYS)) }

        // Recorded on the tablet, its audio reaching the computer as it is recorded
        assertTrue(withContext(stateContext) { tablet.start("doc", "Analisi 1", FakeNotebook()) })
        val recorded = tablet.recorder!!.lecture
        // A minute of speech, a pause (where the chunk is cut), then more
        microphone.speak(62.0, loud = true, random = Random(3))
        microphone.speak(2.0, loud = false, random = Random(3))
        microphone.speak(10.0, loud = true, random = Random(4))
        await { recorded.elapsedMs >= 73_000 }
        await { tablet.attachments.versions("doc").any { (key, version) -> key.endsWith("/audio") && version.removePrefix("a").toLong() >= File(recorded.directory, "audio.opus").length() - 4_000 } }
        meet(tablet.attachments, computer.attachments)

        // Followed on the computer: transcribed there while the tablet records, and shown on the tablet
        val followed = await { computer.shownLecture("doc")?.takeIf { it.followedHere } }
        await { computer.library.readRecord(followed.directory)?.transcript?.isNotEmpty() }
        assertEquals(LectureStatus.RECORDING, followed.status)
        meet(tablet.attachments, computer.attachments)
        assertEquals(LectureStatus.RECORDING, recorded.status)
        await { recorded.transcript.isNotEmpty() }
        assertEquals(computer.library.deviceName, recorded.generatingElsewhere(System.currentTimeMillis())?.name)

        // Over on the tablet: the rest of its audio followed, then the review written on the computer
        withContext(stateContext) { tablet.recorder!!.stop() }
        await { recorded.status == LectureStatus.FINISHED }
        meet(tablet.attachments, computer.attachments)
        await { followed.finalReview != null && !followed.followedHere }
        assertEquals("# Teorema ponte", followed.finalReview)
        assertEquals(LectureStatus.FINISHED, followed.status)
        meet(tablet.attachments, computer.attachments)
        assertEquals("# Teorema ponte", recorded.finalReview)
        assertNull(recorded.generation)
        assertContentEquals(File(recorded.directory, "audio.opus").readBytes(), File(followed.directory, "audio.opus").readBytes())
    }

    @Test
    fun theAudioIsSharedAsTheDevicesChoose() = runBlocking<Unit> {
        val tablet = assistant("tablet", canGenerate = false)
        val computer = assistant("computer", canGenerate = true)
        // Not shared: it stays on the tablet
        withContext(stateContext) { tablet.changeSharing("doc") { it.copy(shareAudio = false) } }
        val kept = record(tablet, seconds = 3.0)
        meet(tablet.attachments, computer.attachments)
        val summary = computer.library.list("doc").single()
        assertFalse(File(summary.directory, "audio.opus").exists())

        // Shared, received on request only
        withContext(stateContext) {
            tablet.changeSharing("doc") { it.copy(shareAudio = true) }
            computer.changeSharing("doc") { it.copy(downloadAudio = AudioDownload.ON_REQUEST) }
        }
        val asked = record(tablet, seconds = 3.0)
        meet(tablet.attachments, computer.attachments)
        val directory = computer.library.lectureDirectory("doc", asked.id)
        assertFalse(File(directory, "audio.opus").exists())
        val askedSummary = computer.library.list("doc").single { it.directory == directory }
        withContext(stateContext) { computer.show("doc", askedSummary) }
        val lecture = await { computer.shownLecture("doc")?.takeIf { it.id == asked.id } }
        withContext(stateContext) { computer.requestAudio(lecture) }
        // Written with the request: the file is already there, from the change of the sharing above
        await { File(computer.library.documentDirectory("doc"), "sharing.json").readText().contains(asked.id) }
        meet(tablet.attachments, computer.attachments)
        assertContentEquals(File(asked.directory, "audio.opus").readBytes(), File(directory, "audio.opus").readBytes())
        // The first one, shared afterwards from the tablet
        assertFalse(File(computer.library.lectureDirectory("doc", kept.id), "audio.opus").exists())
    }

    @Test
    fun aLectureDeletedElsewhereWhileRecordedIsDeletedOnceOver() = runBlocking<Unit> {
        val tablet = assistant("tablet", canGenerate = false)
        val computer = assistant("computer", canGenerate = true)
        assertTrue(withContext(stateContext) { tablet.start("doc", "Analisi 1", FakeNotebook()) })
        val recorded = tablet.recorder!!.lecture
        microphone.speak(3.0, loud = true, random = Random(2))
        await { File(recorded.directory, "lecture.json").exists() }
        meet(tablet.attachments, computer.attachments)
        // Deleted on the computer (not knowing it is being recorded)
        assertTrue(computer.library.delete(computer.library.lectureDirectory("doc", recorded.id)))
        meet(tablet.attachments, computer.attachments)
        assertTrue(recorded.deletedElsewhere)
        assertTrue(File(recorded.directory).exists(), "kept while recorded")
        withContext(stateContext) { tablet.recorder!!.stop() }
        assertTrue(await { !File(recorded.directory).exists() })
    }

    @Test
    fun theLecturesOfADocumentDeletedGoWithIt() = runBlocking<Unit> {
        val tablet = assistant("tablet", canGenerate = false)
        record(tablet, seconds = 2.0)
        val folder = File(tablet.library.documentDirectory("doc"))
        assertTrue(folder.exists())
        tablet.forgetDocuments(setOf("doc"))
        assertTrue(await { !folder.exists() })
    }

    /** Records a lecture of [seconds] on [device] (without the models): the lecture, finished. */
    private suspend fun record(device: LectureAssistant, seconds: Double): Lecture {
        assertTrue(withContext(stateContext) { device.start("doc", "Analisi 1", FakeNotebook()) })
        val lecture = device.recorder!!.lecture
        microphone.speak(seconds, loud = true, random = Random(seconds.toInt()))
        withContext(stateContext) { device.recorder!!.stop() }
        await { lecture.status == LectureStatus.FINISHED && File(lecture.directory, "audio.opus").exists() }
        return lecture
    }

    /**
     * Two devices meet: each item one of them has differently is sent to the other one when it
     * wants it, until they have the same ones (as the synchronization does).
     */
    private suspend fun meet(first: DocumentAttachments, second: DocumentAttachments) {
        repeat(10) {
            if (send(first, second) + send(second, first) == 0) return
        }
        fail("The devices keep sending items to each other")
    }

    private suspend fun send(from: DocumentAttachments, to: DocumentAttachments): Int {
        var sent = 0
        val known = to.versions("doc")
        for ((key, version) in from.versions("doc")) {
            if (known[key] == version || !to.wants("doc", key, version)) continue
            val source = assertNotNull(from.open("doc", key), key)
            // As the synchronization does: from what the other device has already
            val start = to.received("doc", key, source.version)
            val receiver = to.receive("doc", key, source.version, source.size, start) ?: continue
            var offset = start
            while (offset < source.size) {
                val bytes = assertNotNull(source.read(offset, PART_SIZE))
                receiver.write(bytes)
                offset += bytes.size
            }
            source.close()
            receiver.complete()
            sent++
        }
        return sent
    }

    private suspend fun awaitFile(file: File) = await { file.exists() }

    /** Waits for [value] (read on the thread of the state) to be there (or true). */
    private suspend fun <T : Any> await(value: suspend () -> T?): T = withTimeout(20_000) {
        var found: T? = null
        while (found == null) {
            found = withContext(stateContext) { value() }?.takeIf { it != false }
            if (found == null) delay(20)
        }
        found
    }

    private companion object {
        const val PART_SIZE = 64 * 1024
    }
}
