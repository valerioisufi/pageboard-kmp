package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.AudioChunk
import io.github.valerioisufi.drawview.lecture.audio.SpeechChunker
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.audio.isSilent
import io.github.valerioisufi.drawview.lecture.audio.rms
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.local.OllamaException
import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
import io.github.valerioisufi.drawview.lecture.model.OllamaBackend
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.math.min

/**
 * Records a [Lecture] and follows it live.
 *
 * The microphone audio is written to disk ([io.github.valerioisufi.drawview.lecture.audio.RecordingFile])
 * and cut into chunks of about [AssistantSettings.chunkSeconds] ([SpeechChunker]). The chunks are
 * analyzed one at a time, in order: every request carries the memory of the lecture (outline,
 * suggested notes and their status, end of the transcript), what the student is looking at and
 * writing ([NotebookSource]), the slides and what was said, and returns the updated outline and
 * the missing notes ([ChunkAnalysis]). What was said is the audio, transcribed by Gemini in the
 * same request, or the transcript made on this computer ([Transcriber]) when the audio must not
 * leave it. Every kind of request is answered by the provider of its [LectureTask] (Gemini or a
 * local model). When the analysis falls behind (slow model or missing network) the waiting chunks
 * are merged into a single request. When the recording stops, the lecture is reviewed once more
 * ([Lecture.generateFinalReview]).
 *
 * The recording goes on when the document is closed: the notebook is then detached
 * ([detachNotebook]) and the analysis works on the audio and on the slides already seen, until
 * the document is opened again ([attachNotebook]).
 *
 * To limit the cost, the chunks without speech are not analyzed, the outline is rewritten every
 * few minutes only, and the notebook is sent as the areas written by the student.
 */
class LectureRecorder internal constructor(
    val lecture: Lecture,
    private val settingsStore: AssistantSettingsStore,
    private val capture: AudioCapture,
    notebook: NotebookSource?,
    private val transcriber: Transcriber? = null,
    private val onFinished: (LectureRecorder) -> Unit = {}
) {
    private val backends get() = lecture.backends

    @Volatile
    private var notebook: NotebookSource? = notebook

    private val audioBlocks = Channel<ShortArray>(Channel.UNLIMITED)
    private val updates = Channel<QueuedChunk>(Channel.UNLIMITED)
    private val cutRequested = atomic(false)
    private val catchUpRequested = atomic(false)
    private val recordingFileFinished = atomic(false)
    private var audioJob: Job? = null
    private var analysisJob: Job? = null

    // Owned by the analysis coroutine
    private var knownStrokeIds: Set<String>? = null
    private val recentChunks = ArrayDeque<AudioChunk>()
    private var lastOutlineMs = Long.MIN_VALUE
    private var lastSlides: List<UploadedSlides> = emptyList()

    private class QueuedChunk(val chunk: AudioChunk?, val catchUp: Boolean)

    val isRecording: Boolean get() = lecture.status == LectureStatus.RECORDING

    /** The editor of the document is open: the analysis sees its pages again. */
    fun attachNotebook(source: NotebookSource) {
        notebook = source
        lecture.isNotebookAttached = true
    }

    /** The editor of the document is closed ([source]: only if it is still the attached one). */
    fun detachNotebook(source: NotebookSource? = null) {
        if (source != null && notebook !== source) return
        notebook = null
        lecture.isNotebookAttached = false
    }

    /**
     * Opens the recording and starts the microphone.
     *
     * @throws io.github.valerioisufi.drawview.lecture.audio.AudioCaptureException when no microphone can be opened.
     */
    internal fun start() {
        lecture.recording.open()
        lecture.isNotebookAttached = notebook != null
        val chunker = SpeechChunker(targetSeconds = settingsStore.settings.chunkSeconds, overlapSeconds = OVERLAP_SECONDS)

        analysisJob = lecture.scope.launch {
            for (first in updates) {
                val group = mutableListOf(first)
                // Behind schedule: the waiting chunks are analyzed together
                while (group.sumOf { it.newDurationMs() } < MAX_MERGED_MS) {
                    group.add(updates.tryReceive().getOrNull() ?: break)
                }
                analyze(group)
            }
        }

        audioJob = lecture.scope.launch {
            var totalSamples = 0L
            var heardSomething = false
            var lastStateUpdate = 0L
            for (block in audioBlocks) {
                lecture.recording.append(block)
                totalSamples += block.size
                if (!heardSomething && block.any { it != 0.toShort() }) heardSomething = true
                val blockLevel = (rms(block) / LEVEL_FULL_SCALE).coerceIn(0f, 1f)

                val chunks = chunker.push(block).map { QueuedChunk(it, catchUp = false) }.toMutableList()
                if (cutRequested.getAndSet(false)) {
                    val catchUp = catchUpRequested.getAndSet(false)
                    val cut = chunker.cutNow(minSeconds = 0.5f)
                    if (cut != null || catchUp) chunks.add(QueuedChunk(cut, catchUp))
                }
                if (chunks.isNotEmpty()) {
                    lecture.setState { pendingUpdates += chunks.size }
                    chunks.forEach { updates.trySend(it) }
                }

                val recordedMs = samplesToMs(totalSamples)
                if (chunks.isNotEmpty() || recordedMs - lastStateUpdate >= STATE_UPDATE_MS) {
                    lastStateUpdate = recordedMs
                    lecture.setState {
                        elapsedMs = recordedMs
                        level = blockLevel
                        noAudioWarning = !heardSomething && recordedMs > SILENCE_WARNING_MS
                    }
                }
            }
            // Stopped: the last words
            chunker.cutNow(minSeconds = 2f)?.let {
                lecture.setState { pendingUpdates += 1 }
                updates.trySend(QueuedChunk(it, catchUp = false))
            }
            updates.close()
            lecture.setState {
                elapsedMs = samplesToMs(totalSamples)
                level = 0f
            }
            finishRecordingFile()
        }

        try {
            capture.start { samples -> audioBlocks.trySend(samples) }
        } catch (e: Throwable) {
            audioJob?.cancel()
            analysisJob?.cancel()
            throw e
        }
        lecture.scope.launch { lecture.persist() }
    }

    /** Analyzes the audio received so far right away, recapping the last minutes. */
    fun catchUp() {
        if (!isRecording) return
        catchUpRequested.value = true
        cutRequested.value = true
    }

    /** Stops the recording, analyzes the last chunks and reviews the whole lecture. */
    fun stop() {
        if (!isRecording) return
        lecture.status = LectureStatus.FINALIZING
        stopMicrophone()
        lecture.scope.launch {
            audioJob?.join()
            analysisJob?.join()
            lecture.setState { isAnalyzing = false }
            releaseLocalModels()
            lecture.runFinalReview(settingsStore.settings.reviewProvider)
            lecture.setState { status = LectureStatus.FINISHED }
            lecture.persist()
            onFinished(this@LectureRecorder)
        }
    }

    /**
     * Ends the recording right away (the application is exiting): the audio is saved, the waiting
     * chunks are dropped and the final review is left to be written later. Does not need the UI
     * thread, which may be the caller.
     */
    suspend fun finishNow() {
        if (lecture.status == LectureStatus.FINISHED) return
        analysisJob?.cancel()
        stopMicrophone()
        // The audio coroutine may be waiting for the UI thread: its tail is done here
        audioJob?.cancel()
        withTimeoutOrNull(2_000) { audioJob?.join() }
        finishRecordingFile()
        withTimeoutOrNull(2_000) { transcriber?.close() }
        lecture.status = LectureStatus.FINISHED
        lecture.level = 0f
        lecture.pendingUpdates = 0
        lecture.isAnalyzing = false
        lecture.persistNow()
        onFinished(this)
    }

    /** The local models of the live requests stay loaded during the lecture: freed at its end. */
    private suspend fun releaseLocalModels() {
        try {
            transcriber?.close()
        } catch (e: Throwable) {
            println("DrawView: cannot stop the transcription: ${e.message}")
        }
        val settings = settingsStore.settings
        listOf(LectureTask.LIVE_NOTES, LectureTask.CATCH_UP).map { settings.task(it) }
            .filter { it.provider == ModelProvider.LOCAL }
            .map { it.local }
            .distinct()
            .forEach { backends.local.unload(it) }
    }

    private fun stopMicrophone() {
        try {
            capture.stop()
        } catch (e: Throwable) {
            println("DrawView: cannot stop the microphone: ${e.message}")
        }
        audioBlocks.close()
    }

    /** Converts the raw audio into its WAV file, once (by the audio coroutine or [finishNow]). */
    private fun finishRecordingFile() {
        if (!recordingFileFinished.compareAndSet(expect = false, update = true)) return
        try {
            lecture.recording.finish()
        } catch (e: Throwable) {
            println("DrawView: cannot finish the recording ${lecture.directory}: ${e.message}")
        }
    }

    // ==========================================================
    // Live analysis
    // ==========================================================

    private fun QueuedChunk.newDurationMs(): Long = chunk?.let { it.endMs - it.newStartMs } ?: 0L

    private suspend fun analyze(group: List<QueuedChunk>) {
        val catchUp = group.any { it.catchUp }
        val chunks = group.mapNotNull { it.chunk }
        lecture.setState { isAnalyzing = true }
        try {
            if (chunks.isNotEmpty()) {
                if (chunks.all { isSilent(it.samples, it.overlapSamples) }) {
                    // Nothing said (a break, an exercise in silence): no request
                    lecture.setState { usage += TokenUsage(skippedUpdates = chunks.size) }
                } else {
                    analyzeChunks(chunks)
                }
                rememberRecent(chunks)
            }
            if (catchUp) recapLastMinutes()
        } finally {
            withContext(NonCancellable) {
                lecture.setState {
                    pendingUpdates = (pendingUpdates - group.size).coerceAtLeast(0)
                    isAnalyzing = false
                }
                lecture.persist()
            }
        }
    }

    private fun rememberRecent(chunks: List<AudioChunk>) {
        chunks.forEach { recentChunks.addLast(it) }
        val latestEnd = recentChunks.lastOrNull()?.endMs ?: 0L
        while (recentChunks.size > 1 && latestEnd - recentChunks.first().endMs > CATCH_UP_MS) recentChunks.removeFirst()
    }

    /** The live update of [chunks]: suggestions, outline and (with the audio) transcript. */
    private suspend fun analyzeChunks(chunks: List<AudioChunk>) {
        val settings = settingsStore.settings
        val speech: RequestSpeech = if (settings.sendsAudio) {
            audioOf(chunks, contextMs = null) ?: return
        } else {
            transcribe(chunks, settings) ?: return
        }
        var includeOutline = false
        val result = request(settings.task(LectureTask.LIVE_NOTES), settings) { notebook, slides ->
            val memory = lecture.memory(speech.endMs)
            includeOutline = memory.outline.isBlank() || speech.endMs - lastOutlineMs >= OUTLINE_INTERVAL_MS
            LecturePrompts.chunkPrompt(settings, memory, notebook, slides, speech, includeOutline = includeOutline)
        } ?: return
        val analysis = result.analysis
        if (includeOutline && analysis.outline.isNotBlank()) lastOutlineMs = speech.endMs
        val defaultPage = result.notebook?.visiblePageIndexes?.firstOrNull()
        lecture.setState {
            if (speech is RequestAudio && analysis.transcript.isNotBlank()) {
                transcript.add(TranscriptSegment(speech.newStartMs, speech.endMs, analysis.transcript.trim()))
            }
            if (analysis.outline.isNotBlank()) outline = analysis.outline.trim()
            if (analysis.currentTopic.isNotBlank()) currentTopic = analysis.currentTopic.trim()
            analysis.professorPage?.let { it - 1 }?.takeIf { it in 0 until lastPageCount }?.let { professorPageIndex = it }
            addNotes(analysis.notes, speech.startMs, speech.endMs, defaultPage, fromFinalReview = false)
            usage += result.usage
            problem = null
        }
    }

    /**
     * "I missed something": the notes of the last minutes ([CATCH_UP_MS]), from their audio (Gemini,
     * when the audio may leave the device) or from their transcript.
     */
    private suspend fun recapLastMinutes() {
        val settings = settingsStore.settings
        val task = settings.task(LectureTask.CATCH_UP)
        val speech: RequestSpeech = if (task.provider == ModelProvider.GEMINI && settings.sendsAudio) {
            audioOf(recentChunks.toList(), contextMs = 0L) ?: return
        } else {
            val latest = recentChunks.lastOrNull()?.endMs ?: lecture.elapsedMs
            val segments = lecture.setStateResult { transcript.filter { it.endMs > latest - CATCH_UP_MS } }
            if (segments.isEmpty()) return
            RequestTranscript(emptyList(), segments, segments.first().startMs, 0L, segments.last().endMs)
        }
        val result = request(task, settings) { notebook, slides ->
            LecturePrompts.chunkPrompt(
                settings, lecture.memory(speech.endMs), notebook, slides, speech,
                catchUp = true, includeOutline = false, includeTranscript = false
            )
        } ?: return
        val defaultPage = result.notebook?.visiblePageIndexes?.firstOrNull()
        lecture.setState {
            addNotes(result.analysis.notes, speech.startMs, speech.endMs, defaultPage, fromFinalReview = false)
            usage += result.usage
            problem = null
        }
    }

    /**
     * The transcript of [chunks] made on this computer, added to the lecture right away (before
     * the suggestions); the part repeating the previous chunk is dropped.
     */
    private suspend fun transcribe(chunks: List<AudioChunk>, settings: AssistantSettings): RequestTranscript? {
        val transcriber = transcriber ?: run {
            lecture.setState { problem = LectureProblem.TranscriptionUnavailable("") }
            return null
        }
        val audio = audioOf(chunks, contextMs = null) ?: return null
        val segments = try {
            transcriber.transcribe(audio.wav, settings.whisperLanguage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lecture.setState { problem = LectureProblem.TranscriptionUnavailable(e.message ?: e::class.simpleName.orEmpty()) }
            return null
        }.map { it.copy(startMs = it.startMs + audio.startMs, endMs = it.endMs + audio.startMs) }
            .filter { it.endMs > audio.newStartMs + OVERLAP_TOLERANCE_MS && it.text.isNotBlank() }
        if (segments.isNotEmpty()) {
            // One segment per update, as the transcripts of Gemini
            val merged = TranscriptSegment(segments.first().startMs, segments.last().endMs, segments.joinToString(" ") { it.text.trim() })
            lecture.setState { transcript.add(merged) }
        }
        return RequestTranscript(emptyList(), segments, audio.newStartMs, 0L, audio.endMs)
    }

    private class Answer(val analysis: ChunkAnalysis, val notebook: NotebookSnapshot?, val usage: TokenUsage)

    /**
     * Sends the prompt built by [build] (given the notebook and the slides of the provider) to the
     * provider of [task], repeating it after the temporary errors.
     */
    private suspend fun request(
        task: TaskModel,
        settings: AssistantSettings,
        build: suspend (NotebookSnapshot?, List<UploadedSlides>) -> ModelPrompt
    ): Answer? {
        var attempt = 0
        while (true) {
            val apiKey = settingsStore.apiKey
            if (task.provider == ModelProvider.GEMINI && apiKey.isEmpty()) {
                lecture.setState { problem = LectureProblem.MissingApiKey }
                return null
            }
            try {
                val snapshot = captureNotebook(settings)
                val slides = when {
                    // The local models receive no PDF: the visible slides as images
                    task.provider == ModelProvider.LOCAL || !settings.sendSlides -> emptyList()
                    snapshot != null -> lecture.uploadSlides(apiKey, snapshot.slides).also { lastSlides = it }
                    // Notebook closed: the slides seen so far
                    else -> lastSlides
                }
                val notebook = snapshot?.let { withUnreadableSlides(it, slides, visibleOnly = task.provider == ModelProvider.LOCAL) }
                val prompt = build(notebook, slides)
                val answer = when (task.provider) {
                    ModelProvider.GEMINI -> backends.gemini.generate(prompt, task.gemini)
                    ModelProvider.LOCAL -> backends.local.generate(prompt, task.local, OllamaBackend.LIVE_KEEP_LOADED_SECONDS) {}
                }
                val analysis = LectureAnalysis.parseChunk(answer.text)
                if (notebook != null) {
                    knownStrokeIds = knownStrokeIds.orEmpty() + notebook.strokeIds
                    lecture.rememberNotebook(notebook)
                }
                return Answer(analysis, notebook, answer.usage ?: TokenUsage(localRequests = 1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                attempt++
                val problem = Lecture.problemOf(e)
                if (problem is LectureProblem.Authentication || problem is LectureProblem.LocalModelUnavailable) {
                    lecture.setState { this.problem = problem }
                    return null
                }
                val retryable = when (e) {
                    is GeminiException -> e.isRetryable
                    is OllamaException -> e.statusCode >= 500
                    // An invalid answer (e.g. a truncated JSON): one more try
                    is IllegalArgumentException -> attempt < 2
                    // Network errors
                    else -> true
                }
                val maxAttempts = if (isRecording) MAX_ATTEMPTS else Lecture.MAX_ATTEMPTS_WHEN_STOPPED
                if (!retryable || attempt >= maxAttempts) {
                    lecture.setState { this.problem = problem }
                    return null
                }
                val waitSeconds = min(30, 2 shl attempt)
                lecture.setState { this.problem = LectureProblem.Retrying(e.message ?: e::class.simpleName.orEmpty(), waitSeconds) }
                delay(waitSeconds * 1000L)
            }
        }
    }

    /** What the student is looking at, or `null` when the document is closed. */
    private suspend fun captureNotebook(settings: AssistantSettings): NotebookSnapshot? {
        val source = notebook ?: return null
        return try {
            // The strokes of the document when the lecture starts are not "new writing"
            val known = knownStrokeIds ?: source.strokeIds().also { knownStrokeIds = it }
            source.capture(known, settings.neighborPages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("DrawView: cannot capture the notebook: ${e.message}")
            null
        }
    }

    /**
     * Slides whose PDF is not sent: the model sees the whole page instead ([visibleOnly]: the
     * visible pages only, for the smaller context of the local models).
     */
    private suspend fun withUnreadableSlides(snapshot: NotebookSnapshot, slides: List<UploadedSlides>, visibleOnly: Boolean): NotebookSnapshot {
        val sent = slides.mapTo(mutableSetOf()) { it.document.resourceId }
        val unreadable = snapshot.pages
            .filter { it.slide != null && it.slide.resourceId !in sent && (!visibleOnly || it.visible) }
            .map { it.index }
        if (unreadable.isEmpty()) return snapshot
        val source = notebook ?: return snapshot
        return snapshot.withPageImages(source.renderPages(unreadable).associateBy { it.pageIndex })
    }

    /**
     * The audio of [chunks] (consecutive). [contextMs]: leading audio already analyzed; `null` for
     * the overlap of the first chunk with the previous one.
     */
    private fun audioOf(chunks: List<AudioChunk>, contextMs: Long?): RequestAudio? {
        if (chunks.isEmpty()) return null
        // The most recent audio, within the budget of a request (inline data is limited)
        var budgetMs = MAX_REQUEST_AUDIO_MS
        val selected = ArrayDeque<AudioChunk>()
        for (chunk in chunks.asReversed()) {
            budgetMs -= chunk.endMs - chunk.newStartMs
            if (budgetMs < 0 && selected.isNotEmpty()) break
            selected.addFirst(chunk)
        }
        var end = selected.first().startSample
        val start = end
        val pieces = mutableListOf<ShortArray>()
        for (chunk in selected) {
            val skip = (end - chunk.startSample).coerceIn(0L, chunk.samples.size.toLong()).toInt()
            if (skip < chunk.samples.size) {
                pieces.add(chunk.samples.copyOfRange(skip, chunk.samples.size))
                end = chunk.endSample
            }
        }
        val samples = ShortArray(pieces.sumOf { it.size })
        var offset = 0
        for (piece in pieces) {
            piece.copyInto(samples, offset)
            offset += piece.size
        }
        return RequestAudio(
            wav = Wav.encode(samples),
            startMs = samplesToMs(start),
            contextMs = contextMs ?: samplesToMs(selected.first().newStartSample - start),
            endMs = samplesToMs(end)
        )
    }

    internal companion object {
        const val OVERLAP_SECONDS = 5

        /** New audio merged in a request when the analysis is behind (a chunk can exceed it). */
        const val MAX_MERGED_MS = 3 * 60_000L

        /** Audio of a request (inline data: the whole request must stay under 20 MB). */
        const val MAX_REQUEST_AUDIO_MS = 6 * 60_000L
        const val CATCH_UP_MS = 3 * 60_000L
        const val MAX_ATTEMPTS = 6
        const val SILENCE_WARNING_MS = 4_000L
        const val STATE_UPDATE_MS = 200L
        const val LEVEL_FULL_SCALE = 6_000f

        /** The outline is rewritten (output tokens) every few minutes only. */
        const val OUTLINE_INTERVAL_MS = 210_000L

        /** A segment of the local transcript ending this little after the overlap is repeated. */
        const val OVERLAP_TOLERANCE_MS = 300L
    }
}
