package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.AudioOutput
import io.github.valerioisufi.drawview.lecture.audio.createAudioCapture
import io.github.valerioisufi.drawview.lecture.audio.createAudioOutput
import io.github.valerioisufi.drawview.lecture.audio.exportRecording
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.handwriting.HandwritingReader
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.platform.ChosenFile
import io.github.valerioisufi.drawview.lecture.platform.showNotification
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import io.github.valerioisufi.drawview.lecture.transcription.WhisperTranscriber
import io.github.valerioisufi.drawview.model.DocumentAttachments
import io.github.valerioisufi.drawview.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * The editors show it through `editorExtension` of `drawview-lecture-ui`, the
 * document library through `LectureRecordingIndicator` of `drawview-lecture-ui`.
 *
 * The devices working on a notebook share its lectures ([attachments], for the synchronization):
 * a lecture recorded on a device without the models is transcribed and reviewed where they are
 * ([generate]).
 *
 * @param canGenerate whether the models may follow the lectures here (the live notes, the review,
 *   the course context), as [AssistantSettings.generateHere] chooses: elsewhere the lectures are
 *   recorded only.
 * @param deviceModels the models of this device, given by the application: the local requests go
 *   to them when the settings choose them ([AssistantSettings.localEngine]), to Ollama otherwise.
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
    canGenerate: Boolean = true,
    deviceModels: DeviceModels? = null
) {
    private val generationAllowed = canGenerate

    /** Whether the models follow the lectures here (see [AssistantSettings.generateHere]). */
    public val canGenerate: Boolean get() = generationAllowed && settingsStore.settings.generateHere

    /** Whether the models may follow the lectures here, when the student chooses it ([AssistantSettings.generateHere]). */
    public val mayGenerate: Boolean get() = generationAllowed

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The models: the backends, the speech recognition, the handwriting. */
    private val models = AssistantModels(settingsStore, client, localClient, whisper, library, stateContext, deviceModels)

    /** The models of this device, for the settings, when the application gives them and they run here. */
    public val deviceModels: DeviceModels? = deviceModels?.takeIf { it.isAvailable }

    /** The lectures of the documents as shown. */
    private val lectures: AssistantLectures = AssistantLectures(
        library, settingsStore, models, audioOutput, stateContext, scope,
        canGenerate = { canGenerate },
        activeLectures = { transcribing + listOfNotNull(recorder?.lecture) },
        onSharingChanged = { lectureAttachments.audioRequested(it) }
    )

    /** The transcriptions, the imports, the reviews, the lectures followed and offered. */
    private val work = AssistantWork(library, settingsStore, models, lectures, scope, stateContext, canGenerate = { canGenerate })

    /** The models on this computer (Ollama, whisper.cpp), for the settings. */
    public val localModels: LocalModels get() = models.localModels

    /** The last recording (being recorded, being reviewed, or finished). */
    public var recorder: LectureRecorder? by mutableStateOf(null)
        private set

    public var isPanelOpen: Boolean by mutableStateOf(false)

    /** The suggestion the student is placing in the notebook, if any. */
    public var placingNote: NoteSuggestion? by mutableStateOf(null)

    /**
     * The lecture being imported or transcribed now, if any: the one whose transcription runs,
     * else one whose audio is converted, else the first one waiting (see [transcribing]).
     */
    public val importing: Lecture? get() = work.importing

    /**
     * The lectures being imported or transcribed here (an import, a lecture received without a
     * transcript, a lecture transcribed again), in their order: the one transcribed now, those
     * waiting their turn (`ImportStep.WAITING`), then those still converted. One is transcribed at
     * a time (see `docs/transcription-queue.md`).
     */
    public val transcribing: List<Lecture> get() = work.transcribing

    /** Whether [lecture] is being imported or transcribed here, or waits its turn to be. */
    public fun isTranscribing(lecture: Lecture): Boolean = work.isTranscribing(lecture)

    /** Why the last import failed (the file is not audio), if it did. */
    public val importError: String? get() = work.importError

    /** The last export of an audio. */
    public var exportResult: ExportResult? by mutableStateOf(null)
        private set

    /** Why the last recording could not start, if it could not. */
    public var startError: String? by mutableStateOf(null)
        private set

    /** The last recording could not start: the microphone is not allowed. */
    public var microphoneDenied: Boolean by mutableStateOf(false)
        private set

    /** Asks for the permission to use the microphone (set by `LectureMicrophone` of `drawview-lecture-ui`). */
    public var requestMicrophone: suspend () -> Boolean = { true }

    /** Whether the platform can record (the desktop, for now). */
    public val isRecordingSupported: Boolean by lazy { captureFactory() != null }

    /** The recording not finished yet (recording or reviewing), if any. */
    public val activeRecorder: LectureRecorder? get() = recorder?.takeIf { it.lecture.status != LectureStatus.FINISHED }

    public val isRecording: Boolean get() = recorder?.isRecording == true

    private val lectureAttachments: LectureAttachments by lazy { LectureAttachments(sharing, scope) }

    /** The lectures of the notebooks as items beside them, for the synchronization with the other devices. */
    public val attachments: DocumentAttachments get() = lectureAttachments

    /** What arrived from the other devices and was not looked at yet (see [dismissArrival]). */
    public val arrivals: SnapshotStateList<LectureArrival> = mutableStateListOf<LectureArrival>()

    /**
     * The lectures received without a transcript, waiting for the user to have them transcribed and
     * reviewed here (see [AssistantSettings.receivedLectures]).
     */
    public val generationOffers: SnapshotStateList<GenerationOffer> get() = work.generationOffers

    private val sharing = object : LectureAssistantLibrary {
        override val files get() = library

        override suspend fun loadedLecture(directory: String): Lecture? = withContext(stateContext) {
            listOfNotNull(recorder?.lecture, importing).plus(work.followers.values.map { it.lecture }).plus(lectures.shown.values)
                .firstOrNull { it.directory == directory }
        }

        override suspend fun isActiveHere(directory: String): Boolean = withContext(stateContext) {
            activeRecorder?.lecture?.directory == directory || importing?.directory == directory
        }

        override suspend fun onReceived(documentId: String) = lectures.refreshNow(documentId)

        override suspend fun onContextReceived(documentId: String, context: CourseContext) = withContext(stateContext) {
            lectures.contexts[documentId] = context
        }

        override suspend fun onDeleted(documentId: String, directory: String) = withContext(stateContext) {
            val shown = lectures.shown[documentId]
            if (shown?.directory == directory) {
                shown.stopPlayback()
                lectures.shown.remove(documentId)
            }
        }

        override suspend fun onArrival(arrival: LectureArrival) {
            withContext(stateContext) {
                arrivals.removeAll { it.directory == arrival.directory && it.kind == arrival.kind }
                arrivals.add(arrival)
            }
            notifyArrival(arrival)
            if (arrival.kind != LectureArrival.Kind.REVIEW) work.offerGeneration(arrival)
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
    public fun sharingOf(documentId: String): LectureSharingSettings = lectures.sharingOf(documentId)

    /** Changes how this device shares the lectures of [documentId] by [transform]. */
    public fun changeSharing(documentId: String, transform: (LectureSharingSettings) -> LectureSharingSettings) {
        lectures.changeSharing(documentId, transform)
    }

    /** Whether the audio of [lecture] goes to the other devices (any device decides it, the most recent decision wins). */
    public fun shareAudio(lecture: Lecture, shared: Boolean) {
        lecture.audioShared = shared
        lecture.scope.launch { lecture.persist() }
    }

    /** Receives the audio of [lecture] (recorded on another device) now, even if received only on request. */
    public fun requestAudio(lecture: Lecture) {
        lectures.requestAudio(lecture)
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
                if (lectures.shown[arrival.documentId]?.directory != summary.directory) lectures.shown.remove(arrival.documentId)
                show(arrival.documentId, summary)
            }
        }
    }

    /**
     * Opens the lecture [lectureId] of [documentId] in the panel and seeks its recording to
     * [startMs] (e.g. from a search result or a citation of "Ask your notes").
     */
    public fun openLectureAt(documentId: String, lectureId: String, startMs: Long = 0L) {
        isPanelOpen = true
        scope.launch {
            val summaries = library.list(documentId)
            val summary = summaries.firstOrNull {
                it.directory.trimEnd('/').substringAfterLast('/') == lectureId
            } ?: summaries.firstOrNull() ?: return@launch
            val lecture = lectures.lectureIn(documentId, summary.directory) ?: return@launch
            withContext(stateContext) {
                if (startMs > 0L) {
                    lecture.player?.seek(startMs)
                }
            }
        }
    }

    /** Transcribes and reviews the lecture of [offer] here (the user accepted it, or always does). */
    public fun acceptGeneration(offer: GenerationOffer) {
        work.acceptGeneration(offer)
    }

    public fun declineGeneration(offer: GenerationOffer) {
        work.declineGeneration(offer)
    }

    /** The documents with a lecture (the badge and the filter of the library, see `documentTag` of `drawview-lecture-ui`). */
    public val documentsWithLectures: Set<String> get() = lectures.documentsWithLectures

    /** The lectures of [documentId] (loaded by [refresh]), the most recent first. */
    public fun lecturesOf(documentId: String): List<LectureSummary> = lectures.lecturesOf(documentId)

    /** The lecture shown in the panel of [documentId]: the one being recorded, or the last one. */
    public fun shownLecture(documentId: String): Lecture? = lectures.shown[documentId]

    /** The recorder of [documentId], if it is the one being recorded or reviewed. */
    public fun recorderOf(documentId: String): LectureRecorder? = activeRecorder?.takeIf { it.lecture.documentId == documentId }

    /**
     * The editor of [documentId] is shown: the recording of the document (if any) sees its pages
     * again, and its lectures are loaded.
     */
    public fun onEditorShown(documentId: String, notebook: NotebookSource) {
        lectures.editorNotebooks[documentId] = notebook
        recorderOf(documentId)?.attachNotebook(notebook)
        work.followers.values.filter { it.lecture.documentId == documentId }.forEach { it.attachNotebook(notebook) }
        refresh(documentId)
    }

    /** The editor of [documentId] is closed: the recording goes on, without its pages. */
    public fun onEditorHidden(documentId: String, notebook: NotebookSource) {
        if (lectures.editorNotebooks[documentId] === notebook) lectures.editorNotebooks.remove(documentId)
        recorderOf(documentId)?.detachNotebook(notebook)
        work.followers.values.filter { it.lecture.documentId == documentId }.forEach { it.detachNotebook(notebook) }
        placingNote = null
        lectures.shown[documentId]?.stopPlayback()
    }

    /** The course context of [documentId] (loaded by [refresh]), if any. */
    public fun contextOf(documentId: String): CourseContext? = lectures.contexts[documentId]

    /** The course context of [documentId] is being written. */
    public fun isWritingContext(documentId: String): Boolean = lectures.isWritingContext(documentId)

    /** Whether the context of [documentId] can be written: its notebook is open (and the models are here). */
    public fun canWriteContext(documentId: String): Boolean = lectures.canWriteContext(documentId)

    /** The student changed the course context of [documentId]: it is not replaced automatically. */
    public fun editContext(documentId: String, text: String) {
        lectures.editContext(documentId, text)
    }

    /**
     * Writes the course context of [documentId] from its notebook (open), with the model of the
     * live suggestions; [onlyIfMissing]: not when the notebook has one already.
     */
    public fun writeContext(documentId: String, onlyIfMissing: Boolean = false) {
        lectures.writeContext(documentId, onlyIfMissing)
    }

    /**
     * What turning [text] into notes for [documentId] would cost, with the model of the task of the
     * review (see [textNotes]); `null` when the editor of the document is not shown.
     */
    public suspend fun estimateTextNotes(documentId: String, text: String): TextNotesEstimate? {
        val notebook = lectures.editorNotebooks[documentId] ?: return null
        return models.estimateTextNotes(documentId, notebook, text)
    }

    /**
     * [text] turned into notes for the notebook of [documentId] by the model of the task of the
     * review (billed when it is Gemini: ask the student first, see [estimateTextNotes]), each with
     * its kind, its page and the words of the page it is about.
     *
     * @throws IllegalStateException when the editor of the document is not shown, the text is too
     *   long or Gemini has no key; the errors of the model as they come.
     */
    public suspend fun textNotes(documentId: String, text: String): TextNotes {
        check(text.length <= TextNotes.MAX_TEXT_CHARS) { "The text is too long" }
        val notebook = lectures.editorNotebooks[documentId] ?: error("The editor of the document is not shown")
        return models.textNotes(documentId, notebook, text)
    }

    /** Lists the lectures of [documentId] again, showing the last one when none is shown. */
    public fun refresh(documentId: String) {
        lectures.refresh(documentId)
    }

    /** Shows the lecture [summary] in the panel of [documentId]. */
    public fun show(documentId: String, summary: LectureSummary) {
        lectures.show(documentId, summary)
    }

    /**
     * Starts recording a lecture of the document [documentId] titled [title], seen through
     * [notebook] (see [editorNotebook]), once allowed to use the microphone ([microphoneDenied]
     * otherwise).
     */
    public fun record(documentId: String, title: String, notebook: NotebookSource) {
        if (!canRecord) return
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
        lectures.editorNotebooks.getOrPut(documentId, create)

    /**
     * Starts recording a lecture of the document [documentId] titled [title], seen through
     * [notebook] (the microphone allowed).
     *
     * The transcriptions of whole recordings pause until it is over ([transcribing]): the one running
     * at the end of the piece it is on.
     *
     * @return `false` when another lecture is being recorded, or the microphone cannot be opened
     *   ([startError]).
     */
    public fun start(documentId: String, title: String, notebook: NotebookSource): Boolean {
        if (!canRecord) return false
        val capture = captureFactory() ?: return false
        val startedAt = currentTimeMillis()
        val lecture = lectures.newLecture(documentId, library.newLectureDirectory(documentId, startedAt), record = null, startedAt = startedAt)
            .apply {
                documentTitle = title
                audioShared = sharingOf(documentId).shareAudio
            }
        val settings = settingsStore.settings
        val newRecorder = LectureRecorder(
            lecture, settingsStore, capture, notebook,
            transcriber = if (canGenerate) models.transcribers.forUpdates(settings) else null,
            analyzes = canGenerate,
            handwriting = models.handwritingOf(documentId),
            onFinished = {
                scope.launch(stateContext) { work.queue.release() }
                finished(it.lecture)
            }
        )
        return try {
            newRecorder.start()
            // The speech model is its own until it is over
            work.queue.hold()
            startError = null
            recorder = newRecorder
            lectures.shown[documentId] = lecture
            isPanelOpen = true
            refresh(documentId)
            // The first lecture of the notebook: its course context
            if (lectures.contexts[documentId] == null) writeContext(documentId, onlyIfMissing = true)
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
                    if (lectures.shown[lecture.documentId] === lecture) lectures.shown.remove(lecture.documentId)
                }
                library.deleteFiles(lecture.directory)
            }
            lectures.refreshNow(lecture.documentId)
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

    /**
     * The handwriting of document [documentId] read so far, by key of block (see
     * [io.github.valerioisufi.drawview.lecture.handwriting.blockKey]): as last saved, also by a
     * recording going on.
     */
    public suspend fun handwritingTexts(documentId: String): Map<String, String> = withContext(Dispatchers.IO) {
        HandwritingReader.readFile(library.documentDirectory(documentId) + "/handwriting.json").mapValues { it.value.text }
    }

    /**
     * Whether a recording, an import or a transcription is in progress or waits its turn: what
     * would be interrupted if the application quit now.
     */
    public val isBusy: Boolean get() = activeRecorder != null || importing != null

    /** Whether a lecture can be recorded here now: none is being recorded, or reviewed after its recording. */
    public val canRecord: Boolean get() = activeRecorder == null

    /** Whether recordings can be imported and exported here (every platform chooses files). */
    public val canImportAndExport: Boolean get() = true

    /**
     * Imports [file], a recording made elsewhere (a phone, a recorder), as a lecture of
     * [documentId] titled [title] (seen through [notebook], whose pages are [pageIds], read on
     * the thread of the state), in the background ([LectureImporter]): converted at once, then
     * transcribed and reviewed at its turn, after the transcriptions asked before it and after
     * the recording going on here, if any ([transcribing]).
     *
     * @return `true`: an import is always taken.
     */
    public fun importRecording(
        documentId: String,
        title: String,
        notebook: NotebookSource,
        pageIds: () -> List<String>,
        file: ChosenFile
    ): Boolean {
        work.importRecording(documentId, title, notebook, pageIds, file)
        isPanelOpen = true
        return true
    }

    /**
     * Takes [lecture] out of the transcriptions here, whether it waits or is being transcribed: it
     * stays a finished lecture, with its audio and the transcript made so far, transcribed on
     * request ([generate], [retranscribe]).
     */
    public fun cancelTranscription(lecture: Lecture) {
        work.cancelTranscription(lecture)
    }

    /**
     * Writes what the models write for [lecture] (finished), with [provider]: its review, and
     * before it its transcript when it has none (recorded on a device without the models: the
     * device that recorded it gets them too).
     *
     * A transcription waits its turn after the ones asked before it, and after the recording going
     * on here ([transcribing]); the other devices leave the lecture to this one from when it starts
     * ([GenerationLock]).
     *
     * @return `false` when the lecture is in the queue already, or the models are not here.
     */
    public fun generate(lecture: Lecture, provider: ModelProvider): Boolean = work.generate(lecture, provider)

    /** Whether a question can be answered online now (a key of Gemini is configured). */
    public val canAnswerOnline: Boolean get() = models.canAnswerOnline

    /**
     * Answers [prompt] with [systemInstruction] online (Gemini, the model of the live notes), in
     * plain text: the answer paired with the name of the model.
     *
     * @throws IllegalStateException when it cannot answer online ([canAnswerOnline]); the errors
     *   of the model as they come.
     */
    public suspend fun answerOnline(systemInstruction: String, prompt: String): Pair<String, String> =
        models.answerOnline(systemInstruction, prompt)

    /**
     * Whether [lecture] can be transcribed again here: over, its audio here, the models here, not
     * in the queue already, no other device writing it now.
     */
    public fun canRetranscribe(lecture: Lecture): Boolean = work.canRetranscribe(lecture)

    /**
     * Transcribes [lecture] again from its recording, on this computer, in the background
     * ([LectureRetranscriber]), at its turn after the transcriptions asked before it
     * ([transcribing]); the other devices leave it to this one from when it starts
     * ([GenerationLock]).
     *
     * @return `false` when it cannot be transcribed again now ([canRetranscribe]).
     */
    public fun retranscribe(lecture: Lecture): Boolean = work.retranscribe(lecture)

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

    /** The transcript of [lecture] saved at [path], as a text file, then given to [deliver] (e.g. where the student chose). */
    public fun exportTranscript(lecture: Lecture, path: String, deliver: suspend (written: String) -> Unit = {}) {
        exportResult = null
        scope.launch {
            val result = try {
                val segments = withContext(stateContext) { lecture.transcript.toList() }
                val saved = withContext(Dispatchers.IO) { exportTranscript(segments, path) }
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

    /** The extension of the exported transcript. */
    public val transcriptExportExtension: String get() = "txt"

    public fun clearImportAndExportMessages() {
        work.importError = null
        exportResult = null
    }

    /**
     * Deletes the lecture [summary] (audio included), here and on the devices working on the
     * notebook; not the one being recorded (here or elsewhere).
     */
    public fun delete(documentId: String, summary: LectureSummary) {
        if (activeRecorder?.lecture?.directory == summary.directory || transcribing.any { it.directory == summary.directory } || summary.activeElsewhere) return
        if (lectures.shown[documentId]?.directory == summary.directory) {
            lectures.shown[documentId]?.stopPlayback()
            lectures.shown.remove(documentId)
        }
        scope.launch {
            // Not merged meanwhile with the one of another device
            library.records.withLock { library.delete(summary.directory) }
            lectures.refreshNow(documentId)
        }
    }

    /**
     * Saves the recording in progress right away, the final review left to be written later (the
     * application is exiting); see [LectureRecorder.finishNow].
     */
    public suspend fun finishRecording() {
        activeRecorder?.finishNow()
    }
}
