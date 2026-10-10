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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.takahirom.roborazzi.captureRoboImage
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.lecture.AssistantSettingsStore
import io.github.valerioisufi.drawview.lecture.CourseContext
import io.github.valerioisufi.drawview.lecture.DeviceUsage
import io.github.valerioisufi.drawview.lecture.FakeMicrophone
import io.github.valerioisufi.drawview.lecture.FakeNotebook
import io.github.valerioisufi.drawview.lecture.Lecture
import io.github.valerioisufi.drawview.lecture.LectureAssistant
import io.github.valerioisufi.drawview.lecture.LectureLibrary
import io.github.valerioisufi.drawview.lecture.LectureRecord
import io.github.valerioisufi.drawview.lecture.MemorySecrets
import io.github.valerioisufi.drawview.lecture.NoteKind
import io.github.valerioisufi.drawview.lecture.NoteSuggestion
import io.github.valerioisufi.drawview.lecture.SuggestionStatus
import io.github.valerioisufi.drawview.lecture.TokenUsage
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import io.github.valerioisufi.drawview.ui.theme.LocalCanvasColors
import java.io.File
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
        File(directory, "lecture.json").writeText(Lecture.recordJson.encodeToString(LectureRecord.serializer(), record()))
        library.saveContext(
            "doc",
            CourseContext("Calculus I · Prof. Rossi. Topics: limits, continuity, derivatives, integrals. Terms: epsilon-delta, one-sided limit, removable discontinuity.")
        )
        val assistant = assistant(library)
        val controller = DrawViewController(document = DrawDocument(), coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        runDesktopComposeUiTest(width = 428, height = 920) {
            setContent { Panel { PanelContent(assistant, "doc", controller, LecturePanelState()) } }
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
            setContent { Panel { PanelContent(assistant, "doc", controller, LecturePanelState()) } }
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
                            PanelCard {
                                Text("Converting the audio…", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        Column(modifier = Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LectureNotice(DrawViewIcons.Key, "Set the Gemini API key in the settings.", NoticeTone.Error, action = "Settings" to {})
                            LectureNotice(DrawViewIcons.Mic, "The system is using the microphone (a call, Siri): the recording goes on as soon as it gives it back.", NoticeTone.Warning)
                            LectureNotice(DrawViewIcons.Users, "Being recorded on Marta’s iPad · 12:30", NoticeTone.Info)
                            LectureNotice(DrawViewIcons.Sparkles, "gemma4:12b is reviewing the lecture on this computer: 2140 tokens written.", NoticeTone.Neutral)
                        }
                    }
                }
            }
        }
        waitForIdle()
        captureToImage().captureRoboImage("screenshots/lecture-recording-notices.png")
    }

    /** The settings in their sections: the requests and this computer. */
    @Test
    fun settings() {
        val store = AssistantSettingsStore(File(root, "settings.json").absolutePath, MemorySecrets())
        val assistant = assistant(LectureLibrary(File(root, "empty").absolutePath))
        runDesktopComposeUiTest(width = 1000, height = 900) {
            setContent {
                DrawViewTheme(darkTheme = false, dynamicColor = false) {
                    Box(modifier = Modifier.fillMaxSize().background(LocalCanvasColors.current.desk).padding(24.dp)) {
                        Column(
                            modifier = Modifier
                                .width(940.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            AssistantSettingsContent(store, assistant.localModels, onDismiss = {}, compact = false)
                        }
                    }
                }
            }
            waitForIdle()
            captureToImage().captureRoboImage("screenshots/lecture-settings.png")
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
