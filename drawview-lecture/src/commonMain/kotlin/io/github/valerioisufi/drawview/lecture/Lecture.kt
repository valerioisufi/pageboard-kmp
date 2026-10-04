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
import io.github.valerioisufi.drawview.lecture.audio.AudioPlayer
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.gemini.GeminiUsage
import io.github.valerioisufi.drawview.lecture.gemini.GenerateContentRequest
import io.github.valerioisufi.drawview.lecture.gemini.GenerateContentResponse
import io.github.valerioisufi.drawview.lecture.local.OllamaChatRequest
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.local.OllamaException
import io.github.valerioisufi.drawview.lecture.local.OllamaMessage
import io.github.valerioisufi.drawview.lecture.local.OllamaOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64
import kotlin.random.Random

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

    /** The local model (Ollama) cannot be reached or is not installed. */
    data class LocalModelUnavailable(val detail: String) : LectureProblem

    /** The local review needs the transcript of the live analysis, which is empty. */
    data object NoTranscript : LectureProblem
}

/**
 * A lecture of a document: what was recorded and generated (transcript, suggested notes and what
 * the student did with them, outline, final review), saved in its folder:
 * `lecture.json`, the audio ([RecordingFile]) and `pages/`, the images of the notes of the
 * student kept for the final review.
 *
 * A lecture outlives the editor of its document: it is recorded by a [LectureRecorder] (also
 * while the document is closed), loaded again by the [LectureLibrary], and can be listened to and
 * reviewed at any time. The final review is written by Gemini with the complete recording, or by
 * a local model (Ollama) with the transcript.
 *
 * Every observable property is Compose state, updated on [stateContext].
 */
class Lecture internal constructor(
    val documentId: Int,
    val directory: String,
    record: LectureRecord?,
    private val settingsStore: AssistantSettingsStore,
    private val client: GeminiClient,
    private val localClient: OllamaClient,
    private val player: AudioPlayer?,
    internal val stateContext: CoroutineContext = Dispatchers.Main
) {
    val id: String = directory.trimEnd('/').substringAfterLast('/')
    val startedAt: Long = record?.startedAt ?: currentTimeMillis()

    var documentTitle by mutableStateOf(record?.documentTitle.orEmpty())
        internal set

    var status by mutableStateOf(LectureStatus.RECORDING)
        internal set

    /** Recorded audio. */
    var elapsedMs by mutableLongStateOf(record?.durationMs ?: 0L)
        internal set

    // --- Live state of the recording (see LectureRecorder) ---

    /** Input level of the microphone (0..1). */
    var level by mutableFloatStateOf(0f)
        internal set

    /** The microphone delivers only silence (typically: access not granted). */
    var noAudioWarning by mutableStateOf(false)
        internal set

    /** Chunks waiting to be analyzed (or being analyzed). */
    var pendingUpdates by mutableIntStateOf(0)
        internal set

    var isAnalyzing by mutableStateOf(false)
        internal set

    /** Whether the notebook of the lecture is open (the analysis sees the pages). */
    var isNotebookAttached by mutableStateOf(false)
        internal set

    // --- Generated content ---

    var problem by mutableStateOf<LectureProblem?>(null)
        internal set

    var outline by mutableStateOf(record?.outline.orEmpty())
        internal set

    var currentTopic by mutableStateOf(record?.currentTopic.orEmpty())
        internal set

    /** Page the lecturer is talking about (last known). */
    var professorPageIndex by mutableStateOf<Int?>(null)
        internal set

    val suggestions = mutableStateListOf<NoteSuggestion>().apply { record?.let { addAll(it.suggestions) } }
    val transcript = mutableStateListOf<TranscriptSegment>().apply { record?.let { addAll(it.transcript) } }

    var finalReview by mutableStateOf(record?.finalReview)
        private set

    var reviewStatus by mutableStateOf(if (record?.finalReview != null) ReviewStatus.DONE else ReviewStatus.NONE)
        private set

    /** Model of the final review: the Gemini model or the local one. */
    var reviewModel by mutableStateOf(record?.finalReviewBy)
        private set

    /** Provider of the final review being written (or written). */
    var reviewProvider by mutableStateOf(record?.finalReviewProvider)
        private set

    /** Tokens written so far by the local model. */
    var reviewProgressTokens by mutableIntStateOf(0)
        private set

    var usage by mutableStateOf(record?.usage ?: TokenUsage())
        internal set

    val canPlayAudio: Boolean get() = player != null

    /** The final review can be written (again): the recording is over and no review is running. */
    val canGenerateReview: Boolean get() = status == LectureStatus.FINISHED && reviewStatus != ReviewStatus.RUNNING

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val recording = RecordingFile(directory)
    private val persistMutex = Mutex()

    // Material of the requests (the analysis coroutine of the recorder, then the review)
    internal val uploadedSlides = mutableMapOf<String, UploadedSlides>()
    private val failedSlides = mutableSetOf<String>()
    internal val slideDocuments = LinkedHashMap<String, SlideDocument>()
    private val reviewPages = LinkedHashMap<String, ReviewPage>()
    private val reviewImageCache = mutableMapOf<String, ByteArray>()
    internal var lastPageCount = Int.MAX_VALUE
    internal var lastPageDbIds: List<Int> = emptyList()

    /** Resolution of the images; `null` once the API refused the parameter. */
    internal var mediaResolution: String? = LecturePrompts.MEDIA_RESOLUTION

    init {
        if (record != null) {
            // Saved while recording: the recording was interrupted (see LectureLibrary)
            status = LectureStatus.FINISHED
            record.reviewPages.forEach { reviewPages[it.file] = it }
            record.slides.forEach { slideDocuments[it.resourceId] = it }
        }
    }

    // ==========================================================
    // Actions
    // ==========================================================

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

    /**
     * Writes the final review (again, e.g. after a network error or for a lecture interrupted
     * before its review), by [provider] (by default the one of the settings).
     */
    fun generateFinalReview(provider: ReviewProvider = settingsStore.settings.reviewProvider) {
        if (!canGenerateReview) return
        scope.launch {
            runFinalReview(provider)
            persist()
        }
    }

    // ==========================================================
    // Live updates (LectureRecorder)
    // ==========================================================

    internal suspend fun setState(block: Lecture.() -> Unit) = withContext(stateContext) { block() }

    internal suspend fun memory(elapsed: Long): LectureMemory = withContext(stateContext) {
        LectureMemory(
            elapsedMs = elapsed,
            outline = outline,
            currentTopic = currentTopic,
            previousTranscript = transcript.takeLast(3).joinToString(" ") { it.text }.takeLast(PREVIOUS_TRANSCRIPT_CHARS),
            suggestions = suggestions.toList()
        )
    }

    /** Adds the notes of an answer, skipping the ones already suggested. Runs on [stateContext]. */
    internal fun addNotes(notes: List<AnalysisNote>, startMs: Long, endMs: Long, defaultPage: Int?, fromFinalReview: Boolean) {
        val known = suggestions.mapTo(mutableSetOf()) { normalize(it.title) }
        for (note in notes) {
            val title = note.title.trim()
            val content = note.content.trim()
            if (content.isEmpty() || !known.add(normalize(title.ifEmpty { content }))) continue
            val pageIndex = note.page?.let { it - 1 }?.takeIf { it in 0 until lastPageCount } ?: defaultPage
            suggestions.add(
                NoteSuggestion(
                    id = "note_" + Random.nextLong().toULong().toString(16),
                    kind = NoteKind.parse(note.kind),
                    title = title,
                    content = content,
                    pageIndex = pageIndex,
                    pageDbId = pageIndex?.let { lastPageDbIds.getOrNull(it) } ?: 0,
                    importance = note.importance.coerceIn(1, 3),
                    atMs = (startMs + (note.atSeconds * 1000).toLong()).coerceIn(startMs, maxOf(startMs, endMs)),
                    fromFinalReview = fromFinalReview
                )
            )
        }
    }

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    /** Keeps what a capture of the notebook tells about the document (pages, slides, notes). */
    internal fun rememberNotebook(snapshot: NotebookSnapshot) {
        lastPageCount = snapshot.pageCount
        lastPageDbIds = snapshot.pageDbIds
        snapshot.slides.forEach { slideDocuments[it.resourceId] = it }
        for (image in snapshot.pageImages) {
            val dbId = snapshot.pageDbIds.getOrNull(image.pageIndex) ?: 0
            val file = if (dbId > 0) "p$dbId.jpg" else "i${image.pageIndex}.jpg"
            if (reviewImageCache[file]?.contentEquals(image.jpeg) == true) continue
            if (!PlatformFiles.writeBytes("$directory/pages/$file", image.jpeg)) continue
            reviewImageCache[file] = image.jpeg
            reviewPages.remove(file)
            reviewPages[file] = ReviewPage(image.pageIndex, dbId, file, image.isCrop)
            while (reviewPages.size > MAX_REVIEW_PAGE_IMAGES) {
                val oldest = reviewPages.keys.first()
                reviewPages.remove(oldest)
                reviewImageCache.remove(oldest)
                PlatformFiles.delete("$directory/pages/$oldest")
            }
        }
    }

    /** The images of the notes kept for the review (read from the folder after a reload). */
    private fun reviewImages(): List<PageImage> = reviewPages.values.mapNotNull { page ->
        val bytes = reviewImageCache[page.file] ?: PlatformFiles.readBytes("$directory/pages/${page.file}") ?: return@mapNotNull null
        PageImage(page.pageIndex, bytes, page.isCrop)
    }

    /** Uploads (once per lecture) the PDF documents of [documents]. */
    internal suspend fun uploadSlides(apiKey: String, documents: List<SlideDocument>): List<UploadedSlides> {
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

    internal fun refusesMediaResolution(e: Throwable): Boolean =
        mediaResolution != null && e is GeminiException && e.statusCode == 400 &&
            e.message.orEmpty().contains("resolution", ignoreCase = true)

    // ==========================================================
    // Final review
    // ==========================================================

    internal suspend fun runFinalReview(provider: ReviewProvider) {
        val transcriptCopy = withContext(stateContext) { transcript.toList() }
        if (transcriptCopy.isEmpty() && recording.pcmBytes() < MIN_REVIEW_SECONDS * LECTURE_SAMPLE_RATE * 2L) return
        val settings = settingsStore.settings
        when (provider) {
            ReviewProvider.GEMINI -> runGeminiReview(settings, transcriptCopy)
            ReviewProvider.LOCAL -> runLocalReview(settings, transcriptCopy)
        }
    }

    /** Review by Gemini with the complete recording (the transcript only if it cannot be uploaded). */
    private suspend fun runGeminiReview(settings: AssistantSettings, transcriptCopy: List<TranscriptSegment>) {
        val apiKey = settingsStore.apiKey
        if (apiKey.isEmpty()) {
            setState {
                problem = LectureProblem.MissingApiKey
                reviewStatus = ReviewStatus.FAILED
            }
            return
        }
        setState {
            reviewStatus = ReviewStatus.RUNNING
            reviewProvider = ReviewProvider.GEMINI
            reviewModel = settings.model
        }
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
            val slides = if (settings.sendSlides) uploadSlides(apiKey, slideDocuments.values.toList()) else emptyList()
            val memory = memory(elapsedMs)
            fun request() = LecturePrompts.finalReviewRequest(
                settings = settings,
                documentTitle = documentTitle,
                memory = memory,
                transcript = transcriptCopy,
                pageImages = reviewImages(),
                slides = slides,
                audioFile = audioFile,
                mediaResolution = mediaResolution
            )
            val response = try {
                generateWithRetries(apiKey, settings.model, request(), attempts = MAX_ATTEMPTS_WHEN_STOPPED + 1)
            } catch (e: GeminiException) {
                if (!refusesMediaResolution(e)) throw e
                mediaResolution = null
                generateWithRetries(apiKey, settings.model, request(), attempts = MAX_ATTEMPTS_WHEN_STOPPED + 1)
            }
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

    /**
     * Review by the local model (Ollama, free): the transcript of the live analysis and the notes
     * of the student, no recording nor PDF. It can take several minutes; the model is unloaded
     * from the memory at the end.
     */
    private suspend fun runLocalReview(settings: AssistantSettings, transcriptCopy: List<TranscriptSegment>) {
        if (transcriptCopy.isEmpty()) {
            setState {
                problem = LectureProblem.NoTranscript
                reviewStatus = ReviewStatus.FAILED
            }
            return
        }
        setState {
            reviewStatus = ReviewStatus.RUNNING
            reviewProvider = ReviewProvider.LOCAL
            reviewModel = settings.localModel
            reviewProgressTokens = 0
        }
        try {
            val prompt = LecturePrompts.localReviewPrompt(
                settings = settings,
                documentTitle = documentTitle,
                memory = memory(elapsedMs),
                transcript = transcriptCopy,
                pageImages = reviewImages().takeLast(MAX_LOCAL_REVIEW_IMAGES)
            )
            val request = OllamaChatRequest(
                model = settings.localModel,
                messages = listOf(
                    OllamaMessage("system", prompt.system),
                    OllamaMessage("user", prompt.user, prompt.images.map { Base64.encode(it) }.ifEmpty { null })
                ),
                format = LectureAnalysis.finalReviewSchema,
                think = false,
                keepAlive = 0,
                options = OllamaOptions(numCtx = localContextSize(prompt), temperature = 0.4f)
            )
            val result = localClient.chat(settings.localUrl, request) { tokens ->
                // Written from the network thread: a snapshot state can be
                if (tokens % 8 == 0) reviewProgressTokens = tokens
            }
            val review = LectureAnalysis.parseFinalReview(result.content)
            setState {
                finalReview = review.notesMarkdown.trim().ifEmpty { null }
                addNotes(review.missing, 0L, elapsedMs, defaultPage = null, fromFinalReview = true)
                reviewProgressTokens = result.outputTokens.toInt()
                reviewStatus = ReviewStatus.DONE
                problem = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val detail = e.message ?: e::class.simpleName.orEmpty()
            setState {
                reviewStatus = ReviewStatus.FAILED
                problem = if (e is OllamaException && (e.statusCode == 0 || e.statusCode == 404)) {
                    LectureProblem.LocalModelUnavailable(detail)
                } else {
                    LectureProblem.Failed(detail)
                }
            }
        }
    }

    /** Context of the local model: the prompt (about 3 characters per token), the images and the answer. */
    private fun localContextSize(prompt: LocalPrompt): Int {
        val estimate = (prompt.system.length + prompt.user.length) / 3 + prompt.images.size * LOCAL_TOKENS_PER_IMAGE + LOCAL_ANSWER_TOKENS
        return ((estimate + 4095) / 4096 * 4096).coerceIn(8192, 65536)
    }

    internal suspend fun generateWithRetries(
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

    internal suspend fun generateContent(apiKey: String, model: String, request: GenerateContentRequest): GenerateContentResponse =
        client.generateContent(apiKey, model, request)

    // ==========================================================
    // Persistence
    // ==========================================================

    /** The saved form of the lecture. Reads the state: call it on [stateContext]. */
    internal fun toRecord(): LectureRecord = LectureRecord(
        id = id,
        documentId = documentId,
        documentTitle = documentTitle,
        startedAt = startedAt,
        durationMs = elapsedMs,
        status = status,
        outline = outline,
        currentTopic = currentTopic,
        transcript = transcript.toList(),
        suggestions = suggestions.toList(),
        finalReview = finalReview,
        finalReviewBy = reviewModel,
        finalReviewProvider = reviewProvider,
        usage = usage,
        reviewPages = reviewPages.values.toList(),
        slides = slideDocuments.values.toList()
    )

    /** Writes `lecture.json` (atomically). */
    internal suspend fun persist() {
        val record = withContext(stateContext) { toRecord() }
        persistMutex.withLock { withContext(Dispatchers.Default) { write(record) } }
    }

    /** Writes `lecture.json` from the calling thread (e.g. while the application exits). */
    internal fun persistNow() = write(toRecord())

    private fun write(record: LectureRecord) {
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

    internal fun GeminiUsage?.toTokenUsage(): TokenUsage = TokenUsage(
        requests = 1,
        promptTokens = this?.promptTokenCount ?: 0,
        cachedTokens = this?.cachedContentTokenCount ?: 0,
        outputTokens = (this?.candidatesTokenCount ?: 0) + (this?.thoughtsTokenCount ?: 0),
        promptByModality = this?.promptTokensDetails.orEmpty()
            .filter { it.modality.isNotEmpty() }
            .groupBy({ it.modality }, { it.tokenCount })
            .mapValues { (_, counts) -> counts.sum() }
    )

    internal companion object {
        const val MAX_ATTEMPTS_WHEN_STOPPED = 2
        const val MAX_SLIDES_BYTES = 45 * 1024 * 1024
        const val MAX_REVIEW_PAGE_IMAGES = 16
        const val MAX_LOCAL_REVIEW_IMAGES = 8
        const val LOCAL_TOKENS_PER_IMAGE = 600
        const val LOCAL_ANSWER_TOKENS = 8192
        const val MIN_REVIEW_SECONDS = 20
        const val PREVIOUS_TRANSCRIPT_CHARS = 600
        const val PLAYBACK_LEAD_MS = 3_000L
        const val PLAYBACK_DURATION_MS = 20_000L

        val recordJson = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
