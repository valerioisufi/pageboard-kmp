package io.github.valerioisufi.pageboard.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An export asked by the user: [contents] in [format] with [options], written by [writers] to
 * [path], then given with [deliver] to where the user chose (see `FileTarget`); [fileName] is what
 * the user sees of it.
 */
public class ExportRequest(
    public val contents: ExportContents,
    public val format: ExportFormat,
    public val options: ExportOptions,
    public val writers: ExportWriters,
    public val path: String,
    public val fileName: String,
    public val deliver: suspend (written: String) -> Unit = {}
)

/** Where an export is. */
public sealed interface ExportJobState {
    /** Waiting for the exports asked before it. */
    public data object Queued : ExportJobState

    /** Being written: [progress]. */
    public class Running(public val progress: ExportProgress?) : ExportJobState

    /** Written and delivered: [outcome], the file at [path]. */
    public class Done(public val outcome: ExportOutcome, public val path: String) : ExportJobState

    /** Failed because of [failure]. */
    public class Failed(public val failure: ExportFailure) : ExportJobState

    /** Stopped by the user. */
    public data object Cancelled : ExportJobState
}

/** An export of [request] (numbered [id]) in its [state]. */
public class ExportJob(public val id: Long, public val request: ExportRequest, public val state: ExportJobState) {
    /** Whether it is queued or running. */
    public val isActive: Boolean get() = state is ExportJobState.Queued || state is ExportJobState.Running
}

/**
 * The exports of an application (the Controller of the exports once asked): they run one after
 * the other in [scope], outliving the sheet that asked them, and tell where they are in [jobs]
 * until they are dismissed.
 *
 * @param run writes a request (by default [DocumentExport.export]), telling its progress.
 */
public class ExportJobs(
    private val scope: CoroutineScope,
    private val run: suspend (ExportRequest, progress: (ExportProgress) -> Unit) -> ExportOutcome
) {
    /** The exports of the documents of [export]. */
    public constructor(export: DocumentExport, scope: CoroutineScope) : this(scope, { request, progress ->
        export.export(request.contents, request.format, request.options, request.writers, request.path, progress)
    })

    private val state = MutableStateFlow<List<ExportJob>>(emptyList())

    /** The exports asked and not dismissed, the oldest first. */
    public val jobs: StateFlow<List<ExportJob>> = state.asStateFlow()

    /** The coroutines of the exports queued or running, by id (changed from the coroutines too). */
    private val running = MutableStateFlow<Map<Long, Job>>(emptyMap())
    private val queue = Mutex()
    private var nextId = 1L

    /** Starts exporting [request] once the exports asked before it are done; returns its id. */
    public fun start(request: ExportRequest): Long {
        val id = nextId++
        state.update { it + ExportJob(id, request, ExportJobState.Queued) }
        launch(id, request)
        return id
    }

    /** Stops the export [id], queued or running. */
    public fun cancel(id: Long) {
        running.value[id]?.cancel()
        set(id) { if (it.isActive) ExportJobState.Cancelled else it.state }
    }

    /** Starts again the export [id], failed or cancelled. */
    public fun retry(id: Long) {
        val job = state.value.firstOrNull { it.id == id } ?: return
        if (job.isActive) return
        set(id) { ExportJobState.Queued }
        launch(id, job.request)
    }

    /** Forgets the export [id] (stopping it when it is still going). */
    public fun dismiss(id: Long) {
        running.value[id]?.cancel()
        state.update { jobs -> jobs.filterNot { it.id == id } }
    }

    private fun launch(id: Long, request: ExportRequest) {
        val job = scope.launch {
            queue.withLock {
                setActive(id, ExportJobState.Running(null))
                val result = try {
                    val outcome = run(request) { progress -> setActive(id, ExportJobState.Running(progress)) }
                    request.deliver(request.path)
                    ExportJobState.Done(outcome, request.path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("Pageboard: cannot export ${request.fileName}: ${e.message}")
                    ExportJobState.Failed(ExportFailure.of(e))
                }
                setActive(id, result)
            }
        }
        running.update { it + (id to job) }
        job.invokeOnCompletion { running.update { jobs -> if (jobs[id] === job) jobs - id else jobs } }
    }

    /** Sets the state of the export [id] to [next] unless it was cancelled meanwhile. */
    private fun setActive(id: Long, next: ExportJobState) = set(id) { if (it.isActive) next else it.state }

    /** Sets the state of the export [id] to what [next] gives from it, when it is still listed. */
    private fun set(id: Long, next: (ExportJob) -> ExportJobState) {
        state.update { jobs -> jobs.map { if (it.id == id) ExportJob(id, it.request, next(it)) else it } }
    }
}
