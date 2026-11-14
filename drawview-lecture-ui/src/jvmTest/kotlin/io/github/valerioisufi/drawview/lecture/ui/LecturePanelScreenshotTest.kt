package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.lecture.AssistantSettingsStore
import io.github.valerioisufi.drawview.lecture.CourseContext
import io.github.valerioisufi.drawview.lecture.DeviceModels
import io.github.valerioisufi.drawview.lecture.DeviceUsage
import io.github.valerioisufi.drawview.lecture.Lecture
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureLibrary
import io.github.valerioisufi.drawview.lecture.LectureRecord
import io.github.valerioisufi.drawview.lecture.LectureTask
import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.NoteKind
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.lecture.SuggestionStatus
import io.github.valerioisufi.drawview.lecture.TokenUsage
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.WhisperModel
import io.github.valerioisufi.drawview.lecture.testing.FakeMicrophone
import io.github.valerioisufi.drawview.lecture.testing.FakeNotebook
import io.github.valerioisufi.drawview.lecture.testing.MemorySecrets
import io.github.valerioisufi.drawview.lecture.writeLectureRecord
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import io.github.valerioisufi.drawview.ui.theme.LocalCanvasColors
import io.github.valerioisufi.drawview.ml.Embedder
import io.github.valerioisufi.drawview.ml.EmbedderOptions
import io.github.valerioisufi.drawview.ml.Generator
import io.github.valerioisufi.drawview.ml.GeneratorOptions
import io.github.valerioisufi.drawview.ml.InstalledModel
import io.github.valerioisufi.drawview.ml.InstalledModels
import io.github.valerioisufi.drawview.ml.LoadedModels
import io.github.valerioisufi.drawview.ml.ModelCatalog
import io.github.valerioisufi.drawview.ml.ModelDownloads
import io.github.valerioisufi.drawview.ml.ModelException
import io.github.valerioisufi.drawview.ml.ModelRuntime
import io.github.valerioisufi.drawview.ml.ModelSpec
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Screenshots of the panel of the lecture assistant beside the canvas (docs/ui-design.md): a
 * finished lecture in its four tabs, before recording, the notices and the recording card.
 *
 * `./gradlew :drawview-lecture:recordRoborazziJvm` records them in `screenshots/`.
 */
@OptIn(ExperimentalTestApi::class)
class LecturePanelScreenshotTest {
    private val root: File = Files.createTempDirectory("lecture-panel").toFile()

    @BeforeTest
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun assistant(library: LectureLibrary) = LectureAssistant(
        settingsStore = AssistantSettingsStore(File(root, "settings.json").absolutePath, MemorySecrets()),
        captureFactory = { FakeMicrophone() },
        audioOutput = null,
        library = library,
        stateContext = Dispatchers.Unconfined
    )

    /** A finished lecture: its notes (new and handled), its transcript, its outline and context, its review. */
    @Test
    fun finishedLecture() {
        val library = LectureLibrary(File(root, "lectures").absolutePath)
        val directory = library.newLectureDirectory("doc", STARTED_AT)
        File(directory).mkdirs()
        writeLectureRecord(directory, record())
        library.saveContext(
            "doc",
            CourseContext("Calculus I · Prof. Rossi. Topics: limits, continuity, derivatives, integrals. Terms: epsilon-delta, one-sided limit, removable discontinuity.")
        )
        val assistant = assistant(library)
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        runDesktopComposeUiTest(width = 428, height = 920) {
            setContent { Panel { PanelContent(assistant, "doc", EditorContext(controller), LecturePanelState()) } }
            assistant.onEditorShown("doc", FakeNotebook())
            waitUntil(timeoutMillis = 10_000) { assistant.shownLecture("doc") != null && assistant.lecturesOf("doc").isNotEmpty() }
            waitForIdle()
            onNodeWithText("Handled (2)").performClick()
            waitForIdle()
            captureToImage().captureRoboImage("screenshots/lecture-notes.png")
            for ((tab, name) in listOf("Transcript" to "transcript", "Outline" to "outline", "Review" to "review")) {
                onNodeWithText(tab).performClick()
                waitForIdle()
                captureToImage().captureRoboImage("screenshots/lecture-$name.png")
            }
        }
    }

    /** No lecture yet: what the assistant does and what is sent, record or import. */
    @Test
    fun idle() {
        val assistant = assistant(LectureLibrary(File(root, "empty").absolutePath))
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        runDesktopComposeUiTest(width = 428, height = 600) {
            setContent { Panel { PanelContent(assistant, "doc", EditorContext(controller), LecturePanelState()) } }
            waitForIdle()
            captureToImage().captureRoboImage("screenshots/lecture-idle.png")
        }
    }

    /** The recording card (and the one of a device recording only), the import and the notices in their tones. */
    @Test
    fun recordingAndNotices() = runDesktopComposeUiTest(width = 820, height = 720) {
        setContent {
            DrawViewTheme(darkTheme = false, dynamicColor = false) {
                Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk).padding(24.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(modifier = Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            RecordingCard("42:10", level = 0.6f, status = "Topic: one-sided limits", onStop = {})
                            RecordingCard("42:10", level = 0f, status = "Recording the audio and the pages you look at.", onStop = {}, compact = true)
                            ImportCard("Converting the audio…", duration = null, progress = -1f)
                            ImportCard("Transcribing the recording…", duration = "1:32:10", progress = 0.4f, remove = "Stop")
                            ImportCard("Waiting for its turn…", duration = "48:05", progress = 0f, remove = "Remove from the queue")
                        }
                        Column(modifier = Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LectureNotice(DrawViewIcons.Key, "Set the Gemini API key in the settings.", NoticeTone.Error, action = "Settings" to {})
                            LectureNotice(DrawViewIcons.Mic, "The system is using the microphone (a call, Siri): the recording goes on as soon as it gives it back.", NoticeTone.Warning)
                            LectureNotice(DrawViewIcons.Users, "Being recorded on Marta’s iPad · 12:30", NoticeTone.Info)
                            LectureNotice(DrawViewIcons.Upload, "2 other recordings are in the queue of the transcriptions", NoticeTone.Neutral)
                            LectureNotice(DrawViewIcons.Sparkles, "gemma4:12b is reviewing the lecture on this computer: 2140 tokens written.", NoticeTone.Neutral)
                        }
                    }
                }
            }
        }
        waitForIdle()
        captureToImage().captureRoboImage("screenshots/lecture-recording-notices.png")
    }

    /** The settings in their sections: the requests, and the lecture. */
    @Test
    fun settings() {
        val store = AssistantSettingsStore(File(root, "settings.json").absolutePath, MemorySecrets())
        val assistant = assistant(LectureLibrary(File(root, "empty").absolutePath))
        runDesktopComposeUiTest(width = 1000, height = 900) {
            setContent {
                DrawViewTheme(darkTheme = false, dynamicColor = false) {
                    Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk))
                    AssistantSettingsDialog(store, assistant.localModels, onDismiss = {})
                }
            }
            waitForIdle()
            captureToImage().captureRoboImage("screenshots/lecture-settings.png")
            // The lecture: how often the assistant listens and writes notes
            onNodeWithText("Lecture").performClick()
            waitForIdle()
            captureToImage().captureRoboImage("screenshots/lecture-settings-lecture.png")
        }
    }

    /**
     * The local requests on the models of this device: the engine chosen, the live notes on a model
     * downloaded, the review on one to download. The model downloaded is a sparse file of its size in
     * the directory of the test.
     */
    @Test
    fun settingsOnTheDevice() {
        val installed = InstalledModels(ModelCatalog.Default, File(root, "models").absolutePath)
        File(installed.pathOf(ModelCatalog.GEMMA_4_E4B)).apply { parentFile.mkdirs() }.let { file ->
            RandomAccessFile(file, "rw").use { it.setLength(ModelCatalog.GEMMA_4_E4B.sizeBytes) }
        }
        installed.refresh()
        val deviceModels = DeviceModels(installed, LoadedModels(NoRuntime), ModelDownloads(installed))
        val store = AssistantSettingsStore(File(root, "settings.json").absolutePath, MemorySecrets())
        val settings = store.settings
        store.update(
            settings
                .withTask(LectureTask.LIVE_NOTES, settings.task(LectureTask.LIVE_NOTES).copy(provider = ModelProvider.LOCAL, device = "gemma-4-e4b"))
                .withTask(LectureTask.FINAL_REVIEW, settings.task(LectureTask.FINAL_REVIEW).copy(provider = ModelProvider.LOCAL, device = "gemma-4-12b"))
        )
        val assistant = LectureAssistant(
            settingsStore = store,
            captureFactory = { FakeMicrophone() },
            audioOutput = null,
            library = LectureLibrary(File(root, "empty").absolutePath),
            stateContext = Dispatchers.Unconfined,
            deviceModels = deviceModels
        )
        runDesktopComposeUiTest(width = 1000, height = 1100) {
            setContent {
                DrawViewTheme(darkTheme = false, dynamicColor = false) {
                    Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk))
                    AssistantSettingsDialog(store, assistant.localModels, onDismiss = {}, deviceModels = assistant.deviceModels)
                }
            }
            waitForIdle()
            onNodeWithText("Gemma 4 E4B", substring = true).assertExists()
            captureToImage().captureRoboImage("screenshots/lecture-settings-device.png")
            // The engine of the local requests
            onNodeWithText("On this computer").performClick()
            waitForIdle()
            onNodeWithText("This device").assertExists()
            captureToImage().captureRoboImage("screenshots/lecture-settings-device-engine.png")
        }
    }

    /** A runtime that loads nothing: the settings never run a model. */
    private object NoRuntime : ModelRuntime {
        override fun supports(spec: ModelSpec): Boolean = false

        override suspend fun generator(model: InstalledModel, options: GeneratorOptions): Generator = throw ModelException("none")

        override suspend fun embedder(model: InstalledModel, options: EmbedderOptions): Embedder = throw ModelException("none")
    }

    /**
     * The speech model on a Mac with Apple Silicon: its encoder for the Neural Engine to download, then
     * downloaded (docs/transcription-queue.md). Elsewhere there is no such row, and nothing to see.
     *
     * The files of the model are made in the data directory of the tests only (`drawview.dataDir`), never
     * over one already there, and only those made here are deleted.
     */
    @Test
    fun settingsOnTheNeuralEngine() {
        val store = AssistantSettingsStore(File(root, "settings.json").absolutePath, MemorySecrets())
        val models = assistant(LectureLibrary(File(root, "empty").absolutePath)).localModels
        if (!models.usesNeuralEngine) return
        val dataDir = File(checkNotNull(System.getProperty("drawview.dataDir")) { "the tests run on their own data directory" }).canonicalFile
        val model = File(models.whisperPath(WhisperModel.LARGE_V3_TURBO_Q5)).canonicalFile
        val encoder = File(models.encoderPath(WhisperModel.LARGE_V3_TURBO_Q5)).canonicalFile
        check(model.toPath().startsWith(dataDir.toPath()) && encoder.toPath().startsWith(dataDir.toPath())) { "$model is not in $dataDir" }
        check(!model.exists() && !encoder.exists()) { "$model or its encoder is already there" }
        try {
            model.parentFile.mkdirs()
            model.writeBytes(ByteArray(1))
            runDesktopComposeUiTest(width = 1000, height = 900) {
                setContent {
                    DrawViewTheme(darkTheme = false, dynamicColor = false) {
                        Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk))
                        AssistantSettingsDialog(store, models, onDismiss = {})
                    }
                }
                models.refresh()
                waitUntil(timeoutMillis = 10_000) { WhisperModel.LARGE_V3_TURBO_Q5 in models.whisperModels }
                waitForIdle()
                onNodeWithText("Download for the Neural Engine (1.2 GB)").assertExists()
                captureToImage().captureRoboImage("screenshots/lecture-settings-neural-engine.png")
                encoder.mkdirs()
                models.refresh()
                waitUntil(timeoutMillis = 10_000) { WhisperModel.LARGE_V3_TURBO_Q5 in models.encoders }
                waitForIdle()
                onNodeWithText("Runs on the Neural Engine").assertExists()
                captureToImage().captureRoboImage("screenshots/lecture-settings-neural-engine-ready.png")
            }
        } finally {
            if (model.length() == 1L) model.delete()
            if (encoder.list()?.isEmpty() == true) encoder.delete()
        }
    }

    /** The panel beside the canvas: the low container, its header. */
    @Composable
    private fun Panel(content: @Composable () -> Unit) {
        DrawViewTheme(darkTheme = false, dynamicColor = false) {
            Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk).padding(24.dp)) {
                Column(
                    modifier = Modifier
                        .width(380.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(24.dp))
                ) {
                    Text(
                        "Lecture assistant",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp).height(64.dp).padding(top = 20.dp)
                    )
                    content()
                }
            }
        }
    }

    private fun record() = LectureRecord(
        id = "lecture",
        documentId = "doc",
        documentTitle = "Lecture 1 — Limits",
        startedAt = STARTED_AT,
        durationMs = 88 * 60_000L,
        outline = "- **Limits: the intuitive idea** (0:04)\n  - Why \"close\" needs a number (0:21)\n- **The epsilon-delta definition** (0:38)\n- **One-sided limits** (1:06)",
        currentTopic = "The epsilon-delta definition",
        transcript = listOf(
            TranscriptSegment(722_000, 750_000, "So far we said that f(x) gets close to L when x gets close to a. Today we make it precise."),
            TranscriptSegment(751_000, 780_000, "Close means: as close as we want. We call that distance epsilon."),
            TranscriptSegment(780_000, 815_000, "For every epsilon greater than zero there is a delta such that, if x is within delta of a, f(x) is within epsilon of L.")
        ),
        suggestions = listOf(
            NoteSuggestion(
                id = "1",
                kind = NoteKind.DEFINITION,
                title = "The epsilon-delta definition",
                content = "The lecturer made the limit precise at 12:40, but the page stops at the intuitive idea.",
                pageIndex = 0,
                importance = 3,
                atMs = 760_000
            ),
            NoteSuggestion(id = "2", kind = NoteKind.EXAMPLE, title = "Why the limit exists at x = 1", content = "You wrote the example but not why the limit exists although f(1) is not defined.", atMs = 1_865_000),
            NoteSuggestion(id = "3", kind = NoteKind.OTHER, title = "Limits of sequences", content = "", pageIndex = 0, atMs = 300_000, status = SuggestionStatus.INSERTED),
            NoteSuggestion(id = "4", kind = NoteKind.OTHER, title = "Notation for one-sided limits", content = "", atMs = 200_000, status = SuggestionStatus.ALREADY_NOTED)
        ),
        finalReview = "## 1. Limits\nThe limit describes f near a, not at a.\n\n## 2. One-sided limits\nFrom the left and from the right; the limit exists when they agree.",
        finalReviewBy = "Gemini 3.5 Pro",
        usageByDevice = mapOf(
            "this" to DeviceUsage("This device", TokenUsage(requests = 48, promptTokens = 1_200_000, cachedTokens = 840_000, outputTokens = 36_000, skippedUpdates = 6))
        )
    )

    private companion object {
        const val STARTED_AT = 1_789_388_520_000L
    }
}
