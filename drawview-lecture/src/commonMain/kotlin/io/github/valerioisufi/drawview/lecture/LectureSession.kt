package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.core.util.currentTimeMillis
import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.AudioChunk
import io.github.valerioisufi.drawview.lecture.audio.AudioPlayer
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.audio.SpeechChunker
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.audio.rms
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.gemini.GeminiUsage
import io.github.valerioisufi.drawview.lecture.gemini.GenerateContentRequest
import io.github.valerioisufi.drawview.lecture.gemini.GenerateContentResponse
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlin.random.Random

enum class LectureStatus { RECORDING, FINALIZING, FINISHED }

enum class ReviewStatus { NONE, RUNNING, DONE, FAILED }

/** A problem of the assistant, shown in its panel. The recording goes on in any case. */
sealed interface LectureProblem {
    data object MissingApiKey : LectureProblem

    /** The key was refused (wrong key, API not enabled, model not allowed). */
    data class Authentication(val detail: String) : LectureProblem

    /** A temporary error: the update is repeated in [retryInSeconds]. */
    data class Retrying(val detail: String, val retryInSeconds: Int) : LectureProblem

    /** An update failed: its content will be covered by the final review. */
    data class Failed(val detail: String) : LectureProblem
}

/**
 * A lecture being recorded and followed by the assistant.
 *
 * The microphone audio is written to disk ([RecordingFile]) and cut into chunks of about
 * [AssistantSettings.chunkSeconds] ([SpeechChunker]). The chunks are analyzed one at a time, in
 * order: every request carries the memory of the lecture (outline, suggested notes and their
 * status, end of the transcript), what the student is looking at and writing ([NotebookSource]),
 * the slides and the new audio, and returns the transcript, the updated outline and the missing
 * notes ([ChunkAnalysis]). When the analysis falls behind (slow or missing network) the waiting
 * chunks are merged into a single request. When the recording stops, the whole lecture is
 * reviewed once more with the complete recording ([FinalReview]).
 *
 * Every observable property is Compose state, updated on [stateContext].
 */
class LectureSession internal constructor(
    val documentId: Int,
    val directory: String,
    private val settingsStore: AssistantSettingsStore,
    private val notebook: NotebookSource,
    private val client: GeminiClient,
    private val capture: AudioCapture,
    private val player: AudioPlayer?,
    private val stateContext: CoroutineContext = Dispatchers.Main
) {
    val id: String = directory.trimEnd('/').substringAfterLast('/')
    val startedAt: Long = currentTimeMillis()

    var status by mutableStateOf(LectureStatus.RECORDING)
        private set

    /** Recorded audio. */
    var elapsedMs by mutableLongStateOf(0L)
        private set

    /** Input level of the microphone (0..1). */
    var level by mutableFloatStateOf(0f)
        private set

    /** The microphone delivers only silence (typically: access not granted). */
    var noAudioWarning by mutableStateOf(false)
        private set

    /** Chunks waiting to be analyzed (or being analyzed). */
    var pendingUpdates by mutableIntStateOf(0)
        private set

    var isAnalyzing by mutableStateOf(false)
        private set

    var problem by mutableStateOf<LectureProblem?>(null)
        private set

    var outline by mutableStateOf("")
        private set

    var currentTopic by mutableStateOf("")
        private set

    /** Page the lecturer is talking about (last known). */
    var professorPageIndex by mutableStateOf<Int?>(null)
        private set

    val suggestions = mutableStateListOf<NoteSuggestion>()
    val transcript = mutableStateListOf<TranscriptSegment>()

    var finalReview by mutableStateOf<String?>(null)
        private set

    var reviewStatus by mutableStateOf(ReviewStatus.NONE)
        private set

    var usage by mutableStateOf(TokenUsage())
        private set

    val canPlayAudio: Boolean get() = player != null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recording = RecordingFile(directory)
    private val audioBlocks = Channel<ShortArray>(Channel.UNLIMITED)
    private val updates = Channel<QueuedChunk>(Channel.UNLIMITED)
    private val cutRequested = atomic(false)
    private val catchUpRequested = atomic(false)
    private val persistMutex = Mutex()
    private var audioJob: Job? = null
    private var analysisJob: Job? = null

    // Owned by the analysis coroutine (and by the final review, which runs after it)
    private var knownStrokeIds: Set<String> = emptySet()
    private val uploadedSlides = mutableMapOf<String, UploadedSlides>()
    private val failedSlides = mutableSetOf<String>()
    private val lastPageImages = LinkedHashMap<Int, PageImage>()
    private val recentChunks = ArrayDeque<AudioChunk>()
    private var lastPageCount = Int.MAX_VALUE

    private class QueuedChunk(val chunk: AudioChunk?, val catchUp: Boolean)

    /**
     * Opens the recording and starts the microphone.
     *
     * @throws io.github.valerioisufi.drawview.lecture.audio.AudioCaptureException when no microphone can be opened.
     */
    internal fun start() {
        recording.open()
        val chunker = SpeechChunker(targetSeconds = settingsStore.settings.chunkSeconds, overlapSeconds = OVERLAP_SECONDS)

        analysisJob = scope.launch {
            knownStrokeIds = try {
                notebook.strokeIds()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptySet()
            }
            for (first in updates) {
                val group = mutableListOf(first)
                // Behind schedule: the waiting chunks are analyzed together
                while (group.sumOf { it.newDurationMs() } < MAX_MERGED_MS) {
                    group.add(updates.tryReceive().getOrNull() ?: break)
                }
                analyze(group)
            }
        }

        audioJob = scope.launch {
            var totalSamples = 0L
            var heardSomething = false
            var lastStateUpdate = 0L
            for (block in audioBlocks) {
                recording.append(block)
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
                    setState { pendingUpdates += chunks.size }
                    chunks.forEach { updates.trySend(it) }
                }

                val recordedMs = samplesToMs(totalSamples)
                if (chunks.isNotEmpty() || recordedMs - lastStateUpdate >= STATE_UPDATE_MS) {
                    lastStateUpdate = recordedMs
                    setState {
                        elapsedMs = recordedMs
                        level = blockLevel
                        noAudioWarning = !heardSomething && recordedMs > SILENCE_WARNING_MS
                    }
                }
            }
            // Stopped: the last words
            chunker.cutNow(minSeconds = 2f)?.let {
                setState { pendingUpdates += 1 }
                updates.trySend(QueuedChunk(it, catchUp = false))
            }
            updates.close()
            setState {
                elapsedMs = samplesToMs(totalSamples)
                level = 0f
            }
            try {
                recording.finish()
            } catch (e: Throwable) {
                println("DrawView: cannot finish the recording $directory: ${e.message}")
            }
        }

        try {
            capture.start { samples -> audioBlocks.trySend(samples) }
        } catch (e: Throwable) {
            scope.cancel()
            throw e
        }
        scope.launch { persist() }
    }

    /** Analyzes the audio received so far right away, recapping the last minutes. */
    fun catchUp() {
        if (status != LectureStatus.RECORDING) return
        catchUpRequested.value = true
        cutRequested.value = true
    }

    /** Stops the recording, analyzes the last chunks and reviews the whole lecture. */
    fun stop() {
        if (status != LectureStatus.RECORDING) return
        status = LectureStatus.FINALIZING
        try {
            capture.stop()
        } catch (e: Throwable) {
            println("DrawView: cannot stop the microphone: ${e.message}")
        }
        audioBlocks.close()
        scope.launch {
            audioJob?.join()
            analysisJob?.join()
            runFinalReview()
            setState { status = LectureStatus.FINISHED }
            persist()
        }
    }

    /** Runs the final review again (e.g. after a network error). */
    fun retryFinalReview() {
        if (status != LectureStatus.FINISHED || reviewStatus == ReviewStatus.RUNNING) return
        scope.launch {
            runFinalReview()
            persist()
        }
    }

    /** Records what the student did with a suggestion (sent back to the model). */
    fun updateSuggestion(id: String, status: SuggestionStatus) {
        val index = suggestions.indexOfFirst { it.id == id }
        if (index < 0) return
        suggestions[index] = suggestions[index].copy(status = status)
        scope.launch { persist() }
    }

    /** Plays the recording around the moment [suggestion] was said. */
    fun play(suggestion: NoteSuggestion) {
        val output = player ?: return
        scope.launch {
            val samples = try {
                recording.readSegment((suggestion.atMs - PLAYBACK_LEAD_MS).coerceAtLeast(0L), PLAYBACK_DURATION_MS)
            } catch (_: Throwable) {
                null
            }
            if (samples != null) output.play(samples)
        }
    }

    fun stopPlayback() {
        player?.stop()
    }

    // ==========================================================
    // Live analysis
    // ==========================================================

    private fun QueuedChunk.newDurationMs(): Long = chunk?.let { it.endMs - it.newStartMs } ?: 0L

    private suspend fun analyze(group: List<QueuedChunk>) {
        val catchUp = group.any { it.catchUp }
        val chunks = group.mapNotNull { it.chunk }
        setState { isAnalyzing = true }
        try {
            val audio = buildRequestAudio(chunks, catchUp) ?: return
            var attempt = 0
            while (true) {
                val apiKey = settingsStore.apiKey
                if (apiKey.isEmpty()) {
                    setState { problem = LectureProblem.MissingApiKey }
                    return
                }
                val settings = settingsStore.settings
                try {
                    val snapshot = try {
                        notebook.capture(knownStrokeIds, settings.neighborPages)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        println("DrawView: cannot capture the notebook: ${e.message}")
                        null
                    }
                    val slides = if (settings.sendSlides && snapshot != null) uploadSlides(apiKey, snapshot.slides) else emptyList()
                    val request = LecturePrompts.chunkRequest(settings, memory(audio.endMs), snapshot, slides, audio, catchUp)
                    val response = client.generateContent(apiKey, settings.model, request)
                    val analysis = LectureAnalysis.parseChunk(response.text())

                    if (snapshot != null) {
                        knownStrokeIds = knownStrokeIds + snapshot.strokeIds
                        lastPageCount = snapshot.pageCount
                        snapshot.pageImages.forEach { rememberPageImage(it) }
                    }
                    val defaultPage = snapshot?.visiblePageIndexes?.firstOrNull()
                    setState {
                        if (analysis.transcript.isNotBlank()) {
                            transcript.add(TranscriptSegment(audio.newStartMs, audio.endMs, analysis.transcript.trim()))
                        }
                        if (analysis.outline.isNotBlank()) outline = analysis.outline.trim()
                        if (analysis.currentTopic.isNotBlank()) currentTopic = analysis.currentTopic.trim()
                        analysis.professorPage?.let { it - 1 }?.takeIf { it in 0 until lastPageCount }?.let { professorPageIndex = it }
                        addNotes(analysis.notes, audio.startMs, audio.endMs, defaultPage, fromFinalReview = false)
                        usage += response.usageMetadata.toTokenUsage()
                        problem = null
                    }
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    attempt++
                    val detail = e.message ?: e::class.simpleName.orEmpty()
                    if (e is GeminiException && e.isAuthenticationError) {
                        setState { problem = LectureProblem.Authentication(detail) }
                        return
                    }
                    val retryable = when (e) {
                        is GeminiException -> e.isRetryable
                        // An invalid answer (e.g. a truncated JSON): one more try
                        is IllegalArgumentException -> attempt < 2
                        // Network errors
                        else -> true
                    }
                    val maxAttempts = if (status == LectureStatus.RECORDING) MAX_ATTEMPTS else MAX_ATTEMPTS_WHEN_STOPPED
                    if (!retryable || attempt >= maxAttempts) {
                        setState { problem = LectureProblem.Failed(detail) }
                        return
                    }
                    val waitSeconds = min(30, 2 shl attempt)
                    setState { problem = LectureProblem.Retrying(detail, waitSeconds) }
                    delay(waitSeconds * 1000L)
                }
            }
        } finally {
            chunks.forEach { recentChunks.addLast(it) }
            val latestEnd = recentChunks.lastOrNull()?.endMs ?: 0L
            while (recentChunks.size > 1 && latestEnd - recentChunks.first().endMs > CATCH_UP_MS) recentChunks.removeFirst()
            withContext(NonCancellable) {
                setState {
                    pendingUpdates = (pendingUpdates - group.size).coerceAtLeast(0)
                    isAnalyzing = false
                }
                persist()
            }
        }
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

    /** Uploads (once per session) the PDF documents of [documents]. */
    private suspend fun uploadSlides(apiKey: String, documents: List<SlideDocument>): List<UploadedSlides> {
        val result = mutableListOf<UploadedSlides>()
        for (document in documents) {
            val existing = uploadedSlides[document.resourceId]
            if (existing != null) {
                result.add(UploadedSlides(document, existing.file))
                continue
            }
            if (document.resourceId in failedSlides) continue
            try {
                val bytes = PlatformFiles.readBytes(document.path)
                if (bytes == null || bytes.size > MAX_SLIDES_BYTES) {
                    failedSlides.add(document.resourceId)
                    continue
                }
                val file = client.uploadFile(apiKey, bytes, "application/pdf", document.path.substringAfterLast('/'))
                val uploaded = UploadedSlides(document, file)
                uploadedSlides[document.resourceId] = uploaded
                result.add(uploaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The page images are sent anyway
                println("DrawView: cannot upload the slides ${document.path}: ${e.message}")
                if (e !is GeminiException || !e.isRetryable) failedSlides.add(document.resourceId)
            }
        }
        return result
    }

    private fun rememberPageImage(image: PageImage) {
        lastPageImages.remove(image.pageIndex)
        lastPageImages[image.pageIndex] = image
        while (lastPageImages.size > MAX_REVIEW_PAGE_IMAGES) lastPageImages.remove(lastPageImages.keys.first())
    }

    /** Adds the notes of an answer, skipping the ones already suggested. Runs on [stateContext]. */
    private fun addNotes(notes: List<AnalysisNote>, startMs: Long, endMs: Long, defaultPage: Int?, fromFinalReview: Boolean) {
        val known = suggestions.mapTo(mutableSetOf()) { normalize(it.title) }
        for (note in notes) {
            val title = note.title.trim()
            val content = note.content.trim()
            if (content.isEmpty() || !known.add(normalize(title.ifEmpty { content }))) continue
            suggestions.add(
                NoteSuggestion(
                    id = "note_" + Random.nextLong().toULong().toString(16),
                    kind = NoteKind.parse(note.kind),
                    title = title,
                    content = content,
                    pageIndex = note.page?.let { it - 1 }?.takeIf { it in 0 until lastPageCount } ?: defaultPage,
                    importance = note.importance.coerceIn(1, 3),
                    atMs = (startMs + (note.atSeconds * 1000).toLong()).coerceIn(startMs, maxOf(startMs, endMs)),
                    fromFinalReview = fromFinalReview
                )
            )
        }
    }

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private suspend fun memory(elapsed: Long): LectureMemory = withContext(stateContext) {
        LectureMemory(
            elapsedMs = elapsed,
            outline = outline,
            currentTopic = currentTopic,
            previousTranscript = transcript.takeLast(3).joinToString(" ") { it.text }.takeLast(PREVIOUS_TRANSCRIPT_CHARS),
            suggestions = suggestions.toList()
        )
    }

    // ==========================================================
    // Final review
    // ==========================================================

    private suspend fun runFinalReview() {
        val apiKey = settingsStore.apiKey
        val transcriptCopy = withContext(stateContext) { transcript.toList() }
        if (transcriptCopy.isEmpty() && recording.pcmBytes() < MIN_REVIEW_SECONDS * LECTURE_SAMPLE_RATE * 2L) return
        if (apiKey.isEmpty()) {
            setState {
                problem = LectureProblem.MissingApiKey
                reviewStatus = ReviewStatus.FAILED
            }
            return
        }
        setState { reviewStatus = ReviewStatus.RUNNING }
        val settings = settingsStore.settings
        try {
            val audioFile = try {
                recording.readWav()?.let { client.uploadFile(apiKey, it, "audio/wav", "lecture-$id.wav") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Review on the transcript only
                println("DrawView: cannot upload the recording: ${e.message}")
                null
            }
            val request = LecturePrompts.finalReviewRequest(
                settings = settings,
                documentTitle = notebook.documentTitle,
                memory = memory(elapsedMs),
                transcript = transcriptCopy,
                pageImages = lastPageImages.values.toList(),
                slides = if (settings.sendSlides) uploadedSlides.values.toList() else emptyList(),
                audioFile = audioFile
            )
            val response = generateWithRetries(apiKey, settings.model, request, attempts = MAX_ATTEMPTS_WHEN_STOPPED + 1)
            val review = LectureAnalysis.parseFinalReview(response.text())
            setState {
                finalReview = review.notesMarkdown.trim().ifEmpty { null }
                addNotes(review.missing, 0L, elapsedMs, defaultPage = null, fromFinalReview = true)
                usage += response.usageMetadata.toTokenUsage()
                reviewStatus = ReviewStatus.DONE
                problem = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val detail = e.message ?: e::class.simpleName.orEmpty()
            setState {
                reviewStatus = ReviewStatus.FAILED
                problem = if (e is GeminiException && e.isAuthenticationError) {
                    LectureProblem.Authentication(detail)
                } else {
                    LectureProblem.Failed(detail)
                }
            }
        }
    }

    private suspend fun generateWithRetries(
        apiKey: String,
        model: String,
        request: GenerateContentRequest,
        attempts: Int
    ): GenerateContentResponse {
        var attempt = 0
        while (true) {
            try {
                return client.generateContent(apiKey, model, request)
            } catch (e: GeminiException) {
                attempt++
                if (!e.isRetryable || attempt >= attempts) throw e
                delay((5_000L shl attempt).coerceAtMost(30_000L))
            }
        }
    }

    // ==========================================================
    // State and persistence
    // ==========================================================

    private suspend fun setState(block: LectureSession.() -> Unit) = withContext(stateContext) { block() }

    /** Writes `lecture.json` (atomically). */
    private suspend fun persist() {
        val record = withContext(stateContext) {
            LectureRecord(
                id = id,
                documentId = documentId,
                documentTitle = notebook.documentTitle,
                startedAt = startedAt,
                durationMs = elapsedMs,
                outline = outline,
                currentTopic = currentTopic,
                transcript = transcript.toList(),
                suggestions = suggestions.toList(),
                finalReview = finalReview,
                usage = usage
            )
        }
        persistMutex.withLock {
            withContext(Dispatchers.Default) {
                try {
                    val target = "$directory/lecture.json"
                    val temporary = "$target.tmp"
                    if (PlatformFiles.writeBytes(temporary, recordJson.encodeToString(LectureRecord.serializer(), record).encodeToByteArray())) {
                        SystemFileSystem.atomicMove(Path(temporary), Path(target))
                    }
                } catch (e: Throwable) {
                    println("DrawView: cannot save the lecture $directory: ${e.message}")
                }
            }
        }
    }

    private fun GeminiUsage?.toTokenUsage(): TokenUsage = TokenUsage(
        requests = 1,
        promptTokens = this?.promptTokenCount ?: 0,
        cachedTokens = this?.cachedContentTokenCount ?: 0,
        outputTokens = (this?.candidatesTokenCount ?: 0) + (this?.thoughtsTokenCount ?: 0)
    )

    internal companion object {
        const val OVERLAP_SECONDS = 5
        /** New audio merged in a request when the analysis is behind (a chunk can exceed it). */
        const val MAX_MERGED_MS = 3 * 60_000L

        /** Audio of a request (inline data: the whole request must stay under 20 MB). */
        const val MAX_REQUEST_AUDIO_MS = 6 * 60_000L
        const val CATCH_UP_MS = 3 * 60_000L
        const val MAX_ATTEMPTS = 6
        const val MAX_ATTEMPTS_WHEN_STOPPED = 2
        const val MAX_SLIDES_BYTES = 45 * 1024 * 1024
        const val MAX_REVIEW_PAGE_IMAGES = 16
        const val MIN_REVIEW_SECONDS = 20
        const val PREVIOUS_TRANSCRIPT_CHARS = 600
        const val PLAYBACK_LEAD_MS = 3_000L
        const val PLAYBACK_DURATION_MS = 20_000L
        const val SILENCE_WARNING_MS = 4_000L
        const val STATE_UPDATE_MS = 200L
        const val LEVEL_FULL_SCALE = 6_000f

        val recordJson = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
