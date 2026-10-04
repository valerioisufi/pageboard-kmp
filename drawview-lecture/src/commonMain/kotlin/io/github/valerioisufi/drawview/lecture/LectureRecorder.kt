package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.AudioChunk
import io.github.valerioisufi.drawview.lecture.audio.SpeechChunker
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.audio.isSilent
import io.github.valerioisufi.drawview.lecture.audio.rms
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
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
 * writing ([NotebookSource]), the slides and the new audio, and returns the transcript, the
 * updated outline and the missing notes ([ChunkAnalysis]). When the analysis falls behind (slow or
 * missing network) the waiting chunks are merged into a single request. When the recording
 * stops, the lecture is reviewed once more ([Lecture.generateFinalReview]).
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
    private val onFinished: (LectureRecorder) -> Unit = {}
) {
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
        lecture.status = LectureStatus.FINISHED
        lecture.level = 0f
        lecture.pendingUpdates = 0
        lecture.isAnalyzing = false
        lecture.persistNow()
        onFinished(this)
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
            // Nothing said (a break, an exercise in silence): no request
            if (!catchUp && chunks.isNotEmpty() && chunks.all { isSilent(it.samples, it.overlapSamples) }) {
                lecture.setState { usage += TokenUsage(skippedUpdates = chunks.size) }
                return
            }
            val audio = buildRequestAudio(chunks, catchUp) ?: return
            var attempt = 0
            while (true) {
                val apiKey = settingsStore.apiKey
                if (apiKey.isEmpty()) {
                    lecture.setState { problem = LectureProblem.MissingApiKey }
                    return
                }
                val settings = settingsStore.settings
                try {
                    val snapshot = captureNotebook(settings)
                    val slides = when {
                        !settings.sendSlides -> emptyList()
                        snapshot != null -> lecture.uploadSlides(apiKey, snapshot.slides).also { lastSlides = it }
                        // Notebook closed: the slides seen so far
                        else -> lastSlides
                    }
                    val notebookSnapshot = snapshot?.let { withUnreadableSlides(it, slides) }
                    val memory = lecture.memory(audio.endMs)
                    val includeOutline = catchUp || memory.outline.isBlank() || audio.endMs - lastOutlineMs >= OUTLINE_INTERVAL_MS
                    val request = LecturePrompts.chunkRequest(
                        settings, memory, notebookSnapshot, slides, audio, catchUp,
                        includeOutline = includeOutline,
                        mediaResolution = lecture.mediaResolution
                    )
                    val response = lecture.generateContent(apiKey, settings.model, request)
                    val analysis = LectureAnalysis.parseChunk(response.text())
                    if (includeOutline && analysis.outline.isNotBlank()) lastOutlineMs = audio.endMs

                    if (notebookSnapshot != null) {
                        knownStrokeIds = knownStrokeIds.orEmpty() + notebookSnapshot.strokeIds
                        lecture.rememberNotebook(notebookSnapshot)
                    }
                    val defaultPage = notebookSnapshot?.visiblePageIndexes?.firstOrNull()
                    val usage = with(lecture) { response.usageMetadata.toTokenUsage() }
                    lecture.setState {
                        if (analysis.transcript.isNotBlank()) {
                            transcript.add(TranscriptSegment(audio.newStartMs, audio.endMs, analysis.transcript.trim()))
                        }
                        if (analysis.outline.isNotBlank()) outline = analysis.outline.trim()
                        if (analysis.currentTopic.isNotBlank()) currentTopic = analysis.currentTopic.trim()
                        analysis.professorPage?.let { it - 1 }?.takeIf { it in 0 until lastPageCount }?.let { professorPageIndex = it }
                        addNotes(analysis.notes, audio.startMs, audio.endMs, defaultPage, fromFinalReview = false)
                        this.usage += usage
                        problem = null
                    }
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (lecture.refusesMediaResolution(e)) {
                        // An API version without the parameter: the default resolution
                        lecture.mediaResolution = null
                        continue
                    }
                    attempt++
                    val detail = e.message ?: e::class.simpleName.orEmpty()
                    if (e is GeminiException && e.isAuthenticationError) {
                        lecture.setState { problem = LectureProblem.Authentication(detail) }
                        return
                    }
                    val retryable = when (e) {
                        is GeminiException -> e.isRetryable
                        // An invalid answer (e.g. a truncated JSON): one more try
                        is IllegalArgumentException -> attempt < 2
                        // Network errors
                        else -> true
                    }
                    val maxAttempts = if (isRecording) MAX_ATTEMPTS else Lecture.MAX_ATTEMPTS_WHEN_STOPPED
                    if (!retryable || attempt >= maxAttempts) {
                        lecture.setState { problem = LectureProblem.Failed(detail) }
                        return
                    }
                    val waitSeconds = min(30, 2 shl attempt)
                    lecture.setState { problem = LectureProblem.Retrying(detail, waitSeconds) }
                    delay(waitSeconds * 1000L)
                }
            }
        } finally {
            chunks.forEach { recentChunks.addLast(it) }
            val latestEnd = recentChunks.lastOrNull()?.endMs ?: 0L
            while (recentChunks.size > 1 && latestEnd - recentChunks.first().endMs > CATCH_UP_MS) recentChunks.removeFirst()
            withContext(NonCancellable) {
                lecture.setState {
                    pendingUpdates = (pendingUpdates - group.size).coerceAtLeast(0)
                    isAnalyzing = false
                }
                lecture.persist()
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

    /** Slides whose PDF is not sent: the model sees the whole page instead. */
    private suspend fun withUnreadableSlides(snapshot: NotebookSnapshot, slides: List<UploadedSlides>): NotebookSnapshot {
        val sent = slides.mapTo(mutableSetOf()) { it.document.resourceId }
        val unreadable = snapshot.pages.filter { it.slide != null && it.slide.resourceId !in sent }.map { it.index }
        if (unreadable.isEmpty()) return snapshot
        val source = notebook ?: return snapshot
        return snapshot.withPageImages(source.renderPages(unreadable).associateBy { it.pageIndex })
    }

    /**
     * The audio of a request: the [chunks] (consecutive), preceded by the last minutes already
     * analyzed when the student asked to catch up.
     */
    private fun buildRequestAudio(chunks: List<AudioChunk>, catchUp: Boolean): RequestAudio? {
        val context = if (catchUp) {
            val firstNew = chunks.firstOrNull()?.newStartSample ?: Long.MAX_VALUE
            // The most recent chunks, within the audio budget of a request (inline data is limited)
            var budgetMs = MAX_REQUEST_AUDIO_MS - chunks.sumOf { it.endMs - it.newStartMs }
            val selected = ArrayDeque<AudioChunk>()
            for (chunk in recentChunks.filter { it.newStartSample < firstNew }.asReversed()) {
                budgetMs -= chunk.endMs - chunk.newStartMs
                if (budgetMs < 0) break
                selected.addFirst(chunk)
            }
            selected.toList()
        } else {
            emptyList()
        }
        val all = context + chunks
        if (all.isEmpty()) return null

        var end = all.first().startSample
        val start = end
        val pieces = mutableListOf<ShortArray>()
        for (chunk in all) {
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
        // Without new audio (catch-up right after a chunk) the whole audio is context
        val newStart = chunks.firstOrNull()?.newStartSample ?: end
        return RequestAudio(
            wav = Wav.encode(samples),
            startMs = samplesToMs(start),
            contextMs = samplesToMs(newStart - start),
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
    }
}
