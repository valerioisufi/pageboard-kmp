package io.github.valerioisufi.drawview.lecture.transcription

import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.audio.littleEndianToShorts
import io.github.valerioisufi.drawview.lecture.platform.lectureDataDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The speech recognition of whisper.cpp, built into the application ([WhisperEngine]): the model
 * is loaded when the first chunk is transcribed (a second or two) and freed at the end of the
 * lecture ([close]).
 *
 * @param modelPath the ggml model (e.g. `ggml-large-v3-turbo-q5_0.bin`).
 * @param engine the native whisper.cpp, `null` where it is not available.
 */
class WhisperTranscriber internal constructor(
    private val modelPath: String,
    private val engine: WhisperEngine?,
    private val threads: Int = DEFAULT_THREADS
) : Transcriber {
    private val mutex = Mutex()
    private var handle = 0L

    override suspend fun transcribe(wav: ByteArray, language: String, prompt: String): List<TranscriptSegment> = mutex.withLock {
        val engine = engine ?: throw TranscriptionException("the transcription on this computer is not available on this platform")
        withContext(Dispatchers.IO) {
            if (handle == 0L) {
                if (modelPath.isBlank() || !SystemFileSystem.exists(Path(modelPath))) {
                    throw TranscriptionException("the speech recognition model is not downloaded (see the settings)")
                }
                handle = engine.load(modelPath, useGpu = true)
                if (handle == 0L) throw TranscriptionException("cannot load the speech recognition model $modelPath")
            }
            val samples = wav.copyOfRange(Wav.HEADER_SIZE.coerceAtMost(wav.size), wav.size).littleEndianToShorts()
            val pcm = FloatArray(samples.size) { samples[it] / 32768f }
            val json = try {
                engine.transcribe(handle, pcm, language.ifBlank { "auto" }, prompt.trim().take(MAX_PROMPT_CHARS), threads)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw TranscriptionException("whisper.cpp failed: ${e.message}", e)
            } ?: throw TranscriptionException("whisper.cpp could not transcribe the audio")
            parseVerboseJson(json.decodeToString())
        }
    }

    override suspend fun close() {
        val engine = engine ?: return
        // A transcription in progress stops at its next step
        if (handle != 0L) engine.abort(handle)
        mutex.withLock {
            if (handle != 0L) {
                engine.free(handle)
                handle = 0L
            }
        }
    }

    @Serializable
    private class VerboseJson(val text: String = "", val segments: List<Segment> = emptyList())

    @Serializable
    private class Segment(val start: Double = 0.0, val end: Double = 0.0, val text: String = "")

    companion object {
        /** CPU threads of the decoder (the encoder runs on the GPU, where there is one). */
        private const val DEFAULT_THREADS = 4

        /** The prompt of whisper is short (224 tokens). */
        private const val MAX_PROMPT_CHARS = 600

        private val json = Json { ignoreUnknownKeys = true }

        /** Folder of the speech recognition models: `whisper` next to `lectures`. */
        fun modelsDirectory(): String = lectureDataDirectory().substringBeforeLast('/') + "/whisper"

        /** The model of [settings], or the largest one in [modelsDirectory]. */
        fun modelPath(settings: AssistantSettings): String {
            if (settings.whisperModel.isNotBlank()) return settings.whisperModel
            val directory = Path(modelsDirectory())
            if (!SystemFileSystem.exists(directory)) return ""
            return SystemFileSystem.list(directory)
                .filter { it.name.startsWith("ggml-") && it.name.endsWith(".bin") }
                .maxByOrNull { SystemFileSystem.metadataOrNull(it)?.size ?: 0L }
                ?.toString().orEmpty()
        }

        /** The transcriber of [settings]. */
        fun of(settings: AssistantSettings) = WhisperTranscriber(modelPath(settings), whisperEngine())

        /** Whether this platform can transcribe on the device. */
        val isAvailable: Boolean get() = whisperEngine() != null

        /**
         * The segments of an answer of whisper.cpp (`{"segments":[{"start","end","text"}]}`, times in
         * seconds), in milliseconds.
         */
        internal fun parseVerboseJson(body: String): List<TranscriptSegment> {
            val answer = json.decodeFromString(VerboseJson.serializer(), body)
            if (answer.segments.isEmpty()) {
                return answer.text.trim().takeIf { it.isNotEmpty() }?.let { listOf(TranscriptSegment(0, 0, it)) }.orEmpty()
            }
            val segments = mutableListOf<TranscriptSegment>()
            for (segment in answer.segments) {
                val text = segment.text.trim()
                // Whisper marks the silence and the noises: not speech
                if (text.isEmpty() || NON_SPEECH.matches(text)) continue
                val last = segments.lastOrNull()
                // Whisper also ends a segment in the middle of a word ("il conc" + "etto di"): the
                // next one does not start with a space, and is the rest of the same segment
                if (last != null && !segment.text.first().isWhitespace()) {
                    segments[segments.lastIndex] = last.copy(endMs = (segment.end * 1000).toLong(), text = last.text + text)
                } else {
                    segments.add(TranscriptSegment((segment.start * 1000).toLong(), (segment.end * 1000).toLong(), text))
                }
            }
            return segments
        }

        private val NON_SPEECH = Regex("""^[\[(].*[\])]$""")
    }
}

/** The native whisper.cpp (JNI on the desktop). */
internal interface WhisperEngine {
    /** Loads a model: its handle, 0 when it cannot be loaded. */
    fun load(modelPath: String, useGpu: Boolean): Long

    /**
     * Transcribes [samples] (mono, 16 kHz, -1..1): the segments in JSON (UTF-8), `null` when the
     * transcription failed or was aborted.
     */
    fun transcribe(handle: Long, samples: FloatArray, language: String, prompt: String, threads: Int): ByteArray?

    /** Stops the transcription in progress (from another thread). */
    fun abort(handle: Long)

    fun free(handle: Long)
}

/** The whisper.cpp of this platform, `null` where it is not available. */
internal expect fun whisperEngine(): WhisperEngine?
