package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.gemini.GeminiClient
import io.github.valerioisufi.pageboard.lecture.handwriting.HandwritingReader
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.handwriting.TextInkRecognizer
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.handwriting.InkReading
import io.github.valerioisufi.pageboard.handwriting.HandwritingReading
import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.lecture.handwriting.DeviceHandwriting
import io.github.valerioisufi.pageboard.model.ink.InkBlock
import io.github.valerioisufi.pageboard.model.ink.blockKey
import io.github.valerioisufi.pageboard.model.ink.groupStrokes
import io.github.valerioisufi.pageboard.lecture.local.OllamaClient
import io.github.valerioisufi.pageboard.lecture.model.GeminiBackend
import io.github.valerioisufi.pageboard.lecture.model.ModelBackends
import io.github.valerioisufi.pageboard.lecture.model.OllamaBackend
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HandwritingReaderTest {
    private val directory: File = Files.createTempDirectory("handwriting").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun strokesCloseToEachOtherAreOneBlock() {
        val blocks = groupStrokes(
            listOf(
                // Two lines of a paragraph
                Rect(10f, 50f, 60f, 56f),
                Rect(12f, 60f, 70f, 66f),
                // A note in the margin, far away
                Rect(150f, 20f, 190f, 26f),
                // A formula under the paragraph, after a blank space
                Rect(10f, 100f, 40f, 110f)
            )
        )
        assertEquals(listOf(listOf(2), listOf(0, 1), listOf(3)), blocks)
        assertEquals(blockKey(listOf("a", "b")), blockKey(setOf("b", "a")))
        assertTrue(blockKey(listOf("a", "b")) != blockKey(listOf("a", "b", "c")))
    }

    private class InkSource : NotebookSource {
        val rendered = mutableListOf<String>()
        override val documentTitle = "Analisi 1"
        override suspend fun strokeIds() = emptySet<String>()
        override suspend fun capture(knownStrokeIds: Set<String>, textPages: Int, imagePages: Int) =
            NotebookSnapshot(documentTitle, 1, listOf(0), emptyList())

        override suspend fun renderInk(block: InkBlock): ByteArray {
            rendered.add(block.key)
            return byteArrayOf(1)
        }
    }

    private fun geminiAnswer(payload: String) = buildJsonObject {
        put("candidates", buildJsonArray {
            add(buildJsonObject {
                put("content", buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", payload) }) })
                })
            })
        })
        put("usageMetadata", buildJsonObject {
            put("promptTokenCount", 300)
            put("candidatesTokenCount", 20)
        })
    }.toString()

    private fun block(key: String, strokes: Set<String>, top: Float) = InkBlock(0, "7", key, strokes, Rect(10f, top, 80f, top + 10f))

    @Test
    fun everyBlockIsReadOnceWhenTheStudentIsDoneWithIt() = runBlocking<Unit> {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            requests.add(request.body.toByteArray().decodeToString())
            respond(
                geminiAnswer("""{"blocks":[{"image":1,"text":"${'$'}\\lim_{x \\to a} f(x) = L${'$'}"}]}"""),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val backends = ModelBackends(GeminiBackend(GeminiClient(HttpClient(engine))) { "key" }, OllamaBackend(OllamaClient(HttpClient(engine))) { "" })
        val task = backends.forTask(AssistantSettings(), LectureTask.LIVE_NOTES)
        val path = File(directory, "handwriting.json").absolutePath
        val reader = HandwritingReader(path)
        val source = InkSource()
        val page = PageContent(0, pageId = "7", slide = SlideText(1, 0, "Limiti di funzioni"), hasInk = true)
        val old = block("old", setOf("s1"), top = 10f)
        val new = block("new", setOf("s2"), top = 40f)

        // First capture: only the block written before the lecture
        val usage = reader.read(listOf(new, old), listOf(page), source, task, max = 6, stableOnly = true, initialStrokeIds = setOf("s1"))
        assertEquals(listOf("old"), source.rendered)
        assertEquals(1, usage?.requests)
        assertTrue(requests.single().contains("written over a slide that reads: \\\"Limiti di funzioni\\\""), requests.single())
        val partly = reader.fill(page, listOf(new, old))
        assertEquals(listOf("\$\\lim_{x \\to a} f(x) = L\$"), partly.handwriting)
        assertFalse(partly.inkTranscribed)

        // Unchanged since the previous capture: read now; the first one is not read again
        reader.read(listOf(new, old), listOf(page), source, task, max = 6, stableOnly = true)
        assertEquals(listOf("old", "new"), source.rendered)
        assertTrue(reader.fill(page, listOf(new, old)).inkTranscribed)

        // Kept for the next lectures of the notebook
        val again = HandwritingReader(path)
        assertTrue(again.isRead(old) && again.isRead(new))
        assertNull(again.read(listOf(new, old), listOf(page), source, task, max = 6, stableOnly = false))
        assertEquals(2, requests.size)
    }

    /** A notebook whose blocks "a" and "b" have strokes, "image" only an image. */
    private class SampledSource : NotebookSource {
        val rendered = mutableListOf<String>()
        override val documentTitle = "Fisica"
        override suspend fun strokeIds() = emptySet<String>()
        override suspend fun capture(knownStrokeIds: Set<String>, textPages: Int, imagePages: Int) =
            NotebookSnapshot(documentTitle, 1, listOf(0), emptyList())

        override suspend fun renderInk(block: InkBlock): ByteArray {
            rendered.add(block.key)
            return byteArrayOf(1)
        }

        override suspend fun inkSample(block: InkBlock): InkSample? = if (block.key == "image") null else InkSample(
            (0 until (if (block.key == "a") 3 else 4)).map { i -> (0..6).map { k -> StrokePoint(10f + i * 4f + k * 0.4f, if (k % 2 == 0) 0f else 4f, 0.5f, k * 8L) } }
        )
    }

    /** Reads a line as the number of its strokes. */
    private class DeviceReader : TextInkRecognizer {
        var reads = 0
        override val version = "device 1"
        override suspend fun languages() = setOf("it")
        override suspend fun recognize(ink: InkSample, language: String, context: String): InkReading {
            reads++
            return InkReading("${ink.strokes.size} tratti")
        }
    }

    @Test
    fun theDeviceReadsTheBlocksWithStrokesAndTheModelOnlyTheOthers() = runBlocking<Unit> {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val engine = MockEngine { request ->
            requests.add(request.body.toByteArray().decodeToString())
            respond(geminiAnswer("""{"blocks":[{"image":1,"text":"figura"}]}"""), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val backends = ModelBackends(GeminiBackend(GeminiClient(HttpClient(engine))) { "key" }, OllamaBackend(OllamaClient(HttpClient(engine))) { "" })
        val task = backends.forTask(AssistantSettings(), LectureTask.LIVE_NOTES)
        val path = File(directory, "handwriting.json").absolutePath
        val device = DeviceReader()
        val reader = HandwritingReader(path, device = { DeviceHandwriting(HandwritingReading(HandwritingEngines(listOf(device))), "it-IT") })
        val source = SampledSource()
        val page = PageContent(0, pageId = "7", hasInk = true)
        val blocks = listOf(block("a", setOf("s1"), 10f), block("b", setOf("s2"), 40f), block("image", setOf("s3"), 70f))

        assertTrue(reader.readsOnDevice())
        reader.read(blocks, listOf(page), source, task, max = 6, stableOnly = false)
        assertEquals(2, device.reads, "the blocks with strokes read on the device")
        assertEquals(listOf("image"), source.rendered, "only the block without strokes sent as an image")
        assertEquals(1, requests.size)
        assertEquals(listOf("3 tratti", "4 tratti", "figura"), reader.fill(page, blocks).handwriting)

        // The file as before: a text and its page by key of block
        val saved = HandwritingReader.readFile(path)
        assertEquals(mapOf("a" to HandwritingReader.Entry("7", "3 tratti"), "b" to HandwritingReader.Entry("7", "4 tratti"), "image" to HandwritingReader.Entry("7", "figura")), saved)

        // Without a model, the device still reads
        val alone = HandwritingReader(File(directory, "alone.json").absolutePath, device = { DeviceHandwriting(HandwritingReading(HandwritingEngines(listOf(device))), "it") })
        assertNull(alone.read(blocks, listOf(page), source, task = null, max = 6, stableOnly = false))
        assertTrue(alone.isRead(blocks[0]) && alone.isRead(blocks[1]) && !alone.isRead(blocks[2]))
    }
}

