package io.github.valerioisufi.drawview.lecture.bench

import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.AssistantSettingsStore
import io.github.valerioisufi.drawview.lecture.Lecture
import io.github.valerioisufi.drawview.lecture.LectureRecorder
import io.github.valerioisufi.drawview.lecture.LectureStatus
import io.github.valerioisufi.drawview.lecture.NotebookSnapshot
import io.github.valerioisufi.drawview.lecture.NotebookSource
import io.github.valerioisufi.drawview.lecture.PageContent
import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.CaptureEvent
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import io.github.valerioisufi.drawview.lecture.model.ModelBackends
import io.github.valerioisufi.drawview.lecture.model.OllamaBackend
import io.github.valerioisufi.drawview.lecture.testing.MemorySecrets
import io.github.valerioisufi.drawview.lecture.transcription.Transcribers
import io.github.valerioisufi.drawview.lecture.transcription.whisperEngine
import java.io.File
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * A lecture of the workspace of lecture-eval through the recorder itself ([LectureRecorder]), as
 * the desktop application follows it: its recording heard as from a microphone, transcribed by
 * whisper.cpp on this computer (`<bench>/whisper/ggml-large-v3-turbo-q5_0.bin`), the notes asked
 * of Gemini 3.8 Flash (`GEMINI_API_KEY`) every [AssistantSettings.notesSeconds] with the slides
 * first in an explicit cache, the notebook as the student had it without the assistant, no final
 * review. The audio is given a minute at a time, the next one once the transcription caught up.
 *
 * The notes go to `<bench>/answers/notes/recorder.json` (graded as the others of [NotesBench]),
 * the tokens and the cached tokens to the report. The lecture is written in
 * `<bench>/answers/recorder/`, nowhere else.
 */
internal class RecorderBench(
    private val bench: Bench,
    private val workspace: File,
    private val lectureId: String = "2026-10-15_09-48-14-2ce0b6"
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(report: StringBuilder) {
        report.appendLine("## A lecture through the recorder").appendLine()
        val recorded = BenchLecture.read(workspace, File(bench.material, "pencilnotes"), lectureId)
        val whisperModel = File(bench.directory, "whisper/ggml-large-v3-turbo-q5_0.bin")
        val apiKey = System.getenv("GEMINI_API_KEY").orEmpty()
        if (recorded == null || !whisperModel.isFile || whisperEngine() == null || apiKey.isEmpty()) {
            report.appendLine("Missing: the lecture $lectureId, whisper.cpp and its model, or GEMINI_API_KEY.").appendLine()
            return
        }
        val source = recorded.untouched()
        val recording = RecordingFile(File(bench.material, "pencilnotes/lectures/doc-${source.prepared.documentId}/$lectureId").path)
        val samples = recording.readSegment(0, source.prepared.durationMs) ?: ShortArray(0)

        val directory = bench.output("recorder/lecture").also { it.deleteRecursively() }
        val settingsStore = AssistantSettingsStore(File(directory.parentFile, "settings.json").path, MemorySecrets()).apply {
            update(settings.copy(apiKey = apiKey, spokenLanguage = "it", reviewAtTheEnd = false, whisperModel = whisperModel.path))
        }
        val stateContext = newSingleThreadContext("recorder-bench")
        val lecture = Lecture(
            documentId = source.prepared.documentId,
            directory = directory.path,
            record = null,
            settingsStore = settingsStore,
            backends = ModelBackends(GeminiBackend(GeminiClient()) { settingsStore.apiKey }, OllamaBackend(OllamaClient()) { settingsStore.settings.localUrl }),
            audioOutput = null,
            stateContext = stateContext,
            courseContext = { source.courseContext }
        ).apply { documentTitle = source.prepared.documentTitle }

        val microphone = FileMicrophone()
        var heardMs = 0L
        val notebook = TimedNotebook(source) { heardMs }
        val recorder = LectureRecorder(lecture, settingsStore, microphone, notebook, Transcribers().forUpdates(settingsStore.settings))
        val start = TimeSource.Monotonic.markNow()
        recorder.start()
        val block = LECTURE_SAMPLE_RATE / 10
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + block, samples.size)
            microphone.hear(samples.copyOfRange(offset, end))
            offset = end
            heardMs = offset * 1000L / LECTURE_SAMPLE_RATE
            // A minute heard: the next one once the transcription caught up
            if (offset % (LECTURE_SAMPLE_RATE * 60) < block) {
                withTimeout(10 * 60_000L) { while (withContext(stateContext) { lecture.pendingUpdates } > 0) delay(100) }
                println("recorder ${formatLectureTime(heardMs)}: ${lecture.transcript.size} segments, ${lecture.suggestions.size} notes, ${lecture.usage.requests} requests")
            }
        }
        recorder.stop()
        withTimeout(10 * 60_000L) { while (lecture.status != LectureStatus.FINISHED) delay(200) }
        val millis = start.elapsedNow().inWholeMilliseconds
        stateContext.close()

        val usage = lecture.usage
        val notes = lecture.suggestions.map { BenchNote(it.atMs, it.pageIndex?.plus(1), it.kind.name, it.title, it.content) }
        val run = NotesRun(
            "recorder", lectureId, settingsStore.settings.notesSeconds * 1000L, usage.requests, 0, millis,
            usage.promptTokens, usage.outputTokens, usage.promptByModality, usage.cachedTokens, lecture.outline, notes
        )
        bench.output("notes/recorder.json").writeText(Json { prettyPrint = true }.encodeToString(NotesRun.serializer(), run))
        val dollars = ((usage.promptTokens - usage.cachedTokens) * 0.75 + usage.cachedTokens * 0.075 + usage.outputTokens * 3.75) / 1_000_000.0
        report.appendLine("`$lectureId` (${formatLectureTime(source.prepared.durationMs)}), whisper.cpp large-v3-turbo q5 here, Gemini 3.8 Flash every ${settingsStore.settings.notesSeconds} s.").appendLine()
        report.appendLine("| Segments | Requests | Notes | Prompt tokens | Cached tokens | Output tokens | Time | Dollars |").appendLine("|---|---|---|---|---|---|---|---|")
        report.appendLine(
            "| ${lecture.transcript.size} | ${usage.requests} | ${notes.size} | ${usage.promptTokens} | ${usage.cachedTokens} | ${usage.outputTokens} | ${seconds(millis)} | ${"%.3f".format(dollars)} |"
        ).appendLine()
        lecture.problem?.let { report.appendLine("Problem: $it").appendLine() }
    }

    /** The recording heard as from a microphone, a block at a time ([hear]). */
    private class FileMicrophone : AudioCapture {
        @Volatile
        private var onSamples: ((ShortArray) -> Unit)? = null

        override fun start(onSamples: (ShortArray) -> Unit, onEvent: (CaptureEvent) -> Unit) {
            this.onSamples = onSamples
        }

        override fun stop() {
            onSamples = null
        }

        fun hear(samples: ShortArray) {
            onSamples?.invoke(samples)
        }
    }

    /** The notebook of [lecture] as the student had it at the time heard so far ([heardMs]), in text. */
    private class TimedNotebook(private val lecture: BenchLecture, private val heardMs: () -> Long) : NotebookSource {
        override val documentTitle: String = lecture.prepared.documentTitle

        override suspend fun strokeIds(): Set<String> = emptySet()

        override suspend fun capture(knownStrokeIds: Set<String>, textPages: Int, imagePages: Int): NotebookSnapshot =
            lecture.notebook(heardMs()) ?: NotebookSnapshot(documentTitle, 0, emptyList(), emptyList())

        override suspend fun allPages(): List<PageContent> = lecture.pagesAt(0)
    }
}
