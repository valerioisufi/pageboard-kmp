package io.github.valerioisufi.pageboard.ml

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ModelDownloadsTest {
    private val directory = newTestDirectory()
    private val bytes = Random(7).nextBytes(1_300_000)
    private val spec = testSpec(bytes)
    private val models = InstalledModels(ModelCatalog(listOf(spec)), directory)
    private val requests = mutableListOf<HttpRequestData>()

    @AfterTest
    fun deleteFiles() = deleteDirectory(directory)

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(
        MockEngine { request ->
            requests.add(request)
            handler(request)
        }
    ) {
        expectSuccess = false
        install(HttpTimeout)
    }

    /** A server that sends the file, or its rest when asked for a range. */
    private fun server() = client { request -> serve(request) }

    private fun MockRequestHandleScope.serve(request: HttpRequestData): HttpResponseData {
        val range = request.headers[HttpHeaders.Range]
        return if (range != null) {
            val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
            respond(ByteReadChannel(bytes.copyOfRange(from, bytes.size)), HttpStatusCode.PartialContent)
        } else {
            respond(ByteReadChannel(bytes), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, bytes.size.toString()))
        }
    }

    @Test
    fun aModelIsDownloadedVerifiedAndInstalled() = runTest {
        val downloads = ModelDownloads(models, server(), this)
        downloads.download(spec)!!.join()

        assertContentEquals(bytes, readFile(models.pathOf(spec)))
        assertFalse(exists(models.partialPathOf(spec)))
        assertEquals(setOf(spec.id), models.installed.value)
        assertEquals(emptyMap(), downloads.progress.value)
        assertEquals(emptyMap(), downloads.failures.value)
        // Installed: nothing to download
        assertNull(downloads.download(spec))
    }

    @Test
    fun anInterruptedDownloadIsResumedWhereItStopped() = runTest {
        writeFile(models.partialPathOf(spec), bytes.copyOf(400_000))
        val downloads = ModelDownloads(models, server(), this)
        downloads.download(spec)!!.join()

        assertEquals("bytes=400000-", requests.single().headers[HttpHeaders.Range])
        assertContentEquals(bytes, readFile(models.pathOf(spec)))
        assertNotNull(models[spec])
    }

    @Test
    fun aServerSendingTheWholeFileAgainIsWrittenFromTheStart() = runTest {
        writeFile(models.partialPathOf(spec), bytes.copyOf(400_000))
        val downloads = ModelDownloads(models, client { respond(ByteReadChannel(bytes), HttpStatusCode.OK) }, this)
        downloads.download(spec)!!.join()

        assertContentEquals(bytes, readFile(models.pathOf(spec)))
    }

    @Test
    fun aFileThatDoesNotMatchIsNotKept() = runTest {
        val unrelated = "$directory/notes.txt"
        writeFile(unrelated, byteArrayOf(1, 2, 3))
        val wrong = bytes.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        val downloads = ModelDownloads(models, client { respond(ByteReadChannel(wrong), HttpStatusCode.OK) }, this)
        downloads.download(spec)!!.join()

        assertFalse(exists(models.pathOf(spec)))
        assertFalse(exists(models.partialPathOf(spec)))
        assertTrue(downloads.failures.value.getValue(spec.id).isNotEmpty())
        assertEquals(emptySet(), models.installed.value)
        assertContentEquals(byteArrayOf(1, 2, 3), readFile(unrelated))
    }

    @Test
    fun whatArrivedOfAnIncompleteDownloadStaysToBeResumed() = runTest {
        var connectionDrops = true
        val downloads = ModelDownloads(
            models,
            client { request ->
                if (connectionDrops) respond(ByteReadChannel(bytes.copyOf(700_000)), HttpStatusCode.OK) else serve(request)
            },
            this
        )
        downloads.download(spec)!!.join()

        assertFalse(exists(models.pathOf(spec)))
        assertEquals(700_000, readFile(models.partialPathOf(spec))?.size)
        assertTrue(spec.id in downloads.failures.value)

        // The next download asks for the rest only, and the failure is forgotten
        connectionDrops = false
        downloads.download(spec)!!.join()
        assertEquals("bytes=700000-", requests.last().headers[HttpHeaders.Range])
        assertContentEquals(bytes, readFile(models.pathOf(spec)))
        assertEquals(emptyMap(), downloads.failures.value)
    }

    @Test
    fun aServerErrorIsSaidAndNothingIsWritten() = runTest {
        val downloads = ModelDownloads(models, client { respond("", HttpStatusCode.NotFound) }, this)
        downloads.download(spec)!!.join()

        assertEquals("HTTP 404", downloads.failures.value[spec.id])
        assertFalse(exists(models.pathOf(spec)))
        assertFalse(exists(models.partialPathOf(spec)))
    }
}
