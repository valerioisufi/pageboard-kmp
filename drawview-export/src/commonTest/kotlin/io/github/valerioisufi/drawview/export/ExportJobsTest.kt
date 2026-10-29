package io.github.valerioisufi.drawview.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ExportJobsTest {
    private val contents = ExportContents(ExportScope.Folder(null), null, emptyList(), 0)

    private fun request(name: String, delivered: MutableList<String> = mutableListOf()) = ExportRequest(
        contents = contents,
        format = ExportFormat.COMBINED_PDF,
        options = ExportOptions(),
        writers = ExportWriters(PdfExporter(null)),
        path = "/tmp/$name",
        fileName = name,
        deliver = { delivered += it }
    )

    @Test
    fun theExportsRunOneAfterTheOtherAndAreDelivered() = runTest {
        val gates = mapOf("a" to CompletableDeferred<Unit>(), "b" to CompletableDeferred())
        val delivered = mutableListOf<String>()
        val jobs = ExportJobs(backgroundScope) { request, progress ->
            progress(ExportProgress(0.5f, 1, 2, request.fileName))
            gates.getValue(request.fileName).await()
            ExportOutcome(documents = 2, pages = 3, skipped = 0)
        }
        val a = jobs.start(request("a", delivered))
        val b = jobs.start(request("b", delivered))
        runCurrent()
        val running = assertIs<ExportJobState.Running>(jobs.jobs.value.first { it.id == a }.state)
        assertEquals("a", running.progress?.name)
        assertEquals(ExportJobState.Queued, jobs.jobs.value.first { it.id == b }.state)

        gates.getValue("a").complete(Unit)
        runCurrent()
        val done = assertIs<ExportJobState.Done>(jobs.jobs.value.first { it.id == a }.state)
        assertEquals(3, done.outcome.pages)
        assertEquals(listOf("/tmp/a"), delivered)
        assertIs<ExportJobState.Running>(jobs.jobs.value.first { it.id == b }.state)

        gates.getValue("b").complete(Unit)
        runCurrent()
        assertEquals(listOf("/tmp/a", "/tmp/b"), delivered)
        jobs.dismiss(a)
        assertEquals(listOf(b), jobs.jobs.value.map { it.id })
    }

    @Test
    fun aCancelledExportStopsAndCanBeRetried() = runTest {
        var gate = CompletableDeferred<Unit>()
        val jobs = ExportJobs(backgroundScope) { _, _ ->
            gate.await()
            ExportOutcome(1, 1, 0)
        }
        val id = jobs.start(request("a"))
        runCurrent()
        jobs.cancel(id)
        runCurrent()
        assertEquals(ExportJobState.Cancelled, jobs.jobs.value.single().state)

        gate = CompletableDeferred()
        jobs.retry(id)
        runCurrent()
        assertTrue(jobs.jobs.value.single().isActive)
        gate.complete(Unit)
        runCurrent()
        assertIs<ExportJobState.Done>(jobs.jobs.value.single().state)
    }

    @Test
    fun aFailureTellsItsCause() = runTest {
        val jobs = ExportJobs(backgroundScope) { _, _ -> throw ExportException(ExportFailure.NO_SPACE) }
        jobs.start(request("a"))
        runCurrent()
        assertEquals(ExportFailure.NO_SPACE, assertIs<ExportJobState.Failed>(jobs.jobs.value.single().state).failure)
    }
}
