package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.AudioOutput
import io.github.valerioisufi.drawview.lecture.audio.FollowedRecording
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.audio.createAudioCapture
import io.github.valerioisufi.drawview.lecture.audio.createAudioOutput
import io.github.valerioisufi.drawview.lecture.audio.exportRecording
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.handwriting.HandwritingReader
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import io.github.valerioisufi.drawview.lecture.model.ModelBackends
import io.github.valerioisufi.drawview.lecture.model.OllamaBackend
import io.github.valerioisufi.drawview.lecture.platform.ChosenFile
import io.github.valerioisufi.drawview.lecture.platform.showNotification
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import io.github.valerioisufi.drawview.lecture.transcription.Transcribers
import io.github.valerioisufi.drawview.lecture.transcription.WhisperTranscriber
import io.github.valerioisufi.drawview.model.DocumentAttachments
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import io.github.valerioisufi.drawview.platform.syncLock
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The lecture assistant of an application.
 *
 * It lives as long as the application: one lecture is recorded at a time ([recorder]), also while
 * its document is closed, and the lectures of every document are kept by the [library] and shown
 * again when the document is opened ([lecturesOf], [shownLecture]).
 *
 * Every notebook has its course context ([CourseContext]), written by the assistant from the
 * notebook when its first lecture starts ([contextOf], [writeContext], [editContext]).
 *
 * The editors show it through [io.github.valerioisufi.drawview.lecture.ui.editorExtension], the
 * document library through [io.github.valerioisufi.drawview.lecture.ui.LectureRecordingIndicator].
 *
 * The devices working on a notebook share its lectures ([attachments], for the synchronization):
 * a lecture recorded on a device without the models is transcribed and reviewed where they are
 * ([generate]).
 *
 * @param canGenerate whether the models may follow the lectures here (the live notes, the review,
 *   the course context), as [AssistantSettings.generateHere] chooses: elsewhere the lectures are
 *   recorded only.
 */
public class LectureAssistant(
    public val settingsStore: AssistantSettingsStore = AssistantSettingsStore(),
    private val client: GeminiClient = GeminiClient(),
    private val localClient: OllamaClient = OllamaClient(),
    private val captureFactory: () -> AudioCapture? = ::createAudioCapture,
    private val audioOutput: (() -> AudioOutput?)? = ::createAudioOutput.takeIf { createAudioOutput() != null },
    public val library: LectureLibrary = LectureLibrary(),
    private val stateContext: CoroutineContext = Dispatchers.Main,
    private val whisper: (AssistantSettings) -> Transcriber = { WhisperTranscriber.of(it) },
    canGenerate: Boolean = true
) {
    private val generationAllowed = canGenerate

    /** Whether the models follow the lectures here (see [AssistantSettings.generateHere]). */
    public val canGenerate: Boolean get() = generationAllowed && settingsStore.settings.generateHere

    /** Whether the models may follow the lectures here, when the student chooses it ([AssistantSettings.generateHere]). */
    public val mayGenerate: Boolean get() = generationAllowed

    private val backends = ModelBackends(
        gemini = GeminiBackend(client) { settingsStore.apiKey },
        local = OllamaBackend(localClient) { settingsStore.settings.localUrl }
    )

    /** The speech recognition chosen in the settings. */
    private val transcribers = Transcribers(backends.gemini, whisper)

    /** The models on this computer (Ollama, whisper.cpp), for the settings. */
    public val localModels: LocalModels = LocalModels(settingsStore, localClient, stateContext = stateContext)

    /** The last recording (being recorded, being reviewed, or finished). */
    public var recorder: LectureRecorder? by mutableStateOf(null)
        private set

    public var isPanelOpen: Boolean by mutableStateOf(false)

    /** The suggestion the student is placing in the notebook, if any. */
    public var placingNote: NoteSuggestion? by mutableStateOf(null)

    /** The lecture being imported, if any. */
    public var importing: Lecture? by mutableStateOf(null)
        private set

    /** Why the last import failed (the file is not audio), if it did. */
    public var importError: String? by mutableStateOf(null)
        private set

    /** The last export of an audio. */
    public var exportResult: ExportResult? by mutableStateOf(null)
        private set

    /** Why the last recording could not start, if it could not. */
    public var startError: String? by mutableStateOf(null)
        private set

    /** The last recording could not start: the microphone is not allowed. */
    public var microphoneDenied: Boolean by mutableStateOf(false)
        private set

    /** Asks for the permission to use the microphone (set by [io.github.valerioisufi.drawview.lecture.ui.rememberLectureAssistant]). */
    public var requestMicrophone: suspend () -> Boolean = { true }

    /** Whether the platform can record (the desktop, for now). */
    public val isRecordingSupported: Boolean by lazy { captureFactory() != null }

    /** The recording not finished yet (recording or reviewing), if any. */
    public val activeRecorder: LectureRecorder? get() = recorder?.takeIf { it.lecture.status != LectureStatus.FINISHED }

    public val isRecording: Boolean get() = recorder?.isRecording == true

    /** The lectures recorded on other devices followed here live, by folder (see [LectureRecorder]). */
    private val followers = mutableStateMapOf<String, LectureRecorder>()

    private val lectureLists = mutableStateMapOf<String, List<LectureSummary>>()
    private val shownLectures = mutableStateMapOf<String, Lecture>()
    private val contexts = mutableStateMapOf<String, CourseContext>()
    private val writingContexts = mutableStateMapOf<String, Boolean>()
    private val editorNotebooks = mutableMapOf<String, NotebookSource>()
    private val handwritingReaders = mutableMapOf<String, HandwritingReader>()
    private val handwritingLock = SyncLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lectureAttachments: LectureAttachments by lazy { LectureAttachments(sharing, scope) }

    /** The lectures of the notebooks as items beside them, for the synchronization with the other devices. */
    public val attachments: DocumentAttachments get() = lectureAttachments

    private val sharingSettings = mutableStateMapOf<String, LectureSharingSettings>()

    /** What arrived from the other devices and was not looked at yet (see [dismissArrival]). */
    public val arrivals: SnapshotStateList<LectureArrival> = mutableStateListOf<LectureArrival>()

    /**
     * The lectures received without a transcript, waiting for the user to have them transcribed and
     * reviewed here (see [AssistantSettings.receivedLectures]).
     */
    public val generationOffers: SnapshotStateList<GenerationOffer> = mutableStateListOf<GenerationOffer>()

    private val sharing = object : LectureAssistantLibrary {
        override val files get() = library

        override suspend fun loadedLecture(directory: String): Lecture? = withContext(stateContext) {
            listOfNotNull(recorder?.lecture, importing).plus(followers.values.map { it.lecture }).plus(shownLectures.values)
                .firstOrNull { it.directory == directory }
        }

        override suspend fun isActiveHere(directory: String): Boolean = withContext(stateContext) {
            activeRecorder?.lecture?.directory == directory || importing?.directory == directory
        }

        override suspend fun onReceived(documentId: String) = refreshNow(documentId)

        override suspend fun onContextReceived(documentId: String, context: CourseContext) = withContext(stateContext) {
            contexts[documentId] = context
        }

        override suspend fun onDeleted(documentId: String, directory: String) = withContext(stateContext) {
            val shown = shownLectures[documentId]
            if (shown?.directory == directory) {
                shown.stopPlayback()
                shownLectures.remove(documentId)
            }
        }

        override suspend fun onArrival(arrival: LectureArrival) {
            withContext(stateContext) {
                arrivals.removeAll { it.directory == arrival.directory && it.kind == arrival.kind }
                arrivals.add(arrival)
            }
            notifyArrival(arrival)
            if (arrival.kind != LectureArrival.Kind.REVIEW) offerGeneration(arrival)
        }
    }

    /**
     * The notification of the system telling what arrived in the document titled `documentTitle`
     * (in the language of the application: set by its UI), `null` for none.
     */
    public var describeArrival: suspend (arrival: LectureArrival, documentTitle: String) -> ArrivalNotification? = { _, _ -> null }

    /** Tells the user (with a notification of the system) what arrived. */
    private suspend fun notifyArrival(arrival: LectureArrival) {
        val title = library.list(arrival.documentId).firstOrNull()?.documentTitle.orEmpty()
        val notification = describeArrival(arrival, title) ?: return
        showNotification(notification.channel, notification.title, notification.text)
    }

    /** How this device shares the lectures of [documentId] (loaded by [refresh]). */
    public fun sharingOf(documentId: String): LectureSharingSettings = sharingSettings[documentId] ?: LectureSharingSettings()

    /** Changes how this device shares the lectures of [documentId] by [transform]. */
    public fun changeSharing(documentId: String, transform: (LectureSharingSettings) -> LectureSharingSettings) {
        val settings = transform(sharingOf(documentId))
        sharingSettings[documentId] = settings
        scope.launch {
            library.saveSharing(documentId, settings)
            // Maybe wanted now (e.g. received at once from now on)
            lectureAttachments.audioRequested(documentId)
        }
    }

    /** Whether the audio of [lecture] goes to the other devices (any device decides it, the most recent decision wins). */
    public fun shareAudio(lecture: Lecture, shared: Boolean) {
        lecture.audioShared = shared
        lecture.scope.launch { lecture.persist() }
    }

    /** Receives the audio of [lecture] (recorded on another device) now, even if received only on request. */
    public fun requestAudio(lecture: Lecture) {
        changeSharing(lecture.documentId) { it.copy(requestedAudio = it.requestedAudio + lecture.id) }
    }

    /** How much of the audio of [lecture] arrived (0..1), while it arrives. */
    internal fun audioProgress(lecture: Lecture): Float? = lectureAttachments.audioProgress.value[lecture.directory]

    /** The audio arriving, by lecture folder (to follow its progress). */
    public val audioArriving: StateFlow<Map<String, Float>> get() = lectureAttachments.audioProgress

    public fun dismissArrival(arrival: LectureArrival) {
        arrivals.remove(arrival)
    }

    /** Shows the lecture of [arrival] in the panel. */
    public fun showArrival(arrival: LectureArrival) {
        isPanelOpen = true
        scope.launch {
            val summary = library.list(arrival.documentId).firstOrNull { it.directory == arrival.directory } ?: return@launch
            withContext(stateContext) {
                if (shownLectures[arrival.documentId]?.directory != summary.directory) shownLectures.remove(arrival.documentId)
                show(arrival.documentId, summary)
            }
        }
    }

    /**
     * A lecture received from another device (with its audio, without a transcript): transcribed
     * and reviewed here at once, or offered to the user with what it would cost (see
     * [AssistantSettings.receivedLectures]).
     */
    private suspend fun offerGeneration(arrival: LectureArrival) {
        if (!canGenerate) return
        val choice = settingsStore.settings.receivedLectures
        if (choice == ReceivedLectures.NEVER) return
        val record = withContext(Dispatchers.Default) { library.readRecord(arrival.directory) } ?: return
        if (record.generation?.takeIf { it.device != library.deviceId }?.holds(currentTimeMillis()) == true) return
        val provider = settingsStore.settings.reviewProvider
        // Being recorded, its audio shared as it is: followed live
        if (record.status != LectureStatus.FINISHED) {
            if (arrival.kind != LectureArrival.Kind.LECTURE || !record.audioShared) return
            val offer = GenerationOffer(arrival, durationMs = 0, provider, live = true)
            if (choice == ReceivedLectures.ALWAYS) follow(offer) else withContext(stateContext) {
                if (generationOffers.none { it.arrival.directory == arrival.directory }) generationOffers.add(offer)
            }
            return
        }
        val audioHere = RecordingFile(arrival.directory).exists
        if (record.transcript.isNotEmpty() || record.finalReview != null || !audioHere) return
        val offer = GenerationOffer(arrival, record.durationMs, provider)
        if (choice == ReceivedLectures.ALWAYS) {
            generateReceived(offer)
        } else {
            withContext(stateContext) {
                if (generationOffers.none { it.arrival.directory == arrival.directory }) generationOffers.add(offer)
            }
        }
    }

    /** Transcribes and reviews the lecture of [offer] here (the user accepted it, or always does). */
    public fun acceptGeneration(offer: GenerationOffer) {
        generationOffers.remove(offer)
        scope.launch { if (offer.live) follow(offer) else generateReceived(offer) }
    }

    public fun declineGeneration(offer: GenerationOffer) {
        generationOffers.remove(offer)
    }

    private suspend fun generateReceived(offer: GenerationOffer) {
        val lecture = lectureIn(offer.arrival.documentId, offer.arrival.directory) ?: return
        withContext(stateContext) { generate(lecture, offer.provider) }
    }

    /** The lecture of [documentId] in [directory], shown (loaded if it is not). */
    private suspend fun lectureIn(documentId: String, directory: String): Lecture? {
        withContext(stateContext) { shownLectures[documentId]?.takeIf { it.directory == directory } }?.let { return it }
        return library.records.withLock {
            val record = library.load(directory) ?: return null
            newLecture(documentId, directory, record).also { lecture -> withContext(stateContext) { shownLectures[documentId] = lecture } }
        }
    }

    /**
     * Follows live the lecture of [offer], being recorded on another device: its audio, arriving as
     * it is recorded, analyzed here as if it were recorded here (the notes shared back as they are
     * written), its review written once it is over. The other devices leave it to this one
     * ([GenerationLock]).
     */
    private suspend fun follow(offer: GenerationOffer) {
        val documentId = offer.arrival.documentId
        val directory = offer.arrival.directory
        if (!canGenerate || withContext(stateContext) { directory in followers }) return
        val lecture = lectureIn(documentId, directory) ?: return
        // Over meanwhile: reviewed as the lectures received whole
        if (lecture.status == LectureStatus.FINISHED) return offerGeneration(offer.arrival.copy(kind = LectureArrival.Kind.AUDIO))
        withContext(stateContext) {
            // Its audio, also when it is downloaded on request only
            if (sharingOf(documentId).downloadAudio == AudioDownload.ON_REQUEST) requestAudio(lecture)
        }
        val settings = settingsStore.settings
        val transcriber = transcribers.forUpdates(settings)
        val ended = CompletableDeferred<Unit>()
        lateinit var follower: LectureRecorder
        val capture = FollowedRecording(
            recording = lecture.recording,
            // Recorded whole there, and arrived whole here (as long as it is)
            isOver = { samples -> lecture.deletedElsewhere || (lecture.status == LectureStatus.FINISHED && samplesToMs(samples) >= lecture.elapsedMs - FOLLOW_END_TOLERANCE_MS) },
            onEnd = { if (lecture.deletedElsewhere) scope.launch { follower.abandon() } else follower.stop() }
        )
        follower = LectureRecorder(
            lecture, settingsStore, capture, editorNotebooks[documentId], transcriber, stream = transcribers.forStreaming(settings),
            analyzes = true, follows = true, handwriting = handwritingOf(documentId), onFinished = { ended.complete(Unit) }
        )
        withContext(stateContext) {
            followers[directory] = follower
            lecture.followedHere = true
        }
        lecture.generating = scope.launch {
            try {
                holdingGeneration(lecture) {
                    follower.start()
                    ended.await()
                }
            } finally {
                withContext(NonCancellable) {
                    // Taken over by another device, or deleted: nothing more written here
                    follower.abandon()
                    withContext(stateContext) {
                        followers.remove(directory)
                        lecture.followedHere = false
                    }
                }
                refresh(documentId)
            }
        }
    }

    /** The documents with a lecture (the badge and the filter of the library, see [documentTag]). */
    public var documentsWithLectures: Set<String> by mutableStateOf(emptySet())
        private set

    init {
        LectureRecordings.register(this)
        scope.launch {
            val documents = withContext(Dispatchers.Default) { library.documentsWithLectures() }
            withContext(stateContext) { documentsWithLectures = documentsWithLectures + documents }
        }
    }

    /** The lectures of [documentId] (loaded by [refresh]), the most recent first. */
    public fun lecturesOf(documentId: String): List<LectureSummary> = lectureLists[documentId].orEmpty()

    /** The lecture shown in the panel of [documentId]: the one being recorded, or the last one. */
    public fun shownLecture(documentId: String): Lecture? = shownLectures[documentId]

    /** The recorder of [documentId], if it is the one being recorded or reviewed. */
    public fun recorderOf(documentId: String): LectureRecorder? = activeRecorder?.takeIf { it.lecture.documentId == documentId }

    /**
     * The editor of [documentId] is shown: the recording of the document (if any) sees its pages
     * again, and its lectures are loaded.
     */
    public fun onEditorShown(documentId: String, notebook: NotebookSource) {
        editorNotebooks[documentId] = notebook
        recorderOf(documentId)?.attachNotebook(notebook)
        followers.values.filter { it.lecture.documentId == documentId }.forEach { it.attachNotebook(notebook) }
        refresh(documentId)
    }

    /** The editor of [documentId] is closed: the recording goes on, without its pages. */
    public fun onEditorHidden(documentId: String, notebook: NotebookSource) {
        if (editorNotebooks[documentId] === notebook) editorNotebooks.remove(documentId)
        recorderOf(documentId)?.detachNotebook(notebook)
        followers.values.filter { it.lecture.documentId == documentId }.forEach { it.detachNotebook(notebook) }
        placingNote = null
        shownLectures[documentId]?.stopPlayback()
    }

    /** The course context of [documentId] (loaded by [refresh]), if any. */
    public fun contextOf(documentId: String): CourseContext? = contexts[documentId]

    /** The course context of [documentId] is being written. */
    public fun isWritingContext(documentId: String): Boolean = writingContexts[documentId] == true

    /** Whether the context of [documentId] can be written: its notebook is open (and the models are here). */
    public fun canWriteContext(documentId: String): Boolean = canGenerate && documentId in editorNotebooks

    /** The student changed the course context of [documentId]: it is not replaced automatically. */
    public fun editContext(documentId: String, text: String) {
        val previous = contexts[documentId]
        val context = CourseContext(text.trim(), edited = true, generatedAt = previous?.generatedAt ?: 0L, stamp = nextContextStamp(previous))
        contexts[documentId] = context
        scope.launch { library.saveContext(documentId, context) }
    }

    /**
     * Writes the course context of [documentId] from its notebook (open), with the model of the
     * live suggestions; [onlyIfMissing]: not when the notebook has one already.
     */
    public fun writeContext(documentId: String, onlyIfMissing: Boolean = false) {
        if (!canGenerate) return
        val notebook = editorNotebooks[documentId] ?: return
        if (isWritingContext(documentId)) return
        writingContexts[documentId] = true
        scope.launch { writeContextNow(documentId, notebook, onlyIfMissing) }
    }

    /** Writes the course context of [documentId] from [notebook] (see [writeContext]), waiting for it. */
    private suspend fun writeContextNow(documentId: String, notebook: NotebookSource, onlyIfMissing: Boolean) {
        withContext(stateContext) { writingContexts[documentId] = true }
        val text = try {
            if (onlyIfMissing && library.loadContext(documentId) != null) null else composeContext(documentId, notebook)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("DrawView: cannot write the course context: ${e.message}")
            null
        }
        val context = text?.takeIf { it.isNotBlank() }?.let {
            CourseContext(it, edited = false, generatedAt = currentTimeMillis(), stamp = nextContextStamp(library.loadContext(documentId)))
        }
        if (context != null) library.saveContext(documentId, context)
        withContext(stateContext) {
            writingContexts.remove(documentId)
            if (context != null) contexts[documentId] = context
        }
    }

    /** The stamp of a course context written here after [previous]. */
    private fun nextContextStamp(previous: CourseContext?) = (previous?.stamp ?: Stamp.NONE).next(library.deviceId, currentTimeMillis())

    /** The course context written by the model of the live suggestions, from the whole notebook. */
    private suspend fun composeContext(documentId: String, notebook: NotebookSource): String? {
        val task = settingsStore.settings.task(LectureTask.LIVE_NOTES)
        if (task.provider == ModelProvider.GEMINI && !settingsStore.hasApiKey) return null
        val pages = notebook.allPages()
        if (pages.isEmpty()) return null
        // With the handwriting already transcribed
        val reader = handwritingOf(documentId)
        val blocks = notebook.inkBlocks(pages.filter { it.hasInk }.map { it.index })
        val filled = pages.map { page -> reader.fill(page, blocks.filter { it.pageIndex == page.index }) }
        val prompt = LecturePrompts.courseContextPrompt(settingsStore.settings, notebook.documentTitle, filled)
        val answer = when (task.provider) {
            ModelProvider.GEMINI -> backends.gemini.generate(prompt, task.gemini)
            ModelProvider.LOCAL -> backends.local.generate(prompt, task.local, OllamaBackend.LIVE_KEEP_LOADED_SECONDS) {}
        }
        return LectureAnalysis.parseCourseContext(answer.text)
    }

    /** Lists the lectures of [documentId] again, showing the last one when none is shown. */
    public fun refresh(documentId: String) {
        scope.launch { refreshNow(documentId) }
    }

    private suspend fun refreshNow(documentId: String) {
        val lectures = library.list(documentId)
        val context = library.loadContext(documentId)
        val sharing = library.sharingOf(documentId)
        withContext(stateContext) {
            sharingSettings[documentId] = sharing
            if (context != null && contexts[documentId] == null) contexts[documentId] = context
            lectureLists[documentId] = lectures
            documentsWithLectures = if (lectures.isEmpty()) documentsWithLectures - documentId else documentsWithLectures + documentId
            if (shownLectures[documentId] == null) lectures.firstOrNull()?.let { show(documentId, it) }
        }
    }

    /** Shows the lecture [summary] in the panel of [documentId]. */
    public fun show(documentId: String, summary: LectureSummary) {
        val active = importing?.takeIf { it.directory == summary.directory } ?: recorder?.lecture
        if (active != null && active.directory == summary.directory) {
            shownLectures[documentId] = active
            return
        }
        if (shownLectures[documentId]?.directory == summary.directory) return
        scope.launch {
            // Shown before what another device sends is merged into it (see LectureAttachments)
            library.records.withLock {
                val record = library.load(summary.directory) ?: return@launch
                val lecture = newLecture(documentId, summary.directory, record)
                withContext(stateContext) { shownLectures[documentId] = lecture }
            }
        }
    }

    /** A lecture of [documentId] in [directory]: [record] when saved already. */
    private fun newLecture(documentId: String, directory: String, record: LectureRecord?, startedAt: Long? = null) = Lecture(
        documentId = documentId,
        directory = directory,
        record = record,
        settingsStore = settingsStore,
        backends = backends,
        audioOutput = audioOutput,
        stateContext = stateContext,
        courseContext = { contextOf(documentId)?.text.orEmpty() },
        startedAt = startedAt,
        generatesHere = { canGenerate },
        device = library.deviceId,
        deviceName = library.deviceName,
        recordsLock = library.records,
        onChanged = { library.changed(documentId, it) }
    )

    /**
     * Starts recording a lecture of the document [documentId] titled [title], seen through
     * [notebook] (see [editorNotebook]), once allowed to use the microphone ([microphoneDenied]
     * otherwise).
     */
    public fun record(documentId: String, title: String, notebook: NotebookSource) {
        if (isBusy) return
        scope.launch {
            withContext(stateContext) {
                val allowed = requestMicrophone()
                microphoneDenied = !allowed
                if (allowed) start(documentId, title, notebook)
            }
        }
    }

    /**
     * The notebook of the editor of [documentId] (made by [create] the first time): the pages the
     * recordings of the document see.
     */
    public fun editorNotebook(documentId: String, create: () -> NotebookSource): NotebookSource =
        editorNotebooks.getOrPut(documentId, create)

    /**
     * Starts recording a lecture of the document [documentId] titled [title], seen through
     * [notebook] (the microphone allowed).
     *
     * @return `false` when another lecture is being recorded, or the microphone cannot be opened
     *   ([startError]).
     */
    public fun start(documentId: String, title: String, notebook: NotebookSource): Boolean {
        if (isBusy) return false
        val capture = captureFactory() ?: return false
        val startedAt = currentTimeMillis()
        val lecture = newLecture(documentId, library.newLectureDirectory(documentId, startedAt), record = null, startedAt = startedAt)
            .apply {
                documentTitle = title
                audioShared = sharingOf(documentId).shareAudio
            }
        val settings = settingsStore.settings
        val newRecorder = LectureRecorder(
            lecture, settingsStore, capture, notebook,
            transcriber = if (canGenerate) transcribers.forUpdates(settings) else null,
            stream = if (canGenerate) transcribers.forStreaming(settings) else null,
            analyzes = canGenerate,
            handwriting = handwritingOf(documentId),
            onFinished = { finished(it.lecture) }
        )
        return try {
            newRecorder.start()
            startError = null
            recorder = newRecorder
            shownLectures[documentId] = lecture
            isPanelOpen = true
            refresh(documentId)
            // The first lecture of the notebook: its course context
            if (contexts[documentId] == null) writeContext(documentId, onlyIfMissing = true)
            true
        } catch (e: Throwable) {
            startError = e.message ?: e::class.simpleName
            false
        }
    }

    /** The recording of [lecture] is over: deleted if another device deleted it meanwhile. */
    private fun finished(lecture: Lecture) {
        scope.launch {
            val deleted = lecture.id in library.deletedLectures(lecture.documentId)
            if (deleted) {
                withContext(stateContext) {
                    if (shownLectures[lecture.documentId] === lecture) shownLectures.remove(lecture.documentId)
                }
                library.deleteFiles(lecture.directory)
            }
            refreshNow(lecture.documentId)
        }
    }

    /**
     * The documents [documentIds] were deleted: their lectures go too (not the one being recorded,
     * deleted once over).
     */
    public fun forgetDocuments(documentIds: Set<String>) {
        if (documentIds.isEmpty()) return
        val active = activeRecorder?.lecture?.documentId
        scope.launch {
            for (documentId in documentIds - setOfNotNull(active)) library.deleteFiles(library.documentDirectory(documentId))
        }
    }

    /** The handwriting of the notebook [documentId], transcribed once for all its lectures (and shared with its devices). */
    private fun handwritingOf(documentId: String): HandwritingReader = syncLock(handwritingLock) {
        handwritingReaders.getOrPut(documentId) {
            HandwritingReader(library.documentDirectory(documentId) + "/handwriting.json", backends) { library.changed(documentId, null) }
        }
    }

    /** Whether a recording or an import is in progress (one at a time). */
    public val isBusy: Boolean get() = activeRecorder != null || importing != null

    /** Whether recordings can be imported and exported here (every platform chooses files). */
    public val canImportAndExport: Boolean get() = true

    /**
     * Imports [file], a recording made elsewhere (a phone, a recorder), as a lecture of
     * [documentId] titled [title] (seen through [notebook], whose pages are [pageIds], read on
     * the thread of the state): converted, transcribed and reviewed ([LectureImporter]), in the
     * background.
     *
     * @return `false` when a recording or an import is in progress.
     */
    public fun importRecording(
        documentId: String,
        title: String,
        notebook: NotebookSource,
        pageIds: () -> List<String>,
        file: ChosenFile
    ): Boolean {
        if (isBusy) return false
        val startedAt = file.lastModified ?: currentTimeMillis()
        val lecture = newLecture(documentId, library.newLectureDirectory(documentId, startedAt), record = null, startedAt = startedAt).apply {
            documentTitle = title
            status = LectureStatus.IMPORTING
        }
        importing = lecture
        importError = null
        shownLectures[documentId] = lecture
        isPanelOpen = true
        scope.launch {
            try {
                // The course context first: it helps the transcription
                if (contexts[documentId] == null) writeContextNow(documentId, notebook, onlyIfMissing = true)
                val pages = try {
                    notebook.allPages()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    emptyList()
                }
                val ids = withContext(stateContext) { pageIds() }
                val settings = settingsStore.settings
                LectureImporter(lecture, settingsStore, transcribers.forRecording(settings)).run(file.path, pages, ids, reviews = canGenerate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Not an audio file: no lecture
                library.delete(lecture.directory)
                withContext(stateContext) {
                    importError = e.message ?: e::class.simpleName
                    if (shownLectures[documentId] === lecture) shownLectures.remove(documentId)
                }
            } finally {
                if (file.temporary) withContext(NonCancellable + Dispatchers.IO) { SystemFileSystem.delete(Path(file.path), mustExist = false) }
                withContext(NonCancellable + stateContext) { importing = null }
                refresh(documentId)
            }
        }
        return true
    }

    /**
     * Writes what the models write for [lecture] (finished), with [provider]: its review, and
     * before it its transcript when it has none (recorded on a device without the models: the
     * device that recorded it gets them too).
     *
     * @return `false` when a recording or an import is in progress, or the models are not here.
     */
    public fun generate(lecture: Lecture, provider: ModelProvider): Boolean {
        if (!canGenerate || !lecture.canGenerateReview) return false
        // Written by another device now: left to it
        if (lecture.generatingElsewhere(currentTimeMillis()) != null) return false
        val transcribes = lecture.transcript.isEmpty() && lecture.hasAudio
        if (transcribes) {
            if (isBusy) return false
            importing = lecture
            importError = null
        } else if (!lecture.claimReview(provider)) {
            // Shown as running at once: a second request is refused
            return false
        }
        lecture.generating = scope.launch {
            // The other devices leave it to this one (see GenerationLock)
            holdingGeneration(lecture) {
                if (transcribes) {
                    LectureImporter(lecture, settingsStore, transcribers.forRecording(settingsStore.settings)).transcribeAndReview(provider)
                } else {
                    // With the handwriting of the notebook not read yet, when it is open
                    FinalReviewer(lecture, settingsStore, handwritingOf(lecture.documentId)).write(provider, editorNotebooks[lecture.documentId])
                    lecture.persist()
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                scope.launch {
                    withContext(stateContext) {
                        if (transcribes) importing = null
                        // Stopped before its end (another device took it over)
                        lecture.releaseReviewClaim()
                    }
                }
                refresh(lecture.documentId)
            }
        }
        return true
    }

    /** Runs [block] holding the generation of [lecture] (refreshed meanwhile, released at the end). */
    private suspend fun holdingGeneration(lecture: Lecture, block: suspend () -> Unit) = coroutineScope {
        val now = currentTimeMillis()
        lecture.setState { generation = GenerationLock(library.deviceId, library.deviceName, now, now) }
        lecture.persist()
        val refresher = launch {
            while (true) {
                delay(GenerationLock.REFRESH_MS)
                lecture.setState { generation = generation?.takeIf { it.device == library.deviceId }?.copy(refreshedAt = currentTimeMillis()) }
                lecture.persist()
            }
        }
        try {
            block()
        } finally {
            refresher.cancel()
            withContext(NonCancellable) {
                lecture.setState { if (generation?.device == library.deviceId) generation = null }
                lecture.persist()
            }
        }
    }

    /**
     * Whether [lecture] can be transcribed again here: over, its audio here, the models here, no
     * recording or import in progress, no other device writing it now.
     */
    public fun canRetranscribe(lecture: Lecture): Boolean =
        canGenerate && !isBusy && lecture.status == LectureStatus.FINISHED && lecture.hasAudio &&
            lecture.generatingElsewhere(currentTimeMillis()) == null

    /**
     * Transcribes [lecture] again from its recording, by [provider] (the model of the settings,
     * online or on this computer), in the background ([LectureRetranscriber]); the other devices
     * leave it to this one meanwhile ([GenerationLock]).
     *
     * @return  when it cannot be transcribed again now ([canRetranscribe]).
     */
    public fun retranscribe(lecture: Lecture, provider: ModelProvider): Boolean {
        if (!canRetranscribe(lecture)) return false
        val transcriber = transcribers.forRecording(settingsStore.settings, provider)
        lecture.stopPlayback()
        lecture.status = LectureStatus.IMPORTING
        lecture.importStep = ImportStep.TRANSCRIBING
        lecture.importProgress = 0f
        importing = lecture
        importError = null
        lecture.generating = scope.launch {
            try {
                holdingGeneration(lecture) { LectureRetranscriber(lecture, settingsStore, transcriber).run() }
            } finally {
                withContext(NonCancellable + stateContext) { importing = null }
                refresh(lecture.documentId)
            }
        }
        return true
    }

    /** The model transcribing a lecture again by [provider] (online), empty on this computer. */
    public fun retranscriptionModel(provider: ModelProvider): String = transcribers.recordingModel(settingsStore.settings, provider)

    /** Whether this device can transcribe (whisper.cpp built in). */
    public val canTranscribeHere: Boolean get() = WhisperTranscriber.isAvailable

    /** The audio of [lecture] saved at [path], as m4a, then given to [deliver] (e.g. where the student chose). */
    public fun exportAudio(lecture: Lecture, path: String, deliver: suspend (written: String) -> Unit = {}) {
        exportResult = null
        scope.launch {
            val result = try {
                val saved = withContext(Dispatchers.IO) { exportRecording(lecture.recording, path) }
                deliver(saved)
                ExportResult(saved.substringAfterLast('/'), failure = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ExportResult(path.substringAfterLast('/'), failure = e.message ?: e::class.simpleName.orEmpty())
            }
            withContext(stateContext) { exportResult = result }
        }
    }

    /** The extension of the exported audio. */
    public val exportExtension: String get() = "m4a"

    public fun clearImportAndExportMessages() {
        importError = null
        exportResult = null
    }

    /**
     * Deletes the lecture [summary] (audio included), here and on the devices working on the
     * notebook; not the one being recorded (here or elsewhere).
     */
    public fun delete(documentId: String, summary: LectureSummary) {
        if (activeRecorder?.lecture?.directory == summary.directory || importing?.directory == summary.directory || summary.activeElsewhere) return
        if (shownLectures[documentId]?.directory == summary.directory) {
            shownLectures[documentId]?.stopPlayback()
            shownLectures.remove(documentId)
        }
        scope.launch {
            // Not merged meanwhile with the one of another device
            library.records.withLock { library.delete(summary.directory) }
            refreshNow(documentId)
        }
    }

    internal fun clearStartError() {
        startError = null
    }

    /** Ends the recording right away (the application is exiting); see [LectureRecorder.finishNow]. */
    internal suspend fun finishRecording() {
        activeRecorder?.finishNow()
    }
}

/**
 * The lecture assistants of the process: the application ends their recordings before exiting
 * ([finishAll]), and may ask a confirmation while one is recording ([isRecording]).
 */
public object LectureRecordings {
    private val assistants = mutableListOf<LectureAssistant>()

    internal fun register(assistant: LectureAssistant) {
        assistants.add(assistant)
    }

    /** A lecture is being recorded, reviewed or imported. */
    public val isRecording: Boolean get() = assistants.any { it.isBusy }

    /** Saves the recordings in progress (the final reviews are left to be written later). */
    public suspend fun finishAll() {
        assistants.forEach { it.finishRecording() }
    }
}

/** An audio exported: the name of the file, and why it failed, if it did. */
public class ExportResult(public val fileName: String, public val failure: String?)

/**
 * A lecture received from another device ([arrival]), [durationMs] long, without a transcript:
 * offered to be transcribed and reviewed here by [provider] (see [LectureAssistant.generationOffers]).
 */
public class GenerationOffer(public val arrival: LectureArrival, public val durationMs: Long, public val provider: ModelProvider, public val live: Boolean = false) {
    /**
     * The tokens of the requests to Gemini, about (the audio counts 32 tokens a second, read twice:
     * transcribed, then reviewed), `0` for the local models; for a lecture followed live, an hour of it.
     */
    public val estimatedTokens: Long
        get() = if (provider == ModelProvider.LOCAL) 0 else (if (live) HOUR_SECONDS else durationMs / 1000) * AUDIO_TOKENS_PER_SECOND * 2

    private companion object {
        const val AUDIO_TOKENS_PER_SECOND = 32L
        const val HOUR_SECONDS = 3_600L
    }
}

/** The audio of a lecture followed is whole when it is this close to the length the recording device says. */
private const val FOLLOW_END_TOLERANCE_MS = 2_000L

/** A notification of the system: its [channel] (the kind of notifications, where the system names them), [title] and [text]. */
public class ArrivalNotification(public val channel: String, public val title: String, public val text: String)
