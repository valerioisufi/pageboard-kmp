package io.github.valerioisufi.drawview.lecture

import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.gemini.GeminiLiveClient
import io.github.valerioisufi.drawview.lecture.gemini.LiveConnection
import io.github.valerioisufi.drawview.lecture.gemini.LiveConnector
import io.github.valerioisufi.drawview.lecture.transcription.GeminiLiveTranscriber
import io.github.valerioisufi.drawview.lecture.transcription.SpeechHints
import io.github.valerioisufi.drawview.lecture.transcription.StreamingTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The live transcription against a fake Live API: sessions, times, reconnection. */
class GeminiLiveTranscriberTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() = scope.cancel()

    private class FakeConnection : LiveConnection {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val server = Channel<String>(Channel.UNLIMITED)
        var error: GeminiException? = null
        var closed = false
        override suspend fun send(message: String) {
            sent.add(message)
        }

        override val incoming: ReceiveChannel<String> get() = server
        override suspend fun closeError() = error
        override suspend fun close() {
            closed = true
            server.close()
        }

        /** The server closes the connection with [error]. */
        fun drop(error: GeminiException?) {
            this.error = error
            server.close()
        }

        /** Milliseconds of audio received. */
        fun audioMs(): Long = synchronized(sent) { sent.toList() }.filter { "realtimeInput" in it && "data" in it }.sumOf { message ->
            val data = Json.parseToJsonElement(message).jsonObject["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject["data"]!!.jsonPrimitive.content
            Base64.getDecoder().decode(data).size / 2L * 1000 / LECTURE_SAMPLE_RATE
        }
    }

    private class FakeConnector(var failure: GeminiException? = null) : LiveConnector {
        val connections = Collections.synchronizedList(mutableListOf<FakeConnection>())
        val keys = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun connect(apiKey: String): LiveConnection {
            keys.add(apiKey)
            failure?.let { throw it }
            return FakeConnection().also { connections.add(it) }
        }
    }

    private class Results : StreamingTranscriber.Listener {
        val finals = Collections.synchronizedList(mutableListOf<TranscriptSegment>())
        val interims = Collections.synchronizedList(mutableListOf<String>())
        val problems = Collections.synchronizedList(mutableListOf<Pair<Exception?, Int>>())
        val usage = Collections.synchronizedList(mutableListOf<TokenUsage>())
        override suspend fun onFinal(segment: TranscriptSegment) {
            finals.add(segment)
        }

        override suspend fun onInterim(text: String) {
            interims.add(text)
        }

        override suspend fun onUsage(usage: TokenUsage) {
            this.usage.add(usage)
        }

        override suspend fun onProblem(error: Exception?, retryInSeconds: Int) {
            problems.add(error to retryInSeconds)
        }
    }

    private fun speech(seconds: Double) = ShortArray((seconds * LECTURE_SAMPLE_RATE).toInt()) { (it % 200 - 100).toShort() }

    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    private val hints = SpeechHints(locales = listOf("it-IT"), vocabulary = listOf("HMAC", "IND-CPA"))

    @Test
    fun theWordsArriveWhileTheyAreSaid() = runBlocking<Unit> {
        val connector = FakeConnector()
        val results = Results()
        val transcriber = GeminiLiveTranscriber(connector, { "key-1" }, "gemini-3.5-transcribe-live")
        transcriber.start(scope, { hints }, results)
        // Received before the setup is done: kept
        transcriber.push(speech(1.0))
        until { connector.connections.size == 1 && connector.connections[0].sent.isNotEmpty() }
        val connection = connector.connections.single()
        assertEquals("key-1", connector.keys.single())
        val setup = Json.parseToJsonElement(connection.sent.single()).jsonObject["setup"]!!.jsonObject
        assertEquals("models/gemini-3.5-transcribe-live", setup["model"]!!.jsonPrimitive.content)
        val config = setup["inputAudioTranscription"]!!.jsonObject
        assertEquals("""["it-IT"]""", config["languageCodes"].toString())
        assertEquals("""["HMAC","IND-CPA"]""", config["customVocabulary"].toString())
        assertEquals("SMART", config["mode"]!!.jsonPrimitive.content)
        assertEquals("""["TEXT"]""", setup["generationConfig"]!!.jsonObject["responseModalities"].toString())

        connection.server.send("""{"setupComplete":{}}""")
        until { connection.audioMs() == 1_000L }
        // In pieces of 100 ms
        assertEquals(11, connection.sent.size)
        assertTrue(connection.sent[1].contains("\"mimeType\":\"audio/pcm;rate=16000\""))

        transcriber.push(speech(2.0))
        until { connection.audioMs() == 3_000L }
        connection.server.send("""{"serverContent":{"interimInputTranscription":{"text":"Buongiorno a"}}}""")
        until { results.interims.contains("Buongiorno a") }
        transcriber.push(speech(1.0))
        until { connection.audioMs() == 4_000L }
        connection.server.send("""{"serverContent":{"inputTranscription":{"text":"Buongiorno a tutti."}},"usageMetadata":{"promptTokenCount":120,"responseTokenCount":8}}""")
        until { results.finals.isNotEmpty() }
        // Times estimated from the audio sent: the interim a second late, the final after a pause
        assertEquals(TranscriptSegment(2_000, 3_300, "Buongiorno a tutti."), results.finals.single())
        assertEquals("", results.interims.last())

        // Up to the final: nothing to wait for; later, until the text arrives or the time is over
        withTimeout(1_000) { transcriber.awaitFinal(3_000, 5_000) }
        val waited = System.currentTimeMillis()
        transcriber.awaitFinal(10_000, 200)
        assertTrue(System.currentTimeMillis() - waited >= 200)

        // The end of the audio: the last words become final
        transcriber.endOfAudio()
        until { connection.sent.last().contains("\"audioStreamEnd\":true") }
        transcriber.close()
        assertTrue(connection.closed)
        assertEquals(listOf(TokenUsage(requests = 1, promptTokens = 120, outputTokens = 8)), results.usage)
    }

    @Test
    fun theTimesOfTheServerAreUsedWhenGiven() = runBlocking<Unit> {
        val connector = FakeConnector()
        val results = Results()
        val transcriber = GeminiLiveTranscriber(connector, { "key" }, "gemini-3.5-transcribe-live")
        transcriber.start(scope, { hints }, results)
        until { connector.connections.isNotEmpty() }
        val connection = connector.connections.single()
        connection.server.send("""{"setupComplete":{}}""")
        transcriber.push(speech(5.0))
        until { connection.audioMs() == 5_000L }
        connection.server.send("""{"serverContent":{"inputTranscription":{"text":"Una funzione hash.","startOffset":"1.250s","endOffset":"3.5s"}}}""")
        until { results.finals.isNotEmpty() }
        assertEquals(TranscriptSegment(1_250, 3_500, "Una funzione hash."), results.finals.single())
        transcriber.close()
    }

    @Test
    fun theStartComesFromTheSpeechFoundByTheServer() = runBlocking<Unit> {
        val connector = FakeConnector()
        val results = Results()
        val transcriber = GeminiLiveTranscriber(connector, { "key" }, "gemini-3.5-transcribe-live")
        transcriber.start(scope, { hints }, results)
        until { connector.connections.isNotEmpty() }
        val connection = connector.connections.single()
        // As the server answers (August 2026): the activity found, the interim texts, the final one, the end of the activity
        connection.server.send("""{"setupComplete":{}}""")
        transcriber.push(speech(3.0))
        until { connection.audioMs() == 3_000L }
        connection.server.send("""{"serverContent":{},"voiceActivity":{"type":"ACTIVITY_START","audioOffset":"0.520s"}}""")
        connection.server.send("""{"serverContent":{"interimInputTranscription":{"text":"somma"}}}""")
        connection.server.send("""{"serverContent":{"inputTranscription":{"text":"Somma modulare."}}}""")
        connection.server.send("""{"serverContent":{"generationComplete":true}}""")
        connection.server.send("""{"serverContent":{},"voiceActivity":{"type":"ACTIVITY_END","audioOffset":"2.900s"}}""")
        until { results.finals.isNotEmpty() }
        assertEquals(TranscriptSegment(520, 2_300, "Somma modulare."), results.finals.single())
        // Final up to the end of the activity
        withTimeout(1_000) { transcriber.awaitFinal(2_900, 5_000) }
        transcriber.close()
        // No tokens from the server: those of the audio sent
        assertEquals(listOf(TokenUsage(requests = 1, promptTokens = 75, promptByModality = mapOf("AUDIO" to 75L))), results.usage)
    }

    @Test
    fun theSoftSpeechIsMadeLouder() {
        val level = io.github.valerioisufi.drawview.lecture.transcription.SpeechLevel()
        fun piece(amplitude: Int) = ShortArray(LECTURE_SAMPLE_RATE / 10) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }
        // Loud: as it is
        repeat(10) { level.level(piece(3_000)) }
        assertEquals(3_000, kotlin.math.abs(level.level(piece(3_000)).last().toInt()))
        // Soft for a while: louder, at most four times
        repeat(10) { level.level(piece(300)) }
        assertTrue(kotlin.math.abs(level.level(piece(300)).last().toInt()) in 1_150..1_200)
    }

    @Test
    fun aNewSessionStartsAtAPauseFromTheAudioNotFinal() = runBlocking<Unit> {
        val connector = FakeConnector()
        val results = Results()
        var vocabulary = listOf("HMAC")
        val transcriber = GeminiLiveTranscriber(connector, { "key" }, "gemini-3.5-transcribe-live", sessionAudioMs = 3_000, maxSessionAudioMs = 60_000)
        transcriber.start(scope, { SpeechHints(vocabulary = vocabulary) }, results)
        until { connector.connections.isNotEmpty() }
        val first = connector.connections[0]
        first.server.send("""{"setupComplete":{}}""")
        transcriber.push(speech(2.0))
        until { first.audioMs() == 2_000L }
        first.server.send("""{"serverContent":{"interimInputTranscription":{"text":"Il MAC"}}}""")
        until { results.interims.isNotEmpty() }
        // Past the length of a session while an utterance is said: not now
        transcriber.push(speech(2.0))
        until { first.audioMs() == 4_000L }
        assertEquals(1, connector.connections.size)
        first.server.send("""{"serverContent":{"inputTranscription":{"text":"Il MAC garantisce l'integrità.","startOffset":"0.5s","endOffset":"3.6s"}}}""")
        until { results.finals.size == 1 }

        // At the pause: a new session, with the slides of now, from the end of the final text
        vocabulary = listOf("HMAC", "CBC-MAC")
        transcriber.push(speech(0.5))
        until { connector.connections.size == 2 }
        assertTrue(first.closed)
        val second = connector.connections[1]
        until { second.sent.isNotEmpty() }
        assertTrue(second.sent[0].contains("\"customVocabulary\":[\"HMAC\",\"CBC-MAC\"]"), second.sent[0])
        second.server.send("""{"setupComplete":{}}""")
        // From 3.6 s to 4.5 s
        until { second.audioMs() == 900L }
        // The old session is ignored; the times of the new one start where it does
        first.server.trySend("""{"serverContent":{"inputTranscription":{"text":"doppione"}}}""")
        second.server.send("""{"serverContent":{"inputTranscription":{"text":"Vediamo come.","startOffset":"0.2s","endOffset":"0.8s"}}}""")
        until { results.finals.size == 2 }
        assertEquals(TranscriptSegment(3_800, 4_400, "Vediamo come."), results.finals[1])
        transcriber.close()
    }

    @Test
    fun aLostConnectionIsOpenedAgainWithTheAudioKept() = runBlocking<Unit> {
        val connector = FakeConnector()
        val results = Results()
        val transcriber = GeminiLiveTranscriber(connector, { "key" }, "gemini-3.5-transcribe-live", retryUnitMs = 20)
        transcriber.start(scope, { hints }, results)
        until { connector.connections.isNotEmpty() }
        val first = connector.connections[0]
        first.server.send("""{"setupComplete":{}}""")
        transcriber.push(speech(2.0))
        until { first.audioMs() == 2_000L }
        first.server.send("""{"serverContent":{"inputTranscription":{"text":"Prima frase.","startOffset":"0s","endOffset":"1s"}}}""")
        until { results.finals.size == 1 }

        first.drop(GeminiException(503, "Internal error"))
        until { results.problems.isNotEmpty() }
        assertEquals(1, results.problems.single().second)
        // Said while the connection is down: kept
        transcriber.push(speech(1.0))
        until { connector.connections.size == 2 }
        val second = connector.connections[1]
        second.server.send("""{"setupComplete":{}}""")
        // From the end of the last final text: 1 s to 3 s
        until { second.audioMs() == 2_000L }
        until { results.problems.size == 2 }
        assertNull(results.problems.last().first)
        transcriber.close()
    }

    @Test
    fun aKeyRefusedStopsTheTranscription() = runBlocking<Unit> {
        val connector = FakeConnector(failure = GeminiException(GeminiLiveClient.statusOf("API key not valid. Please pass a valid API key."), "API key not valid"))
        val results = Results()
        val transcriber = GeminiLiveTranscriber(connector, { "wrong" }, "gemini-3.5-transcribe-live", retryUnitMs = 20)
        transcriber.start(scope, { hints }, results)
        transcriber.push(speech(1.0))
        until { results.problems.isNotEmpty() }
        val (error, retry) = results.problems.single()
        assertTrue((error as GeminiException).isAuthenticationError)
        assertEquals(0, retry)
        // Nothing will become final: nothing to wait for
        withTimeout(1_000) { transcriber.awaitFinal(1_000, 5_000) }
        delay(100)
        assertEquals(1, connector.keys.size)
        transcriber.close()
    }

    @Test
    fun theClosingsOfTheServerAreClassified() {
        assertEquals(403, GeminiLiveClient.statusOf("API key not valid. Please pass a valid API key."))
        assertEquals(400, GeminiLiveClient.statusOf("models/gemini-x is not found for API version v1beta"))
        assertEquals(429, GeminiLiveClient.statusOf("You exceeded your current quota"))
        assertEquals(503, GeminiLiveClient.statusOf("Internal error encountered."))
    }
}
