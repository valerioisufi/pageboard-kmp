package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.decodeAudioFile
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Turns an audio file recorded elsewhere (a phone, a recorder) into a [lecture] of a notebook:
 * converted into the audio of the lectures ([convert]), then, at its turn, transcribed by
 * [transcriber] ([RecordingTranscriber]) and reviewed as a recorded lecture, unless the review is
 * written on request ([transcribeAndReview]). No live suggestion: the review writes the notes and
 * what is missing from the notebook (`pages`).
 *
 * A lecture recorded on a device without the models is transcribed and reviewed the same way
 * ([transcribeAndReview]).
 *
 * @param transcriber makes the transcriber when the transcription starts (the settings as they are
 *   then), closed at its end.
 * @param gate what makes the transcription wait between two pieces (a recording going on).
 */
internal class LectureImporter(
    private val lecture: Lecture,
    private val settingsStore: AssistantSettingsStore,
    private val gate: TranscriptionGate? = null,
    private val transcriber: () -> Transcriber
) {
    /**
     * Converts [input] into the audio of the lecture, and saves the lecture: from here on the
     * recording is in the notebook, whatever happens to its transcription. [transcribed]: its
     * transcription follows (waiting its turn); otherwise the lecture is finished with its audio
     * only, for a device with the models.
     *
     * @throws IllegalArgumentException when [input] cannot be read as audio.
     */
    suspend fun convert(input: String, pages: List<PageContent>, pageIds: List<String>, transcribed: Boolean = true) {
        lecture.setState {
            importStep = ImportStep.CONVERTING
            importProgress = -1f
            setNotebookPages(pages, pageIds)
        }
        val samples = withContext(Dispatchers.IO) {
            SystemFileSystem.createDirectories(Path(lecture.directory))
            val samples = try {
                decodeAudioFile(input, lecture.recording)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException(e.message ?: "unreadable audio", e)
            }
            require(samples > 0) { "no audio in the file" }
            samples
        }
        lecture.setState {
            elapsedMs = samplesToMs(samples)
            if (transcribed) {
                importStep = ImportStep.WAITING
            } else {
                // No models here: the audio only, reviewed by a device with them
                status = LectureStatus.FINISHED
                importStep = null
            }
        }
        // Its audio is here from now on: it can be played, exported and transcribed on request
        lecture.audioReceived()
        lecture.persist()
    }

    /**
     * Transcribes the audio of the lecture, then writes its review with [provider] ([review]: the
     * review written at the end, see [AssistantSettings.reviewAtTheEnd]; otherwise on request).
     */
    suspend fun transcribeAndReview(provider: ModelProvider, review: Boolean = true) {
        val durationMs = lecture.setStateResult {
            status = LectureStatus.IMPORTING
            elapsedMs
        }
        val settings = settingsStore.settings
        val transcriber = transcriber()
        try {
            transcribe(transcriber, settings, durationMs)
        } finally {
            transcriber.close()
        }

        try {
            if (review) {
                lecture.setState {
                    importStep = ImportStep.REVIEWING
                    importProgress = -1f
                }
                lecture.persist()
                FinalReviewer(lecture, settingsStore, handwriting = null).write(provider, notebook = null)
            }
        } finally {
            withContext(NonCancellable) {
                lecture.setState {
                    status = LectureStatus.FINISHED
                    importStep = null
                }
                lecture.persist()
            }
        }
    }

    /**
     * The transcript, chunk after chunk, as fast as the transcription goes. A failure stops it: the
     * review goes on with the transcript made so far.
     */
    private suspend fun transcribe(transcriber: Transcriber, settings: AssistantSettings, durationMs: Long) {
        lecture.setState {
            importStep = ImportStep.TRANSCRIBING
            importProgress = 0f
        }
        val hints = { LecturePrompts.speechHints(settings, lecture.courseContext(), visibleSlides = emptyList()) }
        try {
            RecordingTranscriber(lecture.recording, transcriber, gate, lecture::transcriptionPaused, hints).transcribe(durationMs) { chunk, progress ->
                lecture.setState {
                    transcript.addAll(chunk.segments)
                    chunk.usage?.let { usage += it }
                    importProgress = progress
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lecture.setState { problem = transcriptionProblem(e) }
        }
    }
}
