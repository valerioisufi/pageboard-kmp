package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.valerioisufi.drawview.lecture.audio.AudioOutput
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.audio.RecordingPlayer
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import io.github.valerioisufi.drawview.lecture.model.ModelAnswer
import io.github.valerioisufi.drawview.lecture.model.ModelBackends
import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
import io.github.valerioisufi.drawview.lecture.model.ModelUse
import io.github.valerioisufi.drawview.lecture.model.OllamaBackend
import io.github.valerioisufi.drawview.lecture.model.TaskBackend
import io.github.valerioisufi.drawview.platform.PlatformFiles
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

public enum class ReviewStatus { NONE, RUNNING, DONE, FAILED }

/** A problem of the assistant, shown in its panel. The recording goes on in any case. */
public sealed interface LectureProblem {
    public data object MissingApiKey : LectureProblem

    /** The key was refused (wrong key, API not enabled, model not allowed). */
    public data class Authentication(val detail: String) : LectureProblem

    /** A temporary error: the update is repeated in [retryInSeconds]. */
    public data class Retrying(val detail: String, val retryInSeconds: Int) : LectureProblem

    /** An update failed: its content will be covered by the final review. */
    public data class Failed(val detail: String) : LectureProblem

    /** The local model (Ollama) cannot be reached or is not installed. */
    public data class LocalModelUnavailable(val detail: String) : LectureProblem

    /** The local review needs the transcript of the live analysis, which is empty. */
    public data object NoTranscript : LectureProblem

    /** The audio cannot be transcribed on this computer (whisper.cpp missing or failing). */
    public data class TranscriptionUnavailable(val detail: String) : LectureProblem

    /**
     * The plan of Gemini allows no more requests to [model] now: none until the next day
     * ([daily]), or for a while; they come back in [retryInMs], when the API says.
     */
    public data class QuotaExhausted(val model: String, val daily: Boolean, val retryInMs: Long?) : LectureProblem
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
 * The devices working on the notebook have the lecture too ([LectureAttachments]): what they
 * change is merged into it ([merge]), as is what changes here into theirs.
 *
 * Every observable property is Compose state, updated on [stateContext].
 *
 * @param courseContext the course context of the notebook ([CourseContext]), read at every request.
 * @param generates whether this device writes what the models write (see
 *   [LectureAssistant.canGenerate]).
 * @param device this device ([LectureLibrary.deviceId]), [deviceName] its name.
 * @param recordsLock held while `lecture.json` is written ([LectureLibrary.records]).
 * @param onChanged called when something changed here: `lecture.json` written (with its id), or
 *   what the lectures of the notebook share (`null`, e.g. the handwriting read).
 */
public class Lecture internal constructor(
    public val documentId: String,
    public val directory: String,
    record: LectureRecord?,
    private val settingsStore: AssistantSettingsStore,
    internal val backends: ModelBackends,
    audioOutput: (() -> AudioOutput?)?,
    internal val stateContext: CoroutineContext = Dispatchers.Main,
    internal val courseContext: () -> String = { "" },
    startedAt: Long? = null,
    private val generatesHere: () -> Boolean = { true },
    internal val device: String = "",
    private val deviceName: String = "",
    private val recordsLock: Mutex = Mutex(),
    internal val onChanged: (lectureId: String?) -> Unit = {}
) {
    public val id: String = directory.trimEnd('/').substringAfterLast('/')
    public val startedAt: Long = record?.startedAt ?: startedAt ?: currentTimeMillis()

    /** The device that recorded it, and its name. */
    private val recordedBy: String = record?.recordedBy?.takeIf { it.isNotEmpty() } ?: device
    public val recordedOn: String = record?.recordedOn?.takeIf { it.isNotEmpty() } ?: deviceName

    /** Recorded on another device (it may still be recording it, its audio may not be here yet). */
    public val recordedElsewhere: Boolean get() = recordedBy.isNotEmpty() && recordedBy != device

    public var documentTitle: String by mutableStateOf(record?.documentTitle.orEmpty())
        internal set

    public var status: LectureStatus by mutableStateOf(LectureStatus.RECORDING)
        internal set

    /** Recorded audio. */
    public var elapsedMs: Long by mutableLongStateOf(record?.durationMs ?: 0L)
        internal set

    // --- Live state of the recording (see LectureRecorder) ---

    /** Input level of the microphone (0..1). */
    public var level: Float by mutableFloatStateOf(0f)
        internal set

    /** The microphone delivers only silence (typically: access not granted). */
    public var noAudioWarning: Boolean by mutableStateOf(false)
        internal set

    /** The system holds the microphone (a call, Siri): the recording goes on when it gives it back. */
    public var microphoneInterrupted: Boolean by mutableStateOf(false)
        internal set

    /** Chunks waiting to be analyzed (or being analyzed). */
    public var pendingUpdates: Int by mutableIntStateOf(0)
        internal set

    public var isAnalyzing: Boolean by mutableStateOf(false)
        internal set

    /** The step of an import ([LectureStatus.IMPORTING]). */
    public var importStep: ImportStep? by mutableStateOf<ImportStep?>(null)
        internal set

    /** Progress of the step of an import (0..1), -1 when unknown. */
    public var importProgress: Float by mutableFloatStateOf(-1f)
        internal set

    /** Whether the notebook of the lecture is open (the analysis sees the pages). */
    internal var isNotebookAttached by mutableStateOf(false)
        internal set

    // --- Generated content ---

    public var problem: LectureProblem? by mutableStateOf<LectureProblem?>(null)
        internal set

    public var outline: String by mutableStateOf(record?.outline.orEmpty())
        internal set

    public var currentTopic: String by mutableStateOf(record?.currentTopic.orEmpty())
        internal set

    /** Page the lecturer is talking about (last known). */
    public var professorPageIndex: Int? by mutableStateOf<Int?>(null)
        internal set

    public val suggestions: SnapshotStateList<NoteSuggestion> = mutableStateListOf<NoteSuggestion>().apply { record?.let { addAll(it.suggestions) } }
    public val transcript: SnapshotStateList<TranscriptSegment> = mutableStateListOf<TranscriptSegment>().apply { record?.let { addAll(it.transcript) } }

    /** What the lecturer is saying, transcribed live and still changing (not saved). */
    public var interimTranscript: String by mutableStateOf("")
        internal set

    public var finalReview: String? by mutableStateOf(record?.finalReview)
        private set

    public var reviewStatus: ReviewStatus by mutableStateOf(if (record?.finalReview != null) ReviewStatus.DONE else ReviewStatus.NONE)
        private set

    /** Model of the final review: the Gemini model or the local one. */
    public var reviewModel: String? by mutableStateOf(record?.finalReviewBy)
        private set

    /** Provider of the final review being written (or written). */
    public var reviewProvider: ModelProvider? by mutableStateOf(record?.finalReviewProvider)
        private set

    /** Tokens written so far by the local model. */
    public var reviewProgressTokens: Int by mutableIntStateOf(0)
        private set

    /** The tokens this device used for the lecture. */
    public var usage: TokenUsage by mutableStateOf(record?.usageByDevice?.get(device)?.usage ?: TokenUsage())
        internal set

    /** The tokens the other devices used for it, by device. */
    public var otherUsage: Map<String, DeviceUsage> by mutableStateOf(record?.usageByDevice?.minus(device).orEmpty())
        private set

    /** The tokens every device used for it. */
    public val totalUsage: TokenUsage get() = otherUsage.values.fold(usage) { total, other -> total + other.usage }

    /** The audio is here (a lecture recorded on another device gets it once finished there). */
    public var hasAudio: Boolean by mutableStateOf(record != null && RecordingFile(directory).exists)
        private set

    /** Whether its audio goes to the other devices working on the notebook. */
    public var audioShared: Boolean by mutableStateOf(record?.audioShared ?: true)
        internal set

    /** The device writing its transcript or review now (this one or another), if any. */
    public var generation: GenerationLock? by mutableStateOf(record?.generation)
        internal set

    /** When the device recording it was last heard of (its last change of the recording). */
    public var recordingHeardAt: Long by mutableLongStateOf(record?.stampOf(LecturePart.RECORDING)?.at ?: 0L)
        private set

    /** It was deleted on another device while recorded here: deleted here too once over. */
    public var deletedElsewhere: Boolean by mutableStateOf(false)
        internal set

    /** Recorded on another device and followed here live (see LectureRecorder.follows). */
    public var followedHere: Boolean by mutableStateOf(false)
        internal set

    /** The transcription or review running here, stopped when another device took it over (see [generation]). */
    internal var generating: Job? = null

    /** Another device writes its transcript or review now ([generation], still held). */
    public fun generatingElsewhere(now: Long): GenerationLock? = generation?.takeIf { it.device != device && it.holds(now) }


    /** Whether this device writes what the models write (now: it is a setting). */
    public val generates: Boolean get() = generatesHere()

    public val canGenerateReview: Boolean get() = generates && status == LectureStatus.FINISHED && reviewStatus != ReviewStatus.RUNNING

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val recording = RecordingFile(directory)

    /** Plays the recording, where the platform can (`null` elsewhere). */
    public val player: RecordingPlayer? = audioOutput?.let { RecordingPlayer(recording, it, stateContext) }

    public val canPlayAudio: Boolean get() = player != null

    /** The lecture as last saved or merged (the stamps of its parts: what changed since then is stamped when saved). */
    private var saved: LectureRecord? = record

    // Material of the review (the analysis coroutine of the recorder, then the review)
    private val seenPages = LinkedHashMap<String, SeenPage>()
    private val imageCache = mutableMapOf<String, ByteArray>()
    internal var lastPageCount = Int.MAX_VALUE
    internal var lastPageIds: List<String> = emptyList()

    init {
        if (record != null) {
            // Saved while recording here: the recording was interrupted (see LectureLibrary). Not
            // when another device is recording it
            status = if (record.status != LectureStatus.FINISHED && record.stampOf(LecturePart.RECORDING).by.let { it.isNotEmpty() && it != device }) {
                record.status
            } else {
                LectureStatus.FINISHED
            }
            record.pages.forEach { seenPages[pageKey(it.content.index, it.content.pageId)] = it }
        }
    }

    // ==========================================================
    // Actions
    // ==========================================================

    /** Records what the student did with a suggestion (sent back to the model). */
    public fun updateSuggestion(id: String, status: SuggestionStatus) {
        val index = suggestions.indexOfFirst { it.id == id }
        if (index < 0) return
        val suggestion = suggestions[index]
        suggestions[index] = suggestion.copy(status = status, statusStamp = (suggestion.statusStamp ?: Stamp.NONE).next(device, currentTimeMillis()))
        scope.launch { persist() }
    }

    /** Plays the recording from a little before the moment [suggestion] was said. */
    public fun play(suggestion: NoteSuggestion) {
        player?.play((suggestion.atMs - PLAYBACK_LEAD_MS).coerceAtLeast(0L))
    }

    internal fun stopPlayback() {
        player?.pause()
    }

    /**
     * Marks the final review as being written by [provider] (called on the state thread, before it
     * starts: a second request is refused); `false` when it cannot be written now.
     */
    internal fun claimReview(provider: ModelProvider): Boolean {
        if (!canGenerateReview) return false
        reviewStatus = ReviewStatus.RUNNING
        reviewProvider = provider
        return true
    }

    /** The review claimed did not run to its end (stopped): shown as it was. Called on the state thread. */
    internal fun releaseReviewClaim() {
        if (reviewStatus == ReviewStatus.RUNNING) reviewStatus = if (finalReview != null) ReviewStatus.DONE else ReviewStatus.NONE
    }

    // ==========================================================
    // Live updates (LectureRecorder)
    // ==========================================================

    internal suspend fun setState(block: Lecture.() -> Unit) = withContext(stateContext) { block() }

    /** Reads the state on [stateContext]. */
    internal suspend fun <T> setStateResult(block: Lecture.() -> T): T = withContext(stateContext) { block() }

    /** What the assistant remembers [elapsed] into the lecture, with the transcript said before [transcriptBeforeMs]. */
    internal suspend fun memory(elapsed: Long, transcriptBeforeMs: Long = Long.MAX_VALUE): LectureMemory = withContext(stateContext) {
        LectureMemory(
            elapsedMs = elapsed,
            outline = outline,
            currentTopic = currentTopic,
            previousTranscript = transcript.filter { it.startMs < transcriptBeforeMs }.takeLast(3).joinToString(" ") { it.text }.takeLast(PREVIOUS_TRANSCRIPT_CHARS),
            suggestions = suggestions.toList(),
            courseContext = courseContext()
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
                    pageId = pageIndex?.let { lastPageIds.getOrNull(it) }.orEmpty(),
                    importance = note.importance.coerceIn(1, 3),
                    atMs = (startMs + (note.atSeconds * 1000).toLong()).coerceIn(startMs, maxOf(startMs, endMs)),
                    fromFinalReview = fromFinalReview,
                    anchor = note.anchor.trim().take(MAX_ANCHOR_CHARS)
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
     * Keeps what a capture of the notebook tells about the document: its pages (by identifier,
     * the most recent last) and the images of the visible ones, for the final review.
     */
    internal fun rememberNotebook(snapshot: NotebookSnapshot) {
        lastPageCount = snapshot.pageCount
        lastPageIds = snapshot.pageIds
        val images = snapshot.pageImages.associateBy { it.pageIndex }
        // The farthest first: the visible pages end up the most recent
        for (content in snapshot.pages.asReversed()) {
            val key = pageKey(content.index, content.pageId)
            var file = seenPages[key]?.image
            var hash = seenPages[key]?.imageHash
            images[content.index]?.let { image ->
                val name = "$key.jpg"
                // Written whole: the other devices may be reading it
                if (imageCache[name]?.contentEquals(image.jpeg) == true || writeAtomically("$directory/pages/$name", image.jpeg)) {
                    if (imageCache[name]?.contentEquals(image.jpeg) != true) hash = contentHash(image.jpeg)
                    imageCache[name] = image.jpeg
                    file = name
                }
            }
            val visible = seenPages[key]?.visible == true || content.index in snapshot.visiblePageIndexes
            seenPages.remove(key)
            seenPages[key] = SeenPage(content, file, visible, hash)
        }
        // The images of the pages seen longest ago are dropped
        val withImage = seenPages.filterValues { it.image != null }.keys.toList()
        for (key in withImage.dropLast(MAX_PAGE_IMAGES)) {
            val file = seenPages.getValue(key).image ?: continue
            seenPages[key] = seenPages.getValue(key).copy(image = null, imageHash = null)
            imageCache.remove(file)
            PlatformFiles.delete("$directory/pages/$file")
        }
        while (seenPages.size > MAX_SEEN_PAGES) seenPages.remove(seenPages.keys.first())
    }

    /**
     * The pages of the notebook of a lecture recorded elsewhere: what the student was looking at
     * is not known, so none is marked as on screen.
     */
    internal fun setNotebookPages(contents: List<PageContent>, pageIds: List<String>) {
        lastPageCount = contents.size
        lastPageIds = pageIds
        seenPages.clear()
        for (content in contents) seenPages[pageKey(content.index, content.pageId)] = SeenPage(content)
    }

    /** Updates the pages seen with [contents] (e.g. their handwriting transcribed), in place. */
    internal fun updateSeenPages(contents: List<PageContent>) {
        for (content in contents) {
            val key = pageKey(content.index, content.pageId)
            val seen = seenPages[key] ?: continue
            seenPages[key] = seen.copy(content = content)
        }
    }

    private fun visibleSeenPages(): Set<Int> = seenPages.values.filter { it.visible }.mapTo(mutableSetOf()) { it.content.index }

    /** The pages seen during the lecture, as last seen. */
    internal fun seenPageContents(): List<PageContent> = seenPages.values.map { it.content }

    /** The key of a page: its identifier, or its index when it is not known. */
    private fun pageKey(index: Int, pageId: String) = if (pageId.isNotEmpty()) "p$pageId" else "i$index"

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

    /**
     * Writes the final review by [provider]: [prepare] completes its material first (e.g. the
     * handwriting not transcribed yet), while the review is shown as running.
     */
    internal suspend fun runFinalReview(provider: ModelProvider, prepare: suspend () -> Unit = {}) {
        val transcriptCopy = withContext(stateContext) { transcript.toList() }
        if (transcriptCopy.isEmpty() && recording.samples() < MIN_REVIEW_SECONDS * LECTURE_SAMPLE_RATE.toLong()) {
            // Nothing to review (a few seconds of silence)
            setState { reviewStatus = if (finalReview != null) ReviewStatus.DONE else ReviewStatus.NONE }
            return
        }
        val settings = settingsStore.settings
        val task = backends.forReview(settings, provider)
        task.unavailable()?.let { unavailable ->
            setState {
                problem = unavailable
                reviewStatus = ReviewStatus.FAILED
            }
            return
        }
        // The review reads the transcript only: the recording never leaves the device
        if (transcriptCopy.isEmpty()) {
            setState {
                problem = LectureProblem.NoTranscript
                reviewStatus = ReviewStatus.FAILED
            }
            return
        }
        setState {
            reviewStatus = ReviewStatus.RUNNING
            reviewProvider = provider
            reviewModel = task.model
            reviewProgressTokens = 0
        }
        try {
            prepare()
            val prompt = LecturePrompts.finalReviewPrompt(
                settings = settings,
                documentTitle = documentTitle,
                memory = memory(elapsedMs),
                transcript = transcriptCopy,
                pages = seenPages.values.map { it.content },
                pageImages = seenImages(task.limits.reviewImages),
                visiblePages = visibleSeenPages()
            )
            val answer = generateWithRetries(task, prompt) { tokens ->
                // Written from the network thread: a snapshot state can be
                if (tokens % 8 == 0) reviewProgressTokens = tokens
            }
            val review = LectureAnalysis.parseFinalReview(answer.text)
            setState {
                finalReview = review.notesMarkdown.trim().ifEmpty { null }
                addNotes(review.missing, 0L, elapsedMs, defaultPage = null, fromFinalReview = true)
                usage += answer.usageOrLocal
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

    /** Answers [prompt], repeating the request after the temporary errors of its backend. */
    private suspend fun generateWithRetries(task: TaskBackend, prompt: ModelPrompt, onProgress: (Int) -> Unit): ModelAnswer {
        var attempt = 0
        while (true) {
            try {
                return task.generate(prompt, ModelUse.ONCE, onProgress = onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (task.backend.isRetryable(e) != true || attempt > MAX_ATTEMPTS_WHEN_STOPPED) throw e
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
        usageByDevice = if (usage == TokenUsage() && device !in saved?.usageByDevice.orEmpty()) otherUsage else otherUsage + (device to DeviceUsage(deviceName, usage, saved?.usageByDevice?.get(device)?.stamp ?: Stamp.NONE)),
        pages = seenPages.values.toList(),
        recordedBy = recordedBy,
        recordedOn = recordedOn,
        audioShared = audioShared,
        generation = generation,
        stamps = saved?.stamps.orEmpty()
    )

    /** Writes `lecture.json` (atomically), what changed since it was last written stamped as changed here. */
    internal suspend fun persist() {
        recordsLock.withLock {
            val record = withContext(stateContext) { LectureRecords.stamped(toRecord(), saved, device, currentTimeMillis()) }
            withContext(Dispatchers.Default) { writeLectureRecord(directory, record) }
            saved = record
        }
        onChanged(id)
    }

    /** Writes `lecture.json` from the calling thread (e.g. while the application exits). */
    internal fun persistNow() {
        val record = LectureRecords.stamped(toRecord(), saved, device, currentTimeMillis())
        writeLectureRecord(directory, record)
        saved = record
        onChanged(id)
    }

    /**
     * Merges [theirs] (this lecture as another device has it) into this one, and saves it: the
     * lecture kept. Called holding [recordsLock].
     */
    internal suspend fun merge(theirs: LectureRecord): LectureRecord {
        val now = currentTimeMillis()
        val merged = withContext(stateContext) {
            val mine = LectureRecords.stamped(toRecord(), saved, device, now)
            LectureRecords.merge(mine, theirs).also { showMerged(it, mine) }
        }
        withContext(Dispatchers.Default) { writeLectureRecord(directory, merged) }
        saved = merged
        return merged
    }

    /** Shows what [merged] changed of [mine] (this lecture as it was). Runs on [stateContext]. */
    private fun showMerged(merged: LectureRecord, mine: LectureRecord) {
        documentTitle = merged.documentTitle
        if (merged.status != mine.status || merged.durationMs != mine.durationMs) {
            status = merged.status
            elapsedMs = merged.durationMs
        }
        if (merged.pages != mine.pages) {
            seenPages.clear()
            merged.pages.forEach { seenPages[pageKey(it.content.index, it.content.pageId)] = it }
        }
        outline = merged.outline
        currentTopic = merged.currentTopic
        if (merged.transcript != mine.transcript) {
            transcript.clear()
            transcript.addAll(merged.transcript)
        }
        if (merged.suggestions != mine.suggestions) {
            suggestions.clear()
            suggestions.addAll(merged.suggestions)
        }
        if (merged.finalReview != mine.finalReview || merged.finalReviewBy != mine.finalReviewBy) {
            finalReview = merged.finalReview
            reviewModel = merged.finalReviewBy
            reviewProvider = merged.finalReviewProvider
            if (reviewStatus != ReviewStatus.RUNNING) reviewStatus = if (merged.finalReview != null) ReviewStatus.DONE else ReviewStatus.NONE
        }
        otherUsage = merged.usageByDevice - device
        merged.usageByDevice[device]?.let { usage = it.usage }
        audioShared = merged.audioShared
        recordingHeardAt = merged.stampOf(LecturePart.RECORDING).at
        if (merged.generation != generation) {
            generation = merged.generation
            // Another device took the transcription or the review over (at the same moment): left to it
            if (merged.generation?.device != device) generating?.cancel()
        }
    }

    /** More audio was recorded: offered to the other devices (see LectureAttachments). */
    internal fun audioGrew() = onChanged(id)

    /** Its transcription waits for a recording to end ([paused]), or goes on after it. */
    internal suspend fun transcriptionPaused(paused: Boolean) = setState {
        if (status == LectureStatus.IMPORTING) importStep = if (paused) ImportStep.WAITING else ImportStep.TRANSCRIBING
    }

    /** Its audio is here now: received from the device that recorded it, or converted from a file imported. */
    internal suspend fun audioReceived() = setState {
        hasAudio = true
        player?.refresh()
    }

    internal companion object {
        /** The problem shown for a failed request. */
        fun problemOf(e: Throwable): LectureProblem {
            return GeminiBackend.problemOf(e) ?: OllamaBackend.problemOf(e) ?: LectureProblem.Failed(e.message ?: e::class.simpleName.orEmpty())
        }

        const val MAX_ATTEMPTS_WHEN_STOPPED = 2
        /** Pages kept for the review (their text), and those with their last image. */
        const val MAX_SEEN_PAGES = 120
        const val MAX_PAGE_IMAGES = 16
        const val MAX_REVIEW_IMAGES = 16
        const val MAX_LOCAL_REVIEW_IMAGES = 8
        const val MIN_REVIEW_SECONDS = 20
        const val PREVIOUS_TRANSCRIPT_CHARS = 300

        /** The longest anchor kept (a few words are asked: a longer one is cut). */
        const val MAX_ANCHOR_CHARS = 120

        const val MIN_WORDS_TO_COMPARE = 6
        const val REPEATED_WORDS = 0.8
        const val PLAYBACK_LEAD_MS = 3_000L

        val recordJson = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
