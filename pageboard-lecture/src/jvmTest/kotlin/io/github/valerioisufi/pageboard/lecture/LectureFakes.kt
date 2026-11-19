package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.audio.AudioCapture
import io.github.valerioisufi.pageboard.lecture.audio.CaptureEvent
import io.github.valerioisufi.pageboard.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.pageboard.lecture.transcription.SpeechHints
import io.github.valerioisufi.pageboard.lecture.transcription.Transcriber
import io.github.valerioisufi.pageboard.lecture.transcription.Transcription
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.util.Collections
import kotlin.random.Random
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The microphone, the notebook, the transcription and the answers of Gemini of the tests

/** A transcription on this computer (with times, with the overlap as context). */
internal class FakeTranscriber : Transcriber {
    var closed = false
    val prompts = Collections.synchronizedList(mutableListOf<String>())
    override val usesLeadingContext = true
    override suspend fun transcribe(wav: ByteArray, hints: SpeechHints): Transcription {
        prompts.add(hints.prompt)
        return Transcription(listOf(TranscriptSegment(2_000, 5_000, "Il teorema ponte"), TranscriptSegment(5_000, 9_000, "vale per ogni successione")))
    }

    override suspend fun close() {
        closed = true
    }
}

internal val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

internal fun geminiAnswer(payload: String) = buildJsonObject {
    put("candidates", buildJsonArray {
        add(buildJsonObject {
            put("content", buildJsonObject {
                put("role", "model")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", payload) }) })
            })
        })
    })
    put("usageMetadata", buildJsonObject {
        put("promptTokenCount", 1000)
        put("candidatesTokenCount", 100)
        put("promptTokensDetails", buildJsonArray {
            add(buildJsonObject {
                put("modality", "TEXT")
                put("tokenCount", 400)
            })
            add(buildJsonObject {
                put("modality", "IMAGE")
                put("tokenCount", 600)
            })
        })
    })
}.toString()
