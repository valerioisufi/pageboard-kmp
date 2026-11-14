package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.lecture.AssistantSettingsStore
import io.github.valerioisufi.drawview.lecture.ImportStep
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureLibrary
import io.github.valerioisufi.drawview.lecture.LectureStatus
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.platform.ChosenFile
import io.github.valerioisufi.drawview.lecture.testing.FakeMicrophone
import io.github.valerioisufi.drawview.lecture.testing.FakeNotebook
import io.github.valerioisufi.drawview.lecture.testing.MemorySecrets
import io.github.valerioisufi.drawview.lecture.transcription.SpeechHints
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import io.github.valerioisufi.drawview.lecture.transcription.Transcription
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Locale
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * The panel while recordings are transcribed (docs/transcription-queue.md): an import is always
 * taken, a recording too unless one is going on, and what waits or is transcribed says so and can be
 * taken out of the queue.
 */
@OptIn(ExperimentalTestApi::class)
class LecturePanelQueueTest {
    private val root: File = Files.createTempDirectory("lecture-queue-panel").toFile()

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** Every transcription waits for this. */
    private val letGo = CompletableDeferred<Unit>()

    private val assistant = LectureAssistant(
        settingsStore = AssistantSettingsStore(File(root, "settings.json").absolutePath, MemorySecrets()).apply {
            // The review on request: no model is asked anything
            update(settings.copy(reviewAtTheEnd = false))
        },
        captureFactory = { FakeMicrophone() },
        audioOutput = null,
        library = LectureLibrary(File(root, "lectures").absolutePath),
        stateContext = Dispatchers.Unconfined,
        whisper = {
            object : Transcriber {
                override suspend fun transcribe(wav: ByteArray, hints: SpeechHints): Transcription {
                    letGo.await()
                    return Transcription(listOf(TranscriptSegment(0, 500, "Limits and continuity")))
                }
            }
        }
    )

    /** A WAV file of three seconds of words: bursts of noise with pauses between them. */
    private fun recording(name: String): ChosenFile {
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
        val file = File(root, name).apply { writeBytes(header.array() + data.array()) }
        return ChosenFile(file.absolutePath, file.name, lastModified = null)
    }

    @Test
    fun thePanelTakesImportsAndRecordingsWhileALectureIsTranscribed() {
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        var shown by mutableStateOf("doc")
        runDesktopComposeUiTest(width = 428, height = 800) {
            setContent {
                DrawViewTheme(darkTheme = false, dynamicColor = false) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        key(shown) { PanelContent(assistant, shown, EditorContext(controller), LecturePanelState()) }
                    }
                }
            }
            // A recording of another notebook is being transcribed
            assistant.importRecording("physics", "Physics", FakeNotebook(), { emptyList() }, recording("physics.wav"))
            val physics = assistant.transcribing.single()
            waitUntil(timeoutMillis = 20_000) { physics.importStep == ImportStep.TRANSCRIBING }
            waitForIdle()
            onNodeWithText("Record the lecture").assertIsEnabled()
            onNodeWithText("Import a recording").assertIsEnabled()
            onNodeWithText("1 other recording is in the queue of the transcriptions").assertExists()

            // One imported here waits its turn, and says so
            assistant.importRecording("doc", "Calculus", FakeNotebook(), { emptyList() }, recording("calculus.wav"))
            val calculus = assistant.transcribing.last()
            waitUntil(timeoutMillis = 20_000) { calculus.importStep == ImportStep.WAITING }
            waitForIdle()
            onNodeWithText("Waiting for its turn…").assertExists()
            onNodeWithText("Remove from the queue").performClick()
            waitUntil(timeoutMillis = 20_000) { calculus.status == LectureStatus.FINISHED && assistant.transcribing == listOf(physics) }
            waitForIdle()
            onNodeWithText("New recording").assertIsEnabled()
            onNodeWithText("Import").assertIsEnabled()

            // A recording going on in another notebook: no other recording, an import still
            check(assistant.start("chemistry", "Chemistry", FakeNotebook()))
            waitForIdle()
            onNodeWithText("New recording").assertIsNotEnabled()
            onNodeWithText("Import").assertIsEnabled()
            onNodeWithText("A lecture of «Chemistry» is being recorded: one recording at a time.").assertExists()

            // The one being transcribed is stopped from its panel
            shown = "physics"
            waitForIdle()
            onNodeWithText("Transcribing the recording…").assertExists()
            onNodeWithText("Stop").performClick()
            waitUntil(timeoutMillis = 20_000) { physics.status == LectureStatus.FINISHED && assistant.transcribing.isEmpty() }
            assertEquals(0, physics.transcript.size)
            letGo.complete(Unit)
            assistant.recorder?.stop()
            waitUntil(timeoutMillis = 20_000) { assistant.canRecord }
        }
    }
}
