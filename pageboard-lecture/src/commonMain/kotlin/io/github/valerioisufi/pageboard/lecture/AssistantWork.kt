package io.github.valerioisufi.pageboard.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.valerioisufi.pageboard.lecture.audio.FollowedRecording
import io.github.valerioisufi.pageboard.lecture.audio.RecordingFile
import io.github.valerioisufi.pageboard.lecture.audio.samplesToMs
import io.github.valerioisufi.pageboard.lecture.platform.ChosenFile
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The work a [LectureAssistant] schedules over time (Pure Fabrication): the transcriptions of whole
 * recordings, one at a time ([TranscriptionQueue]), the conversions of the imports, the reviews, the
 * lectures of other devices followed live, the offers of the lectures received, and the lock that
 * leaves a lecture to this device while it writes it ([GenerationLock]).
 *
 * Its state is Compose state, changed on [stateContext].
 */
internal class AssistantWork(
    private val library: LectureLibrary,
    private val settingsStore: AssistantSettingsStore,
    private val models: AssistantModels,
    private val lectures: AssistantLectures,
    private val scope: CoroutineScope,
    private val stateContext: CoroutineContext,
    private val canGenerate: () -> Boolean
) {
    /** The transcriptions of whole recordings, one at a time. */
    val queue = TranscriptionQueue(scope, stateContext)

    /**
     * The conversions of the imports, run one at a time in the order they were asked for: a
     * `Mutex` reached from the coroutine of each import let two imports asked one after the other
     * swap, and their transcriptions with them.
     */
    private val conversions = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (conversion in conversions) {
                try {
                    conversion()
                } catch (e: CancellationException) {
                    // A cancellation of one conversion leaves the others running, unless it is the assistant's
                    if (!currentCoroutineContext().isActive) throw e
                }
            }
        }
    }

    /** The imports whose audio is being converted, before their transcription waits its turn. */
    private var converting: List<Lecture> by mutableStateOf(emptyList())

    /** The lectures recorded on other devices followed here live, by folder (see [LectureRecorder]). */
    val followers = mutableStateMapOf<String, LectureRecorder>()

    /** See [LectureAssistant.generationOffers]. */
    val generationOffers: SnapshotStateList<GenerationOffer> = mutableStateListOf<GenerationOffer>()

    /** See [LectureAssistant.importError]. */
    var importError: String? by mutableStateOf(null)

    val importing: Lecture? get() = queue.running ?: converting.firstOrNull() ?: queue.lectures.firstOrNull()

    val transcribing: List<Lecture> get() = queue.lectures + converting

    fun isTranscribing(lecture: Lecture): Boolean = lecture in queue || converting.any { it === lecture }

    // ==========================================================
    // The lectures received
    // ==========================================================

    /**
     * A lecture received from another device (with its audio, without a transcript): transcribed
     * and reviewed here at once, or offered to the user with what it would cost (see
     * [AssistantSettings.receivedLectures]).
     */
    suspend fun offerGeneration(arrival: LectureArrival) {
        if (!canGenerate()) return
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

    fun acceptGeneration(offer: GenerationOffer) {
        generationOffers.remove(offer)
        scope.launch { if (offer.live) follow(offer) else generateReceived(offer) }
    }

    fun declineGeneration(offer: GenerationOffer) {
        generationOffers.remove(offer)
    }

    private suspend fun generateReceived(offer: GenerationOffer) {
        val lecture = lectures.lectureIn(offer.arrival.documentId, offer.arrival.directory) ?: return
        withContext(stateContext) { generate(lecture, offer.provider) }
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
        if (!canGenerate() || withContext(stateContext) { directory in followers }) return
        val lecture = lectures.lectureIn(documentId, directory) ?: return
        // Over meanwhile: reviewed as the lectures received whole
        if (lecture.status == LectureStatus.FINISHED) return offerGeneration(offer.arrival.copy(kind = LectureArrival.Kind.AUDIO))
        withContext(stateContext) {
            // Its audio, also when it is downloaded on request only
            if (lectures.sharingOf(documentId).downloadAudio == AudioDownload.ON_REQUEST) lectures.requestAudio(lecture)
        }
        val settings = settingsStore.settings
        val transcriber = models.transcribers.forUpdates(settings)
        val ended = CompletableDeferred<Unit>()
        lateinit var follower: LectureRecorder
        val capture = FollowedRecording(
            recording = lecture.recording,
            // Recorded whole there, and arrived whole here (as long as it is)
            isOver = { samples -> lecture.deletedElsewhere || (lecture.status == LectureStatus.FINISHED && samplesToMs(samples) >= lecture.elapsedMs - FOLLOW_END_TOLERANCE_MS) },
            onEnd = { if (lecture.deletedElsewhere) scope.launch { follower.abandon() } else follower.stop() }
        )
        follower = LectureRecorder(
            lecture, settingsStore, capture, lectures.editorNotebooks[documentId], transcriber,
            analyzes = true, follows = true, handwriting = models.handwritingOf(documentId), onFinished = { ended.complete(Unit) }
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
                lectures.refresh(documentId)
            }
        }
    }

    // ==========================================================
    // Imports, transcriptions and reviews
    // ==========================================================

    /** See [LectureAssistant.importRecording]. */
    fun importRecording(
        documentId: String,
        title: String,
        notebook: NotebookSource,
        pageIds: () -> List<String>,
        file: ChosenFile
    ) {
        val startedAt = file.lastModified ?: currentTimeMillis()
        val lecture = lectures.newLecture(documentId, library.newLectureDirectory(documentId, startedAt), record = null, startedAt = startedAt).apply {
            documentTitle = title
            status = LectureStatus.IMPORTING
            importStep = ImportStep.CONVERTING
        }
        converting = converting + lecture
        importError = null
        lectures.shown[documentId] = lecture
        val importer = LectureImporter(lecture, settingsStore, queue.gate) { models.transcribers.forRecording(settingsStore.settings) }
        // Taken in the order asked: the transcription of an import waits behind those asked before
        conversions.trySend {
            val transcribed = canGenerate()
            val converted = try {
                val pages = try {
                    notebook.allPages()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    emptyList()
                }
                val ids = withContext(stateContext) { pageIds() }
                importer.convert(file.path, pages, ids, transcribed)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Not an audio file: no lecture
                library.delete(lecture.directory)
                withContext(stateContext) {
                    importError = e.message ?: e::class.simpleName
                    if (lectures.shown[documentId] === lecture) lectures.shown.remove(documentId)
                }
                false
            } finally {
                if (file.temporary) withContext(NonCancellable + Dispatchers.IO) { SystemFileSystem.delete(Path(file.path), mustExist = false) }
            }
            withContext(NonCancellable + stateContext) {
                // In the queue before it leaves the conversions: never out of both
                if (converted && transcribed) {
                    lecture.generating = enqueueTranscription(lecture) {
                        // The course context first: it helps the transcription
                        if (withContext(stateContext) { lectures.contexts[documentId] == null }) lectures.writeContextNow(documentId, notebook, onlyIfMissing = true)
                        val settings = settingsStore.settings
                        importer.transcribeAndReview(settings.reviewProvider, review = settings.reviewAtTheEnd)
                    }
                }
                converting = converting.filterNot { it === lecture }
            }
            lectures.refresh(documentId)
        }
    }

    /**
     * Puts [work], the transcription of [lecture], after the ones asked before it; the lecture is
     * shown as waiting until its turn. Called on the thread of the state.
     */
    private fun enqueueTranscription(lecture: Lecture, work: suspend () -> Unit): Job {
        lecture.status = LectureStatus.IMPORTING
        lecture.importStep = ImportStep.WAITING
        lecture.importProgress = -1f
        return queue.enqueue(lecture, work).also { job ->
            job.invokeOnCompletion {
                scope.launch {
                    // Stopped before its end (another device took it over)
                    withContext(stateContext) { lecture.releaseReviewClaim() }
                }
                lectures.refresh(lecture.documentId)
            }
        }
    }

    /** See [LectureAssistant.cancelTranscription]. */
    fun cancelTranscription(lecture: Lecture) {
        if (lecture !in queue) return
        val job = lecture.generating ?: return
        scope.launch {
            job.cancelAndJoin()
            lecture.setState {
                if (status == LectureStatus.IMPORTING) {
                    status = LectureStatus.FINISHED
                    importStep = null
                    importProgress = -1f
                }
            }
            lecture.persist()
            lectures.refresh(lecture.documentId)
        }
    }

    /** See [LectureAssistant.generate]. */
    fun generate(lecture: Lecture, provider: ModelProvider): Boolean {
        if (!canGenerate() || !lecture.canGenerateReview) return false
        // Written by another device now: left to it
        if (lecture.generatingElsewhere(currentTimeMillis()) != null) return false
        if (lecture.transcript.isEmpty() && lecture.hasAudio) {
            if (isTranscribing(lecture)) return false
            importError = null
            lecture.generating = enqueueTranscription(lecture) {
                holdingGeneration(lecture) {
                    LectureImporter(lecture, settingsStore, queue.gate) { models.transcribers.forRecording(settingsStore.settings) }.transcribeAndReview(provider)
                }
            }
            return true
        }
        // Shown as running at once: a second request is refused
        if (!lecture.claimReview(provider)) return false
        lecture.generating = scope.launch {
            // The other devices leave it to this one (see GenerationLock)
            holdingGeneration(lecture) {
                // With the handwriting of the notebook not read yet, when it is open
                FinalReviewer(lecture, settingsStore, models.handwritingOf(lecture.documentId)).write(provider, lectures.editorNotebooks[lecture.documentId])
                lecture.persist()
            }
        }.also { job ->
            job.invokeOnCompletion {
                scope.launch {
                    // Stopped before its end (another device took it over)
                    withContext(stateContext) { lecture.releaseReviewClaim() }
                }
                lectures.refresh(lecture.documentId)
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

    /** See [LectureAssistant.canRetranscribe]. */
    fun canRetranscribe(lecture: Lecture): Boolean =
        canGenerate() && !isTranscribing(lecture) && lecture.status == LectureStatus.FINISHED && lecture.hasAudio &&
            lecture.generatingElsewhere(currentTimeMillis()) == null

    /** See [LectureAssistant.retranscribe]. */
    fun retranscribe(lecture: Lecture): Boolean {
        if (!canRetranscribe(lecture)) return false
        lecture.stopPlayback()
        importError = null
        lecture.generating = enqueueTranscription(lecture) {
            lecture.setState {
                importStep = ImportStep.TRANSCRIBING
                importProgress = 0f
            }
            // The model chosen when its turn comes
            val transcriber = models.transcribers.forRecording(settingsStore.settings)
            holdingGeneration(lecture) { LectureRetranscriber(lecture, settingsStore, transcriber, queue.gate).run() }
        }
        return true
    }
}
