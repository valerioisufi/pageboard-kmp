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
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.local.OllamaException
import io.github.valerioisufi.drawview.lecture.model.ModelAnswer
import io.github.valerioisufi.drawview.lecture.model.ModelBackend
import io.github.valerioisufi.drawview.lecture.model.ModelBackends
import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
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

    /** The audio cannot be transcribed on this computer (whisper.cpp missing or failing). */
    data class TranscriptionUnavailable(val detail: String) : LectureProblem
}

/**
 * A lecture of a document: what was recorded and generated (transcript, suggested notes and what
 * the student did with them, outline, final review), saved in its folder:
 * `lecture.json` (with the pages of the notebook seen, [SeenPage]), the audio ([RecordingFile])
 * and `pages/`, the last images of the visible pages, kept for the final review.
 *
 * A lecture outlives the editor of its document: it is recorded by a [LectureRecorder] (also
 * while the document is closed), loaded again by the [LectureLibrary], and can be listened to and
 * reviewed at any time. The final review is written by Gemini (with the complete recording when the
 * audio may leave the device) or by a local model (Ollama) with the transcript.
 *
 * Every observable property is Compose state, updated on [stateContext].
 */
class Lecture internal constructor(
    val documentId: Int,
    val directory: String,
    record: LectureRecord?,
    private val settingsStore: AssistantSettingsStore,
    internal val backends: ModelBackends,
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

    // Material of the review (the analysis coroutine of the recorder, then the review)
    private val seenPages = LinkedHashMap<String, SeenPage>()
    private val imageCache = mutableMapOf<String, ByteArray>()
    internal var lastPageCount = Int.MAX_VALUE
    internal var lastPageDbIds: List<Int> = emptyList()

    init {
        if (record != null) {
            // Saved while recording: the recording was interrupted (see LectureLibrary)
            status = LectureStatus.FINISHED
            record.pages.forEach { seenPages[pageKey(it.content.index, it.content.dbId)] = it }
            // The first versions kept the images only
            for (page in record.reviewPages) {
                seenPages.getOrPut(pageKey(page.pageIndex, page.pageDbId)) {
                    SeenPage(PageContent(page.pageIndex, page.pageDbId, hasInk = true), page.file)
                }
            }
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
    fun generateFinalReview(provider: ModelProvider = settingsStore.settings.reviewProvider) {
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

    /** Reads the state on [stateContext]. */
    internal suspend fun <T> setStateResult(block: Lecture.() -> T): T = withContext(stateContext) { block() }

    internal suspend fun memory(elapsed: Long): LectureMemory = withContext(stateContext) {
        LectureMemory(
            elapsedMs = elapsed,
            outline = outline,
            currentTopic = currentTopic,
            previousTranscript = transcript.takeLast(3).joinToString(" ") { it.text }.takeLast(PREVIOUS_TRANSCRIPT_CHARS),
            suggestions = suggestions.toList()
        )
    }

    /**
     * Adds the notes of an answer, skipping the ones already suggested (same title, or mostly the
     * same words: the small models repeat a note with another title). Runs on [stateContext].
     */
    internal fun addNotes(notes: List<AnalysisNote>, startMs: Long, endMs: Long, defaultPage: Int?, fromFinalReview: Boolean) {
        val known = suggestions.mapTo(mutableSetOf()) { normalize(it.title) }
        val knownWords = suggestions.mapTo(mutableListOf()) { contentWords(it.content) }
        for (note in notes) {
            val title = note.title.trim()
            val content = note.content.trim()
            if (content.isEmpty() || !known.add(normalize(title.ifEmpty { content }))) continue
            val words = contentWords(content)
            if (knownWords.any { repeats(words, it) }) continue
            knownWords.add(words)
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

    /** The words that tell the content of a note (not the short ones, as articles and prepositions). */
    private fun contentWords(text: String): Set<String> = buildSet {
        val word = StringBuilder()
        for (c in text.lowercase() + " ") {
            if (c.isLetterOrDigit()) {
                word.append(c)
            } else {
                if (word.length >= 4) add(word.toString())
                word.clear()
            }
        }
    }

    /** Most of the words of the shorter note are in the other one. */
    private fun repeats(words: Set<String>, known: Set<String>): Boolean {
        val shorter = minOf(words.size, known.size)
        return shorter >= MIN_WORDS_TO_COMPARE && words.count { it in known } >= shorter * REPEATED_WORDS
    }

    /**
     * Keeps what a capture of the notebook tells about the document: its pages (by database id,
     * the most recent last) and the images of the visible ones, for the final review.
     */
    internal fun rememberNotebook(snapshot: NotebookSnapshot) {
        lastPageCount = snapshot.pageCount
        lastPageDbIds = snapshot.pageDbIds
        val images = snapshot.pageImages.associateBy { it.pageIndex }
        // The farthest first: the visible pages end up the most recent
        for (content in snapshot.pages.asReversed()) {
            val key = pageKey(content.index, content.dbId)
            var file = seenPages[key]?.image
            images[content.index]?.let { image ->
                val name = "$key.jpg"
                if (imageCache[name]?.contentEquals(image.jpeg) == true || PlatformFiles.writeBytes("$directory/pages/$name", image.jpeg)) {
                    imageCache[name] = image.jpeg
                    file = name
                }
            }
            seenPages.remove(key)
            seenPages[key] = SeenPage(content, file)
        }
        // The images of the pages seen longest ago are dropped
        val withImage = seenPages.filterValues { it.image != null }.keys.toList()
        for (key in withImage.dropLast(MAX_PAGE_IMAGES)) {
            val file = seenPages.getValue(key).image ?: continue
            seenPages[key] = seenPages.getValue(key).copy(image = null)
            imageCache.remove(file)
            PlatformFiles.delete("$directory/pages/$file")
        }
        while (seenPages.size > MAX_SEEN_PAGES) seenPages.remove(seenPages.keys.first())
    }

    /** Updates the pages seen with [contents] (e.g. their handwriting transcribed), in place. */
    internal fun updateSeenPages(contents: List<PageContent>) {
        for (content in contents) {
            val key = pageKey(content.index, content.dbId)
            val seen = seenPages[key] ?: continue
            seenPages[key] = seen.copy(content = content)
        }
    }

    /** The pages seen during the lecture, as last seen. */
    internal fun seenPageContents(): List<PageContent> = seenPages.values.map { it.content }

    /** The key of a page: its database id, or its index when it is not saved yet. */
    private fun pageKey(index: Int, dbId: Int) = if (dbId > 0) "p$dbId" else "i$index"

    /** The last images of the pages seen, the most recent first (read from the folder after a reload). */
    private fun seenImages(max: Int): List<PageImage> = seenPages.values.reversed()
        .filter { it.image != null }
        .take(max)
        .mapNotNull { page ->
            val file = page.image ?: return@mapNotNull null
            val bytes = imageCache[file] ?: PlatformFiles.readBytes("$directory/pages/$file") ?: return@mapNotNull null
            PageImage(page.content.index, bytes)
        }

    // ==========================================================
    // Final review
    // ==========================================================

    internal suspend fun runFinalReview(provider: ModelProvider) {
        val transcriptCopy = withContext(stateContext) { transcript.toList() }
        if (transcriptCopy.isEmpty() && recording.pcmBytes() < MIN_REVIEW_SECONDS * LECTURE_SAMPLE_RATE * 2L) return
        val settings = settingsStore.settings
        val model = settings.task(LectureTask.FINAL_REVIEW).let { if (provider == ModelProvider.GEMINI) it.gemini else it.local }
        val apiKey = settingsStore.apiKey
        if (provider == ModelProvider.GEMINI && apiKey.isEmpty()) {
            setState {
                problem = LectureProblem.MissingApiKey
                reviewStatus = ReviewStatus.FAILED
            }
            return
        }
        // A local model reads the transcript only
        if (provider == ModelProvider.LOCAL && transcriptCopy.isEmpty()) {
            setState {
                problem = LectureProblem.NoTranscript
                reviewStatus = ReviewStatus.FAILED
            }
            return
        }
        setState {
            reviewStatus = ReviewStatus.RUNNING
            reviewProvider = provider
            reviewModel = model
            reviewProgressTokens = 0
        }
        try {
            val prompt = when (provider) {
                ModelProvider.GEMINI -> {
                    // The complete recording, when the audio may leave the device
                    val audioFile = if (!settings.sendsAudio) null else try {
                        recording.readWav()?.let { backends.gemini.client.uploadFile(apiKey, it, "audio/wav", "lecture-$id.wav") }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Review on the transcript only
                        println("DrawView: cannot upload the recording: ${e.message}")
                        null
                    }
                    LecturePrompts.finalReviewPrompt(
                        settings = settings,
                        documentTitle = documentTitle,
                        memory = memory(elapsedMs),
                        transcript = transcriptCopy,
                        pages = seenPages.values.map { it.content },
                        pageImages = seenImages(MAX_REVIEW_IMAGES),
                        audioFile = audioFile
                    )
                }
                ModelProvider.LOCAL -> LecturePrompts.finalReviewPrompt(
                    settings = settings,
                    documentTitle = documentTitle,
                    memory = memory(elapsedMs),
                    transcript = transcriptCopy,
                    pages = seenPages.values.map { it.content },
                    pageImages = seenImages(MAX_LOCAL_REVIEW_IMAGES)
                )
            }
            val answer = generateWithRetries(backends.of(provider), prompt, model) { tokens ->
                // Written from the network thread: a snapshot state can be
                if (tokens % 8 == 0) reviewProgressTokens = tokens
            }
            val review = LectureAnalysis.parseFinalReview(answer.text)
            setState {
                finalReview = review.notesMarkdown.trim().ifEmpty { null }
                addNotes(review.missing, 0L, elapsedMs, defaultPage = null, fromFinalReview = true)
                usage += answer.usage ?: TokenUsage(localRequests = 1)
                reviewStatus = ReviewStatus.DONE
                problem = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            setState {
                reviewStatus = ReviewStatus.FAILED
                problem = problemOf(e)
            }
        }
    }

    /** Answers [prompt], repeating the request after the temporary errors of Gemini. */
    private suspend fun generateWithRetries(
        backend: ModelBackend,
        prompt: ModelPrompt,
        model: String,
        onProgress: (Int) -> Unit
    ): ModelAnswer {
        var attempt = 0
        while (true) {
            try {
                return backend.generate(prompt, model, onProgress)
            } catch (e: GeminiException) {
                attempt++
                if (!e.isRetryable || attempt > MAX_ATTEMPTS_WHEN_STOPPED) throw e
                delay((5_000L shl attempt).coerceAtMost(30_000L))
            }
        }
    }

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
        pages = seenPages.values.toList()
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

    internal companion object {
        /** The problem shown for a failed request. */
        fun problemOf(e: Throwable): LectureProblem {
            val detail = e.message ?: e::class.simpleName.orEmpty()
            return when {
                e is GeminiException && e.isAuthenticationError -> LectureProblem.Authentication(detail)
                e is OllamaException && (e.statusCode == 0 || e.statusCode == 404) -> LectureProblem.LocalModelUnavailable(detail)
                else -> LectureProblem.Failed(detail)
            }
        }

        const val MAX_ATTEMPTS_WHEN_STOPPED = 2
        /** Pages kept for the review (their text), and those with their last image. */
        const val MAX_SEEN_PAGES = 120
        const val MAX_PAGE_IMAGES = 16
        const val MAX_REVIEW_IMAGES = 16
        const val MAX_LOCAL_REVIEW_IMAGES = 8
        const val MIN_REVIEW_SECONDS = 20
        const val PREVIOUS_TRANSCRIPT_CHARS = 600
        const val MIN_WORDS_TO_COMPARE = 6
        const val REPEATED_WORDS = 0.8
        const val PLAYBACK_LEAD_MS = 3_000L
        const val PLAYBACK_DURATION_MS = 20_000L

        val recordJson = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
