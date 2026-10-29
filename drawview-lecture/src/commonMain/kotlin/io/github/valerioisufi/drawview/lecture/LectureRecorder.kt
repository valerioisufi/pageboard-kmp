package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.AudioChunk
import io.github.valerioisufi.drawview.lecture.audio.CaptureEvent
import io.github.valerioisufi.drawview.lecture.audio.SpeechChunker
import io.github.valerioisufi.drawview.lecture.audio.isSilent
import io.github.valerioisufi.drawview.lecture.audio.rms
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.handwriting.HandwritingReader
import io.github.valerioisufi.drawview.lecture.local.OllamaException
import io.github.valerioisufi.drawview.lecture.model.GeminiPromptCache
import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
import io.github.valerioisufi.drawview.lecture.model.OllamaBackend
import io.github.valerioisufi.drawview.lecture.transcription.StreamingTranscriber
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * ([FinalReviewer]), or later on request.
 *
 * The recording goes on when the document is closed: the notebook is then detached
 * ([detachNotebook]) and the analysis works on the audio only, until the document is opened again
 * ([attachNotebook]).
 *
 * Where the lectures are not followed by models (![analyzes]: the phones and the tablets, for now)
 * only the audio and the pages seen are recorded (the notebook is captured now and then): the
 * notes and the review are written later, e.g. by a device with the models the lecture reaches.
 *
 * A lecture recorded on another device can be followed here live ([follows]): its audio, read as
 * it arrives ([io.github.valerioisufi.drawview.lecture.audio.FollowedRecording]), is analyzed the
 * same way, and the review written once it is over; the recording itself (its status, its length)
 * stays the one of the device recording it.
 *
 * To limit the cost, the chunks without speech are not analyzed, the outline is rewritten every
 * few minutes only, and the notebook is sent as text ([PageContent]) with the images of the
 * visible pages only: after every update a few blocks of handwriting are transcribed
 * ([HandwritingReader]), once for all the lectures of the notebook.
 */
public class LectureRecorder internal constructor(
    public val lecture: Lecture,
    private val settingsStore: AssistantSettingsStore,
    private val capture: AudioCapture,
    notebook: NotebookSource?,
    private val transcriber: Transcriber? = null,
    private val analyzes: Boolean = true,
    private val follows: Boolean = false,
    private val stream: StreamingTranscriber? = null,
    private val handwriting: HandwritingReader = HandwritingReader(lecture.directory.trimEnd('/').substringBeforeLast('/') + "/handwriting.json", lecture.backends) {
        lecture.onChanged(null)
    },
    private val onFinished: (LectureRecorder) -> Unit = {}
) {
    /** Following a lecture recorded elsewhere, until it is over ([stop]). */
    private val following = atomic(false)

    private val backends get() = lecture.backends

    /** What was said in every update: its audio, or its transcript. */
    private val speech: SpeechSource = when {
        stream != null -> StreamSpeech(lecture, stream, ::speechHints)
        transcriber != null -> ChunkSpeech(lecture, transcriber, ::speechHints)
        else -> AudioSpeech
    }

    @Volatile
    private var notebook: NotebookSource? = notebook

    private val audioBlocks = Channel<ShortArray>(Channel.UNLIMITED)
    private val updates = Channel<QueuedChunk>(Channel.UNLIMITED)
    private val cutRequested = atomic(false)
    private val catchUpRequested = atomic(false)
    private val recordingFileFinished = atomic(false)
    private var audioJob: Job? = null
    private var analysisJob: Job? = null

    /** The captures of the notebook of a recording not analyzed (see [analyzes]). */
    private var notebookJob: Job? = null

    /** Saves the lecture now and then while the system holds the microphone (no audio comes to do it). */
    private var interruptionJob: Job? = null

    // Owned by the analysis coroutine
    private var knownStrokeIds: Set<String>? = null
    private var initialStrokeIds: Set<String> = emptySet()
    private var lastNotebook: NotebookSnapshot? = null

    /** Text of the slides the student was looking at, for the prompt of the transcription. */
    private var visibleSlides: List<String> = emptyList()
    private val recentChunks = ArrayDeque<AudioChunk>()
    private var lastOutlineMs = Long.MIN_VALUE

    /** What opens the requests of Gemini, taken from the notebook at the first of them. */
    private var lectureStart: LectureStart? = null

    /** The transcript of the updates not sent to the notes yet ([AssistantSettings.notesSeconds]). */
    private val gathered = mutableListOf<RequestTranscript>()

    /** The cache of Gemini holding [lectureStart], deleted when the recording ends. */
    private val promptCache by lazy { GeminiPromptCache(backends.gemini.client) }

    private class QueuedChunk(val chunk: AudioChunk?, val catchUp: Boolean)

    public val isRecording: Boolean get() = lecture.status == LectureStatus.RECORDING

    /** The editor of the document is open: the analysis sees its pages again. */
    internal fun attachNotebook(source: NotebookSource) {
        notebook = source
        lecture.isNotebookAttached = true
    }

    /** The editor of the document is closed ([source]: only if it is still the attached one). */
    internal fun detachNotebook(source: NotebookSource? = null) {
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
        if (follows) following.value = true else lecture.recording.open()
        lecture.isNotebookAttached = notebook != null
        val chunker = if (analyzes) SpeechChunker(targetSeconds = settingsStore.settings.chunkSeconds, overlapSeconds = OVERLAP_SECONDS) else null

        if (!analyzes) notebookJob = lecture.scope.launch {
            // The pages seen, for the review written elsewhere
            while (true) {
                rememberPagesSeen()
                delay(NOTEBOOK_CAPTURE_MS)
            }
        }
        if (analyzes) analysisJob = lecture.scope.launch {
            for (first in updates) {
                val group = mutableListOf(first)
                // Behind schedule: the waiting chunks are analyzed together
                while (group.sumOf { it.newDurationMs() } < MAX_MERGED_MS) {
                    group.add(updates.tryReceive().getOrNull() ?: break)
                }
                analyze(group)
            }
            // Stopped: the transcript gathered since the last notes
            if (gathered.isNotEmpty()) {
                lecture.setState { isAnalyzing = true }
                askNotes(gathered.merged().also { gathered.clear() })
            }
        }

        audioJob = lecture.scope.launch {
            var totalSamples = 0L
            var heardSomething = false
            var lastStateUpdate = 0L
            var lastSaved = 0L
            var lastShared = 0L
            for (block in audioBlocks) {
                if (!follows) lecture.recording.append(block)
                speech.onAudio(block)
                totalSamples += block.size
                if (!heardSomething && block.any { it != 0.toShort() }) heardSomething = true
                val blockLevel = (rms(block) / LEVEL_FULL_SCALE).coerceIn(0f, 1f)

                val chunks = chunker?.push(block).orEmpty().map { QueuedChunk(it, catchUp = false) }.toMutableList()
                if (chunker != null && cutRequested.getAndSet(false)) {
                    val catchUp = catchUpRequested.getAndSet(false)
                    val cut = chunker.cutNow(minSeconds = 0.5f)
                    if (cut != null || catchUp) chunks.add(QueuedChunk(cut, catchUp))
                }
                if (chunks.isNotEmpty()) {
                    lecture.setState { pendingUpdates += chunks.size }
                    chunks.forEach { updates.trySend(it) }
                }

                val recordedMs = samplesToMs(totalSamples)
                // Followed: the length and the level are the ones of the device recording it
                if (follows) continue
                if (chunks.isNotEmpty() || recordedMs - lastStateUpdate >= STATE_UPDATE_MS) {
                    lastStateUpdate = recordedMs
                    lecture.setState {
                        elapsedMs = recordedMs
                        level = blockLevel
                        noAudioWarning = !heardSomething && recordedMs > SILENCE_WARNING_MS
                    }
                }
                // The audio shared as it is recorded: the other devices follow it
                if (lecture.audioShared && recordedMs - lastShared >= AUDIO_SHARE_MS) {
                    lastShared = recordedMs
                    lecture.audioGrew()
                }
                // Now and then: the other devices know it is still being recorded
                if (recordedMs - lastSaved >= HEARTBEAT_MS) {
                    lastSaved = recordedMs
                    lecture.scope.launch { lecture.persist() }
                }
            }
            // Stopped: the last words
            speech.onAudioEnd()
            chunker?.cutNow(minSeconds = 2f)?.let {
                lecture.setState { pendingUpdates += 1 }
                updates.trySend(QueuedChunk(it, catchUp = false))
            }
            updates.close()
            if (!follows) {
                lecture.setState {
                    elapsedMs = samplesToMs(totalSamples)
                    level = 0f
                }
            }
            finishRecordingFile()
        }

        speech.start()
        try {
            capture.start(onSamples = { samples -> audioBlocks.trySend(samples) }, onEvent = ::onCaptureEvent)
        } catch (e: Throwable) {
            audioJob?.cancel()
            analysisJob?.cancel()
            notebookJob?.cancel()
            lecture.scope.launch { speech.close() }
            throw e
        }
        lecture.scope.launch { lecture.persist() }
    }

    /** Analyzes the audio received so far right away, recapping the last minutes. */
    public fun catchUp() {
        if (!isRecording || !analyzes) return
        catchUpRequested.value = true
        cutRequested.value = true
    }

    /**
     * Stops the recording, analyzes the last chunks and reviews the whole lecture (unless the
     * review is written on request: [AssistantSettings.reviewAtTheEnd]).
     */
    public fun stop() {
        if (follows) return stopFollowing()
        if (!isRecording) return
        lecture.status = LectureStatus.FINALIZING
        stopMicrophone()
        lecture.scope.launch {
            audioJob?.join()
            if (analyzes) {
                analysisJob?.join()
                lecture.setState { isAnalyzing = false }
                promptCache.clear(settingsStore.apiKey)
                closeTranscriber()
                reviewAtTheEnd()
            } else {
                notebookJob?.cancelAndJoin()
                rememberPagesSeen()
            }
            lecture.setState { status = LectureStatus.FINISHED }
            lecture.persist()
            onFinished(this@LectureRecorder)
        }
    }

    /** The lecture followed is over: its last chunks analyzed, then its review written. */
    private fun stopFollowing() {
        if (!following.compareAndSet(expect = true, update = false)) return
        stopMicrophone()
        lecture.scope.launch {
            audioJob?.join()
            analysisJob?.join()
            lecture.setState { isAnalyzing = false }
            promptCache.clear(settingsStore.apiKey)
            closeTranscriber()
            reviewAtTheEnd()
            lecture.persist()
            onFinished(this@LectureRecorder)
        }
    }

    /** Stops following the lecture at once (another device writes its notes now, or it is deleted): nothing more written. */
    internal suspend fun abandon() {
        if (!following.compareAndSet(expect = true, update = false)) return
        analysisJob?.cancel()
        stopMicrophone()
        audioJob?.cancelAndJoin()
        transcriber?.close()
        promptCache.clear(settingsStore.apiKey)
        lecture.setState {
            isAnalyzing = false
            pendingUpdates = 0
        }
        onFinished(this)
    }

    /**
     * Ends the recording right away (the application is exiting): the audio is saved, the waiting
     * chunks are dropped and the final review is left to be written later. Does not need the UI
     * thread, which may be the caller.
     */
    internal suspend fun finishNow() {
        if (lecture.status == LectureStatus.FINISHED) return
        analysisJob?.cancel()
        notebookJob?.cancel()
        stopMicrophone()
        // The audio coroutine may be waiting for the UI thread: its tail is done here
        audioJob?.cancel()
        withTimeoutOrNull(2_000) { audioJob?.join() }
        finishRecordingFile()
        withTimeoutOrNull(2_000) { speech.close() }
        lecture.status = LectureStatus.FINISHED
        lecture.level = 0f
        lecture.pendingUpdates = 0
        lecture.isAnalyzing = false
        lecture.persistNow()
        onFinished(this)
    }

    /**
     * The review of the lecture over, when it is written at the end ([AssistantSettings.reviewAtTheEnd]);
     * otherwise written on request: the models of the lecture are freed.
     */
    private suspend fun reviewAtTheEnd() {
        val settings = settingsStore.settings
        if (settings.reviewAtTheEnd) {
            FinalReviewer(lecture, settingsStore, handwriting).write(settings.reviewProvider, notebook)
        } else {
            backends.unloadLiveModels(settings)
        }
    }

    /** The speech recognition stays ready during the lecture: freed at its end. */
    private suspend fun closeTranscriber() {
        try {
            speech.close()
        } catch (e: Throwable) {
            println("DrawView: cannot stop the transcription: ${e.message}")
        }
    }

    /** The system took the microphone (a call, Siri) or gave it back: the capture goes on by itself. */
    private fun onCaptureEvent(event: CaptureEvent) {
        if (event == CaptureEvent.DEVICE_CHANGED) return
        val interrupted = event == CaptureEvent.INTERRUPTED
        lecture.scope.launch {
            lecture.setState { microphoneInterrupted = interrupted }
            interruptionJob?.cancel()
            interruptionJob = if (!interrupted) null else lecture.scope.launch {
                // The other devices know it is still being recorded
                while (true) {
                    lecture.persist()
                    delay(HEARTBEAT_MS)
                }
            }
        }
    }

    private fun stopMicrophone() {
        interruptionJob?.cancel()
        lecture.microphoneInterrupted = false
        try {
            capture.stop()
        } catch (e: Throwable) {
            println("DrawView: cannot stop the microphone: ${e.message}")
        }
        audioBlocks.close()
    }

    /** Ends the recording file (its last page), once (by the audio coroutine or [finishNow]). */
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
            // For the next updates: the handwriting the student is done with
            lastNotebook?.let { readHandwriting(it) }
            lastNotebook = null
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

    /**
     * The live update of [chunks]: their transcript, and the suggestions and the outline when it is
     * time ([AssistantSettings.notesSeconds]); with the audio, all of them at once.
     */
    private suspend fun analyzeChunks(chunks: List<AudioChunk>) {
        val heard = speech.speechOf(chunks) ?: return
        if (heard !is RequestTranscript) return askNotes(heard)
        gathered.add(heard)
        if (heard.endMs - gathered.first().newStartMs < settingsStore.settings.notesSeconds * 1000L) return
        askNotes(gathered.merged().also { gathered.clear() })
    }

    /** The transcripts gathered, as one. */
    private fun List<RequestTranscript>.merged(): RequestTranscript =
        singleOrNull() ?: RequestTranscript(emptyList(), flatMap { it.segments }, first().newStartMs, 0L, last().endMs)

    /** The suggestions, the outline and (with the audio) the transcript of [speech]. */
    private suspend fun askNotes(speech: RequestSpeech) {
        val settings = settingsStore.settings
        var includeOutline = false
        val task = settings.task(LectureTask.LIVE_NOTES)
        val start = startOf(task)
        val result = request(task, settings) { notebook ->
            val memory = lecture.memory(speech.endMs, transcriptBeforeMs = speech.newStartMs)
            includeOutline = memory.outline.isBlank() || speech.endMs - lastOutlineMs >= OUTLINE_INTERVAL_MS
            LecturePrompts.chunkPrompt(settings, memory, notebook, speech, includeOutline = includeOutline, start = start)
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
        val latest = recentChunks.lastOrNull()?.endMs ?: lecture.elapsedMs
        val segments = lecture.setStateResult { transcript.filter { it.endMs > latest - CATCH_UP_MS } }
        val speech: RequestSpeech = if (segments.isNotEmpty()) {
            RequestTranscript(emptyList(), segments, segments.first().startMs, 0L, segments.last().endMs)
        } else if (task.provider == ModelProvider.GEMINI && settings.sendsAudio) {
            requestAudio(recentChunks.toList(), contextMs = 0L) ?: return
        } else {
            return
        }
        val start = startOf(task)
        val result = request(task, settings) { notebook ->
            LecturePrompts.chunkPrompt(
                settings, lecture.memory(speech.endMs), notebook, speech,
                catchUp = true, includeOutline = false, includeTranscript = false, start = start
            )
        } ?: return
        val defaultPage = result.notebook?.visiblePageIndexes?.firstOrNull()
        lecture.setState {
            addNotes(result.analysis.notes, speech.startMs, speech.endMs, defaultPage, fromFinalReview = false)
            usage += result.usage
            problem = null
        }
    }

    private class Answer(val analysis: ChunkAnalysis, val notebook: NotebookSnapshot?, val usage: TokenUsage)

    /**
     * What opens the requests of [task]: the notebook as it is at the first request of Gemini with
     * the notebook open, the same afterwards; none for the local models, which cache nothing.
     */
    private suspend fun startOf(task: TaskModel): LectureStart? {
        if (task.provider != ModelProvider.GEMINI) return null
        lectureStart?.let { return it }
        val source = notebook ?: return null
        val pages = try {
            source.allPages()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Read again at the next request
            return null
        }
        return LectureStart(source.documentTitle, pages).also { lectureStart = it }
    }

    /**
     * Sends the prompt built by [build] (given the notebook, `null` when it is closed) to the
     * provider of [task], repeating it after the temporary errors.
     */
    private suspend fun request(
        task: TaskModel,
        settings: AssistantSettings,
        build: suspend (NotebookSnapshot?) -> ModelPrompt
    ): Answer? {
        var attempt = 0
        while (true) {
            val apiKey = settingsStore.apiKey
            if (task.provider == ModelProvider.GEMINI && apiKey.isEmpty()) {
                lecture.setState { problem = LectureProblem.MissingApiKey }
                return null
            }
            try {
                // The local models read fewer images (a smaller context, a slower answer)
                val notebook = captureNotebook(imagePages = if (task.provider == ModelProvider.LOCAL) 1 else 2)
                val prompt = build(notebook)
                val answer = when (task.provider) {
                    ModelProvider.GEMINI -> backends.gemini.generate(prompt, task.gemini, promptCache)
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

    /**
     * What the student is looking at (with the handwriting transcribed so far), or `null` when the
     * document is closed.
     */
    /** Remembers the pages the student is looking at, when the notebook is open (not analyzed). */
    private suspend fun rememberPagesSeen() {
        val snapshot = captureNotebook(imagePages = IMAGE_PAGES) ?: return
        knownStrokeIds = knownStrokeIds.orEmpty() + snapshot.strokeIds
        lecture.rememberNotebook(snapshot)
        lecture.persist()
    }

    private suspend fun captureNotebook(imagePages: Int): NotebookSnapshot? {
        val source = notebook ?: return null
        return try {
            // The strokes of the document when the lecture starts are not "new writing"
            val known = knownStrokeIds ?: source.strokeIds().also {
                knownStrokeIds = it
                initialStrokeIds = it
            }
            handwriting.fill(source.capture(known, TEXT_PAGES, imagePages)).also { snapshot ->
                lastNotebook = snapshot
                visibleSlides = snapshot.pages.filter { it.index in snapshot.visiblePageIndexes }.mapNotNull { it.slide?.text?.takeIf(String::isNotBlank) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("DrawView: cannot capture the notebook: ${e.message}")
            null
        }
    }

    /**
     * Transcribes a few blocks of handwriting of [snapshot] the student is done with (the visible
     * pages first), with the model of the live suggestions. Not essential: an error is ignored.
     */
    private suspend fun readHandwriting(snapshot: NotebookSnapshot) {
        val source = notebook ?: return
        val task = settingsStore.settings.task(LectureTask.LIVE_NOTES)
        if (task.provider == ModelProvider.GEMINI && !settingsStore.hasApiKey) return
        val max = if (task.provider == ModelProvider.LOCAL) HandwritingReader.MAX_PER_UPDATE_LOCAL else HandwritingReader.MAX_PER_UPDATE_ONLINE
        val order = snapshot.pages.map { it.index }
        val blocks = snapshot.inkBlocks.sortedBy { order.indexOf(it.pageIndex) }
        try {
            val tokens = handwriting.read(blocks, snapshot.pages, source, task, max, stableOnly = true, initialStrokeIds = initialStrokeIds) ?: return
            val pages = handwriting.fill(snapshot).pages
            lecture.setState {
                usage += tokens
                updateSeenPages(pages)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("DrawView: cannot read the handwriting: ${e.message}")
        }
    }

    /** What helps the speech recognition now: the course and the slides on screen. */
    private fun speechHints() = LecturePrompts.speechHints(settingsStore.settings, lecture.courseContext(), visibleSlides)

    public companion object {
        internal const val OVERLAP_SECONDS = 5

        /** How often the notebook of a recording not analyzed is captured, and its visible pages as images. */
        internal const val NOTEBOOK_CAPTURE_MS = 30_000L

        /** How often the lecture being recorded is saved (and sent to the other devices) at least. */
        internal const val HEARTBEAT_MS = 30_000L

        /** How often the audio recorded is offered to the other devices, which follow it. */
        internal const val AUDIO_SHARE_MS = 5_000L

        /** Without news of a lecture being recorded on another device for so long, it may have stopped. */
        public const val SILENT_ELSEWHERE_MS: Long = 2 * 60_000L
        internal const val IMAGE_PAGES = 2

        /** Pages described around the visible ones (their text costs little). */
        internal const val TEXT_PAGES = 4

        /** New audio merged in a request when the analysis is behind (a chunk can exceed it). */
        internal const val MAX_MERGED_MS = 3 * 60_000L

        internal const val CATCH_UP_MS = 3 * 60_000L
        internal const val MAX_ATTEMPTS = 6
        internal const val SILENCE_WARNING_MS = 4_000L
        internal const val STATE_UPDATE_MS = 200L
        internal const val LEVEL_FULL_SCALE = 6_000f

        /** The outline is rewritten (output tokens) every few minutes only. */
        internal const val OUTLINE_INTERVAL_MS = 210_000L
    }
}
