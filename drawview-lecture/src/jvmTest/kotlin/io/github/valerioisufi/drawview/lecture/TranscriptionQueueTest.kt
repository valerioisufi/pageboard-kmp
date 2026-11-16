package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.platform.ChosenFile
import io.github.valerioisufi.drawview.lecture.testing.FakeMicrophone
import io.github.valerioisufi.drawview.lecture.testing.FakeNotebook
import io.github.valerioisufi.drawview.lecture.testing.MemorySecrets
import io.github.valerioisufi.drawview.lecture.transcription.SpeechHints
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import io.github.valerioisufi.drawview.lecture.transcription.Transcription
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The transcriptions of whole recordings wait their turn instead of being refused
 * (docs/plans/transcription-queue.md): an import is converted at once and transcribed after the ones asked
 * before it, in their order, and what waits can be taken out.
 */
@OptIn(DelicateCoroutinesApi::class)
class TranscriptionQueueTest {
    private val directory: File = Files.createTempDirectory("transcription-queue").toFile()
    private val stateContext = newSingleThreadContext("lecture-state")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
        stateContext.close()
    }

    private val engine = MockEngine { request ->
        if (!request.url.encodedPath.endsWith(":generateContent")) return@MockEngine respond("", HttpStatusCode.NotFound)
        respond(geminiAnswer("""{"notesMarkdown":"# Teorema ponte","missing":[]}"""), HttpStatusCode.OK, jsonHeaders)
    }

    /** The transcribers made so far, in the order their transcriptions started: each one waits to be let go. */
    private val made = AtomicInteger()
    private val begun = Collections.synchronizedList(mutableListOf<Int>())
    private val closed = Collections.synchronizedList(mutableListOf<Int>())
    private val gates = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    private val steps = ConcurrentHashMap<Int, Channel<Unit>>()

    /** Lets every piece of the transcriber [index] go, from now on. */
    private fun gate(index: Int) = gates.getOrPut(index) { CompletableDeferred() }

    /** Lets one piece of the transcriber [index] go for every unit sent. */
    private fun step(index: Int) = steps.getOrPut(index) { Channel(Channel.UNLIMITED) }

    /** Pieces of a second or so, so that a short recording has several. */
    private var shortPieces = false

    /** The microphone of the next recording; `null`: none can be opened. */
    private var microphone: FakeMicrophone? = FakeMicrophone()

    private inner class HeldTranscriber(private val index: Int) : Transcriber {
        override val pieceSeconds: Int get() = if (shortPieces) 1 else 60

        override suspend fun transcribe(wav: ByteArray, hints: SpeechHints): Transcription {
            begun.add(index)
            if (!gate(index).isCompleted) {
                select {
                    gate(index).onAwait {}
                    step(index).onReceive {}
                }
            }
            return Transcription(listOf(TranscriptSegment(0, 500, "Transcribed by $index")))
        }

        override suspend fun close() {
            closed.add(index)
        }
    }

    private val assistant = LectureAssistant(
        settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()).apply {
            update(
                settings.copy(apiKey = "test-key")
                    .withTask(LectureTask.TRANSCRIPTION, TaskModel(provider = ModelProvider.LOCAL))
                    .withTask(LectureTask.FINAL_REVIEW, TaskModel(gemini = "test-model"))
            )
        },
        client = GeminiClient(HttpClient(engine)),
        captureFactory = { microphone },
        audioOutput = null,
        library = LectureLibrary(File(directory, "lectures").absolutePath),
        stateContext = stateContext,
        whisper = { HeldTranscriber(made.getAndIncrement()) },
        canGenerate = true
    )

    /** A WAV file of [seconds] of words: bursts of noise with pauses between them. */
    private fun recording(name: String, seconds: Int = 3): File {
        val rate = 16_000
        val frames = rate * seconds
        val random = Random(name.hashCode())
        val data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            val loud = (f / (rate / 5)) % 3 != 2
            data.putShort((random.nextInt(-12_000, 12_000) * if (loud) 1.0 else 0.01).toInt().toShort())
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(data.capacity())
        }
        return File(directory, name).apply { writeBytes(header.array() + data.array()) }
    }

    /** Imports a recording named [name] in the document [documentId]: its lecture. */
    private suspend fun import(name: String, documentId: String = "doc", seconds: Int = 3): Lecture = withContext(stateContext) {
        val file = recording("$name.wav", seconds)
        assertTrue(assistant.importRecording(documentId, name, FakeNotebook(), { emptyList() }, ChosenFile(file.absolutePath, file.name, lastModified = null)))
        assistant.transcribing.last()
    }

    private suspend fun <T> onState(block: () -> T): T = withContext(stateContext) { block() }

    /**
     * Waits for [condition], read on the thread of the state: longer than a review tried again
     * takes (`Lecture.MAX_ATTEMPTS_WHEN_STOPPED`, some seconds apart).
     */
    private suspend fun await(what: String, condition: () -> Boolean) {
        try {
            withTimeout(60_000) {
                while (!withContext(stateContext) { condition() }) delay(10)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val state = withContext(stateContext) {
                "transcribing ${assistant.transcribing.map { "${it.documentTitle}: ${it.status} ${it.importStep}" }}, " +
                    "recording ${assistant.recorder?.lecture?.status}, made ${made.get()}, begun $begun, closed $closed"
            }
            throw AssertionError("Timed out waiting for $what ($state)", e)
        }
    }

    private fun audioOf(lecture: Lecture) = File(lecture.directory, "audio.opus")

    @Test
    fun twoImportsAreTranscribedOneAfterTheOther() = runBlocking<Unit> {
        val first = import("first")
        val second = import("second")
        await("the first transcription to start") { begun.size == 1 }
        await("the second import to be converted") { second.importStep == ImportStep.WAITING }
        // Converted at once: its audio is in the notebook while it waits
        assertTrue(audioOf(second).exists())
        assertEquals(LectureStatus.IMPORTING, onState { second.status })
        assertEquals(listOf(first, second), onState { assistant.transcribing })
        assertEquals(first, onState { assistant.importing })
        assertTrue(onState { assistant.isBusy })
        assertEquals(ImportStep.TRANSCRIBING, onState { first.importStep })
        // One model at a time: the second one is not even made
        delay(200)
        assertEquals(1, made.get())

        gate(0).complete(Unit)
        await("the first lecture to be over") { first.status == LectureStatus.FINISHED }
        assertEquals(listOf("Transcribed by 0"), onState { first.transcript.map { it.text } })
        await("the second transcription to start") { begun.size == 2 }
        assertEquals(listOf(second), onState { assistant.transcribing })
        gate(1).complete(Unit)
        await("the second lecture to be over") { second.status == LectureStatus.FINISHED && assistant.transcribing.isEmpty() }
        assertEquals(listOf("Transcribed by 1"), onState { second.transcript.map { it.text } })
        assertEquals(listOf(0, 1), closed.toList())
        assertFalse(onState { assistant.isBusy })
        assertNull(onState { assistant.importing })
    }

    @Test
    fun theOrderAskedIsKeptAcrossImportsAndNewTranscriptions() = runBlocking<Unit> {
        val first = import("first")
        gate(0).complete(Unit)
        await("the first lecture to be over") { first.status == LectureStatus.FINISHED && assistant.transcribing.isEmpty() }

        // Transcribed again, then two imports while it runs
        assertTrue(onState { assistant.retranscribe(first) })
        await("the new transcription to start") { begun.size == 2 }
        assertFalse(onState { assistant.canRetranscribe(first) }, "already in the queue")
        val second = import("second")
        val third = import("third", documentId = "other")
        await("both imports to be converted") { second.importStep == ImportStep.WAITING && third.importStep == ImportStep.WAITING }
        assertEquals(listOf(first, second, third), onState { assistant.transcribing })

        gate(1).complete(Unit)
        await("the second import to start") { begun.size == 3 }
        assertEquals(listOf(second, third), onState { assistant.transcribing })
        assertEquals(ImportStep.WAITING, onState { third.importStep })
        gate(2).complete(Unit)
        gate(3).complete(Unit)
        await("everything to be over") { assistant.transcribing.isEmpty() }
        assertEquals(listOf("Transcribed by 1"), onState { first.transcript.map { it.text } })
        assertEquals(listOf("Transcribed by 2"), onState { second.transcript.map { it.text } })
        assertEquals(listOf("Transcribed by 3"), onState { third.transcript.map { it.text } })
    }

    @Test
    fun aFileThatIsNotAudioLeavesTheQueueAsItWas() = runBlocking<Unit> {
        val first = import("first")
        await("the first transcription to start") { begun.size == 1 }
        val notAudio = File(directory, "notes.wav").apply { writeText("not a recording") }
        val refused = onState {
            assertTrue(assistant.importRecording("doc", "notes", FakeNotebook(), { emptyList() }, ChosenFile(notAudio.absolutePath, notAudio.name, lastModified = null)))
            assistant.transcribing.last()
        }
        await("the import to fail") { assistant.importError != null }
        await("the failed import to leave") { assistant.transcribing == listOf(first) }
        assertFalse(File(refused.directory).exists(), "no lecture left of it")
        assertEquals(ImportStep.TRANSCRIBING, onState { first.importStep })
        gate(0).complete(Unit)
        await("the first lecture to be over") { first.status == LectureStatus.FINISHED }
        assertEquals(1, made.get())
    }

    @Test
    fun aLectureIsTakenOutOfTheQueueWaitingOrRunning() = runBlocking<Unit> {
        val first = import("first")
        val second = import("second")
        val third = import("third")
        await("the first transcription to start") { begun.size == 1 }
        await("the others to wait") { second.importStep == ImportStep.WAITING && third.importStep == ImportStep.WAITING }

        // Waiting: it never starts, and stays a lecture with its audio
        onState { assistant.cancelTranscription(second) }
        await("the second lecture to leave the queue") { assistant.transcribing == listOf(first, third) && second.status == LectureStatus.FINISHED }
        assertNull(onState { second.importStep })
        assertTrue(audioOf(second).exists())
        assertTrue(onState { second.transcript.isEmpty() })

        // Running: the next one takes its turn
        onState { assistant.cancelTranscription(first) }
        await("the third transcription to start") { begun.size == 2 }
        assertEquals(LectureStatus.FINISHED, onState { first.status })
        assertEquals(listOf(third), onState { assistant.transcribing })
        gate(1).complete(Unit)
        await("the third lecture to be over") { third.status == LectureStatus.FINISHED && assistant.transcribing.isEmpty() }
        assertEquals(2, made.get(), "the one taken out while waiting was never transcribed")
        // Saved as finished: found again as it is
        assertEquals(LectureStatus.FINISHED, assistant.library.load(second.directory)?.status)
    }

    @Test
    fun theOtherDevicesAreToldWhenATranscriptionStartsNotWhileItWaits() = runBlocking<Unit> {
        val first = import("first")
        val second = import("second")
        await("the first transcription to start") { begun.size == 1 }
        await("the second import to wait") { second.importStep == ImportStep.WAITING }
        // A lecture with its audio and no transcript, as one received from a tablet
        onState { assistant.cancelTranscription(second) }
        await("the second lecture to be finished") { second.status == LectureStatus.FINISHED && assistant.transcribing == listOf(first) }

        assertTrue(onState { assistant.generate(second, ModelProvider.GEMINI) })
        assertFalse(onState { assistant.generate(second, ModelProvider.GEMINI) }, "asked twice")
        assertEquals(ImportStep.WAITING, onState { second.importStep })
        assertEquals(listOf(first, second), onState { assistant.transcribing })
        delay(200)
        assertNull(onState { second.generation }, "not held while it waits")

        gate(0).complete(Unit)
        await("the second transcription to start") { begun.size == 2 }
        await("the lecture to be held by this device") { second.generation?.device == assistant.library.deviceId }
        gate(1).complete(Unit)
        await("the second lecture to be over") { second.status == LectureStatus.FINISHED && assistant.transcribing.isEmpty() }
        assertEquals(listOf("Transcribed by 1"), onState { second.transcript.map { it.text } })
        await("the lecture to be released") { second.generation == null }
    }

    @Test
    fun aRecordingPausesTheTranscriptionWhichGoesOnAfterIt() = runBlocking<Unit> {
        shortPieces = true
        val imported = import("imported", seconds = 8)
        await("the first piece to be in flight") { begun.size == 1 }

        // A recording starts in the middle of the piece
        assertTrue(onState { assistant.canRecord })
        assertTrue(onState { assistant.start("doc", "Live", FakeNotebook()) })
        assertFalse(onState { assistant.canRecord })
        assertFalse(onState { assistant.start("doc", "Another", FakeNotebook()) }, "one recording at a time")
        // The piece in flight is finished and kept, then the model is freed and it waits
        step(0).send(Unit)
        await("the transcription to pause") { imported.importStep == ImportStep.WAITING }
        assertEquals(1, onState { imported.transcript.size })
        assertEquals(listOf(0), closed.toList())
        assertEquals(listOf(imported), onState { assistant.transcribing })
        delay(300)
        assertEquals(1, begun.size, "no piece transcribed while the recording goes on")

        // The recording over, it goes on from the next piece
        onState { assistant.recorder!!.stop() }
        await("the recording to be over") { assistant.canRecord }
        await("the transcription to go on") { begun.size == 2 }
        assertEquals(ImportStep.TRANSCRIBING, onState { imported.importStep })
        gate(0).complete(Unit)
        await("the import to be over") { imported.status == LectureStatus.FINISHED && assistant.transcribing.isEmpty() }
        val pieces = begun.count { it == 0 }
        assertTrue(pieces >= 3, "$pieces pieces")
        assertEquals(pieces, onState { imported.transcript.size }, "a segment for every piece: none lost, none twice")
        assertEquals(onState { imported.transcript.map { it.startMs } }.sorted(), onState { imported.transcript.map { it.startMs } }, "in their order")
    }

    @Test
    fun anImportDuringARecordingWaitsForItsEnd() = runBlocking<Unit> {
        assertTrue(onState { assistant.start("doc", "Live", FakeNotebook()) })
        val recorded = onState { assistant.recorder!!.lecture }
        val imported = import("imported")
        await("the import to be converted") { imported.importStep == ImportStep.WAITING }
        assertTrue(audioOf(imported).exists())
        delay(300)
        assertTrue(begun.isEmpty(), "nothing transcribed while the recording goes on")
        assertEquals(LectureStatus.RECORDING, onState { recorded.status })

        onState { assistant.recorder!!.stop() }
        await("the import to start") { begun.isNotEmpty() }
        // The transcriber of the recording was made first: the one of the import is the last one
        assertEquals(listOf(made.get() - 1), begun.toList())
        gate(made.get() - 1).complete(Unit)
        await("the import to be over") { imported.status == LectureStatus.FINISHED && assistant.transcribing.isEmpty() }
        assertEquals(1, onState { imported.transcript.size })
    }

    @Test
    fun aRecordingThatCannotStartLeavesTheTranscriptionsGoing() = runBlocking<Unit> {
        shortPieces = true
        val imported = import("imported", seconds = 8)
        await("the first piece to be in flight") { begun.size == 1 }
        microphone = null
        assertFalse(onState { assistant.start("doc", "Live", FakeNotebook()) })
        assertTrue(onState { assistant.canRecord })
        gate(0).complete(Unit)
        await("the import to be over") { imported.status == LectureStatus.FINISHED && assistant.transcribing.isEmpty() }
        assertTrue(closed.count { it == 0 } == 1, "never paused: closed once, at its end")
    }
}
