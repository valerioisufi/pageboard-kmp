package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.AudioCapture
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.transcription.Transcriber
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import kotlin.random.Random

// The microphone, the notebook, the transcription and the answers of Gemini of the tests

internal class FakeMicrophone : AudioCapture {
    var onSamples: ((ShortArray) -> Unit)? = null
    override fun start(onSamples: (ShortArray) -> Unit) {
        this.onSamples = onSamples
    }

    override fun stop() {
        onSamples = null
    }

    fun speak(seconds: Double, loud: Boolean, random: Random, quietLevel: Int = 0) {
        val total = (seconds * LECTURE_SAMPLE_RATE).toInt()
        var sent = 0
        while (sent < total) {
            val size = minOf(LECTURE_SAMPLE_RATE / 10, total - sent)
            onSamples?.invoke(
                ShortArray(size) {
                    when {
                        loud -> random.nextInt(-6000, 6000).toShort()
                        quietLevel > 0 -> random.nextInt(-quietLevel, quietLevel).toShort()
                        else -> 0
                    }
                }
            )
            sent += size
        }
    }
}

internal class FakeNotebook : NotebookSource {
    override val documentTitle = "Analisi 1"
    override suspend fun strokeIds() = setOf("old")
    override suspend fun capture(knownStrokeIds: Set<String>, textPages: Int, imagePages: Int) = NotebookSnapshot(
        documentTitle = documentTitle,
        pageCount = 3,
        visiblePageIndexes = listOf(1),
        pages = listOf(
            PageContent(1, pageId = "11", slide = SlideText(1, 0, "Limiti"), hasInk = true),
            PageContent(2, pageId = "12", slide = SlideText(1, 1, "Derivate"))
        ),
        pageImages = listOf(PageImage(1, byteArrayOf(1, 2, 3))),
        strokeIds = setOf("old", "new"),
        pageIds = listOf("10", "11", "12")
    )
}

internal class FakeTranscriber : Transcriber {
    var closed = false
    val prompts = Collections.synchronizedList(mutableListOf<String>())
    override suspend fun transcribe(wav: ByteArray, language: String, prompt: String): List<TranscriptSegment> {
        prompts.add(prompt)
        return listOf(TranscriptSegment(2_000, 5_000, "Il teorema ponte"), TranscriptSegment(5_000, 9_000, "vale per ogni successione"))
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
                put("modality", "AUDIO")
                put("tokenCount", 600)
            })
        })
    })
}.toString()
