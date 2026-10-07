package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.AudioChunk
import io.github.valerioisufi.drawview.lecture.audio.SpeechChunker
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.audio.decodeAudioFile
import io.github.valerioisufi.drawview.lecture.audio.isSilent
import io.github.valerioisufi.drawview.lecture.audio.samplesToMs
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Turns an audio file recorded elsewhere (a phone, a recorder) into a [lecture] of a notebook:
 * converted into the audio of the lectures, transcribed in chunks cut at the pauses (on this
 * computer by [transcriber], or by Gemini when the audio may be sent and [transcriber] is `null`),
 * then reviewed as a recorded lecture. No live suggestion: the review writes the notes and what is
 * missing from the notebook ([pages]).
 *
 * A lecture recorded on a device without the models is transcribed and reviewed the same way
 * ([transcribeAndReview]).
 */
internal class LectureImporter(
    private val lecture: Lecture,
    private val settingsStore: AssistantSettingsStore,
    private val transcriber: Transcriber?
) {
    /**
     * Imports [input].
     *
     * @throws IllegalArgumentException when [input] cannot be read as audio.
     */
    suspend fun run(input: String, pages: List<PageContent>, pageIds: List<String>, reviews: Boolean = true) {
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
        lecture.setState { elapsedMs = samplesToMs(samples) }
        lecture.persist()
        if (reviews) {
            transcribeAndReview(settingsStore.settings.reviewProvider)
        } else {
            // No models here: the audio only, reviewed by a device with them
            lecture.setState {
                status = LectureStatus.FINISHED
                importStep = null
            }
            lecture.persist()
        }
    }

    /** Transcribes the audio of the lecture, then writes its review with [provider]. */
    suspend fun transcribeAndReview(provider: ModelProvider) {
        val durationMs = lecture.setStateResult {
            status = LectureStatus.IMPORTING
            elapsedMs
        }
        val settings = settingsStore.settings
        try {
            transcribe(settings, durationMs)
        } finally {
            transcriber?.close()
        }

        lecture.setState {
            importStep = ImportStep.REVIEWING
            importProgress = -1f
        }
        lecture.persist()
        try {
            lecture.runFinalReview(provider)
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

    /** The transcript, chunk after chunk, as fast as the transcription goes. */
    private suspend fun transcribe(settings: AssistantSettings, durationMs: Long) {
        lecture.setState {
            importStep = ImportStep.TRANSCRIBING
            importProgress = 0f
        }
        val chunker = SpeechChunker(targetSeconds = CHUNK_SECONDS, overlapSeconds = LectureRecorder.OVERLAP_SECONDS)
        var readMs = 0L
        while (true) {
            val block = withContext(Dispatchers.IO) { lecture.recording.readSegment(readMs, BLOCK_MS) } ?: break
            if (block.isEmpty()) break
            readMs += samplesToMs(block.size.toLong())
            for (chunk in chunker.push(block)) if (!transcribe(chunk, settings)) return
            lecture.setState { importProgress = (readMs.toFloat() / durationMs).coerceIn(0f, 1f) }
        }
        chunker.cutNow(minSeconds = 1f)?.let { transcribe(it, settings) }
    }

    /** Adds the transcript of [chunk] (the part repeating the previous chunk dropped): whether to go on. */
    private suspend fun transcribe(chunk: AudioChunk, settings: AssistantSettings): Boolean {
        if (isSilent(chunk.samples, chunk.overlapSamples)) return true
        val wav = Wav.encode(chunk.samples)
        val context = lecture.courseContext()
        val segment = try {
            if (transcriber != null) {
                val segments = transcriber.transcribe(wav, settings.whisperLanguage, LecturePrompts.transcriptionPrompt(context, emptyList()))
                    .map { it.copy(startMs = it.startMs + chunk.startMs, endMs = it.endMs + chunk.startMs) }
                    .filter { it.endMs > chunk.newStartMs + LectureRecorder.OVERLAP_TOLERANCE_MS && it.text.isNotBlank() }
                if (segments.isEmpty()) return true
                TranscriptSegment(segments.first().startMs, segments.last().endMs, segments.joinToString(" ") { it.text.trim() })
            } else {
                val text = transcribeOnline(wav, context, settings, contextMs = chunk.newStartMs - chunk.startMs)
                if (text.isBlank()) return true
                TranscriptSegment(chunk.newStartMs, chunk.endMs, text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The review goes on without the rest of the transcript (with the recording, online)
            lecture.setState { problem = Lecture.problemOf(e).takeUnless { it is LectureProblem.Failed } ?: LectureProblem.TranscriptionUnavailable(e.message.orEmpty()) }
            return false
        }
        lecture.setState { transcript.add(segment) }
        return true
    }

    private suspend fun transcribeOnline(wav: ByteArray, context: String, settings: AssistantSettings, contextMs: Long): String {
        val prompt = LecturePrompts.transcriptionOnlyPrompt(settings, context, wav, contextMs)
        var attempt = 0
        while (true) {
            try {
                val answer = lecture.backends.gemini.generate(prompt, settings.task(LectureTask.TRANSCRIPTION).gemini)
                answer.usage?.let { usage -> lecture.setState { this.usage += usage } }
                return LectureAnalysis.parseTranscript(answer.text)
            } catch (e: GeminiException) {
                attempt++
                if (!e.isRetryable || attempt >= MAX_ATTEMPTS) throw e
                delay((2_000L shl attempt).coerceAtMost(30_000L))
            }
        }
    }

    private companion object {
        const val CHUNK_SECONDS = 60
        const val BLOCK_MS = 10_000L
        const val MAX_ATTEMPTS = 4
    }
}
