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
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The transcriptions of whole recordings wait their turn instead of being refused
 * (docs/transcription-queue.md): an import is converted at once and transcribed after the ones asked
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

    private fun gate(index: Int) = gates.getOrPut(index) { CompletableDeferred() }

    private inner class HeldTranscriber(private val index: Int) : Transcriber {
        override suspend fun transcribe(wav: ByteArray, hints: SpeechHints): Transcription {
            begun.add(index)
            gate(index).await()
            return Transcription(listOf(TranscriptSegment(0, 1_000, "Transcribed by $index")))
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
        captureFactory = { FakeMicrophone() },
        audioOutput = null,
        library = LectureLibrary(File(directory, "lectures").absolutePath),
        stateContext = stateContext,
        whisper = { HeldTranscriber(made.getAndIncrement()) },
        canGenerate = true
    )

    /** A WAV file of three seconds of words: bursts of noise with pauses between them. */
    private fun recording(name: String): File {
        val rate = 16_000
        val frames = rate * 3
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
    private suspend fun import(name: String, documentId: String = "doc"): Lecture = withContext(stateContext) {
        val file = recording("$name.wav")
        assertTrue(assistant.importRecording(documentId, name, FakeNotebook(), { emptyList() }, ChosenFile(file.absolutePath, file.name, lastModified = null)))
        assistant.transcribing.last()
    }

    private suspend fun <T> onState(block: () -> T): T = withContext(stateContext) { block() }

    /** Waits for [condition], read on the thread of the state. */
    private suspend fun await(what: String, condition: () -> Boolean) {
        try {
            withTimeout(20_000) {
                while (!withContext(stateContext) { condition() }) delay(10)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $what", e)
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
}
