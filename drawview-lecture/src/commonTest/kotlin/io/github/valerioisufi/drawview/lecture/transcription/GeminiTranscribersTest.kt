package io.github.valerioisufi.drawview.lecture.transcription

import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.model.GeminiBackend
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiTranscribersTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun backend(handler: MockRequestHandler) = GeminiBackend(GeminiClient(HttpClient(MockEngine(handler)))) { "key" }

    /** Two seconds of audio. */
    private val wav = Wav.encode(ShortArray(2 * LECTURE_SAMPLE_RATE))

    @Test
    fun theSpeechModelReceivesTheAudioTheLanguageAndTheTerms() = runTest {
        val bodies = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val transcriber = GeminiSpeechTranscriber(backend { request ->
            urls.add(request.url.encodedPath)
            bodies.add(request.body.toByteArray().decodeToString())
            // As the model answers (August 2026): the text in the transcription of the part
            respond(
                """{"candidates":[{"content":{"role":"model","parts":[{"audioTranscription":{"text":"Una funzione hash resistente alle collisioni."}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":64,"candidatesTokenCount":12}}""",
                HttpStatusCode.OK,
                jsonHeaders
            )
        }, "gemini-3.5-transcribe")

        val result = transcriber.transcribe(wav, SpeechHints(language = "it", locales = listOf("it-IT"), vocabulary = listOf("HMAC", "SHA-256")))

        assertEquals(listOf(TranscriptSegment(0, 2_000, "Una funzione hash resistente alle collisioni.")), result.segments)
        assertEquals(64, result.usage?.promptTokens)
        assertEquals("/v1beta/models/gemini-3.5-transcribe:generateContent", urls.single())
        val request = Json.parseToJsonElement(bodies.single()).jsonObject
        val config = request["generationConfig"]!!.jsonObject["audioTranscriptionConfig"]!!.jsonObject
        assertEquals(listOf("it-IT"), config["languageCodes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("HMAC", "SHA-256"), config["customVocabulary"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("SMART", config["mode"]!!.jsonPrimitive.content)
        // The audio only: no instructions, no schema
        assertFalse("systemInstruction" in request)
        assertFalse("responseJsonSchema" in request["generationConfig"]!!.jsonObject)
        assertTrue(bodies.single().contains("\"mimeType\":\"audio/wav\""))
    }

    @Test
    fun theWordsOrATextPartAreReadWhenThereIsNoText() = runTest {
        val transcriber = GeminiSpeechTranscriber(backend {
            respond(
                """{"candidates":[{"content":{"parts":[{"audioTranscription":{"words":[{"word":"Buongiorno","startOffset":"0.1s"},{"word":"a tutti.","startOffset":"0.6s"}]}}]}}]}""",
                HttpStatusCode.OK,
                jsonHeaders
            )
        }, "gemini-3.5-transcribe")
        assertEquals("Buongiorno a tutti.", transcriber.transcribe(wav, SpeechHints()).segments.single().text)
        val asText = GeminiSpeechTranscriber(backend {
            respond("""{"candidates":[{"content":{"parts":[{"text":"Buonasera."}]}}]}""", HttpStatusCode.OK, jsonHeaders)
        }, "gemini-3.5-transcribe")
        assertEquals("Buonasera.", asText.transcribe(wav, SpeechHints()).segments.single().text)
    }

    @Test
    fun anUnknownLanguageIsRecognizedInsteadAndTemporaryErrorsAreRepeated() = runTest {
        val bodies = mutableListOf<String>()
        var temporaryErrors = 1
        val transcriber = GeminiSpeechTranscriber(backend { request ->
            val body = request.body.toByteArray().decodeToString()
            bodies.add(body)
            when {
                "\"it-IT\"" in body -> respond("""{"error":{"code":400,"message":"Unsupported language code","status":"INVALID_ARGUMENT"}}""", HttpStatusCode.BadRequest, jsonHeaders)
                temporaryErrors-- > 0 -> respond("""{"error":{"code":503,"message":"overloaded","status":"UNAVAILABLE"}}""", HttpStatusCode.ServiceUnavailable, jsonHeaders)
                else -> respond("""{"candidates":[{"content":{"parts":[{"text":"Ciao."}]}}]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }, "gemini-3.5-transcribe", retryDelayMs = 1)
        assertEquals("Ciao.", transcriber.transcribe(wav, SpeechHints(locales = listOf("it-IT"))).segments.single().text)
        assertEquals(3, bodies.size)
        // Without languages: recognized
        assertFalse(bodies.last().contains("languageCodes"))

        val refused = GeminiSpeechTranscriber(backend {
            respond("""{"error":{"code":403,"message":"API key not valid","status":"PERMISSION_DENIED"}}""", HttpStatusCode.Forbidden, jsonHeaders)
        }, "gemini-3.5-transcribe", retryDelayMs = 1)
        assertTrue(assertFailsWith<GeminiException> { refused.transcribe(wav, SpeechHints()) }.isAuthenticationError)
    }

    @Test
    fun theWaitAskedByTheApiIsRespectedWhenTheRequestsAreTooMany() = runTest {
        var refused = 6
        val transcriber = GeminiSpeechTranscriber(backend {
            if (refused-- > 0) {
                respond(
                    """{"error":{"code":429,"message":"You exceeded your current quota. Quota exceeded for metric: generate_content_paid_tier_input_token_count, limit: 10000. Please retry in 39.999837456s.","status":"RESOURCE_EXHAUSTED"}}""",
                    HttpStatusCode.TooManyRequests,
                    jsonHeaders
                )
            } else {
                respond("""{"candidates":[{"content":{"parts":[{"audioTranscription":{"text":"Eccomi."}}]}}]}""", HttpStatusCode.OK, jsonHeaders)
            }
        }, "gemini-3.5-transcribe", retryDelayMs = 1)
        val started = testScheduler.currentTime
        // More refusals than the usual attempts: still repeated, waiting as asked
        assertEquals("Eccomi.", transcriber.transcribe(wav, SpeechHints()).segments.single().text)
        assertEquals(6 * 40_499L, testScheduler.currentTime - started)
    }

    @Test
    fun aGeneralModelTranscribesWithInstructions() = runTest {
        var body = ""
        val transcriber = GeminiPromptTranscriber(backend { request ->
            body = request.body.toByteArray().decodeToString()
            respond("""{"candidates":[{"content":{"parts":[{"text":"{\"transcript\":\"Il MAC garantisce l'integrità.\"}"}]}}]}""", HttpStatusCode.OK, jsonHeaders)
        }, "gemini-3.8-flash-lite", { AssistantSettings(spokenLanguage = "it") })
        val result = transcriber.transcribe(wav, SpeechHints(courseContext = "Sicurezza informatica"))
        assertEquals("Il MAC garantisce l'integrità.", result.segments.single().text)
        assertTrue(body.contains("It is spoken in Italian."), body)
        assertTrue(body.contains("Sicurezza informatica"))
    }
}
