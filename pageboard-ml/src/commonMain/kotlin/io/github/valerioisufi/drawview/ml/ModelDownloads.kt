package io.github.valerioisufi.drawview.ml

import io.github.valerioisufi.drawview.platform.Sha256
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** How far the download of a model is. */
public class DownloadProgress(public val downloadedBytes: Long, public val totalBytes: Long) {
    /** From 0 to 1. */
    public val fraction: Float get() = if (totalBytes > 0) (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * Downloads the models of [models] (Pure Fabrication), a file each, gigabytes long:
 *
 * * the file arrives under another name (`<file>.part`), its SHA-256 computed while it arrives;
 * * an interrupted download is resumed where it stopped (an HTTP range), the part already
 *   downloaded hashed again first;
 * * complete and matching its [ModelSpec.sha256], the file takes its name and the model is
 *   installed; a file that does not match is deleted, never kept.
 *
 * Nothing but the files of the model downloaded is written or deleted. A download goes on until
 * it ends or is [cancel]led (what arrived stays, to be resumed).
 */
public class ModelDownloads internal constructor(
    private val models: InstalledModels,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope
) {
    public constructor(
        models: InstalledModels,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    ) : this(models, defaultHttpClient(), scope)

    private val lock = SyncLock()
    private val running = mutableMapOf<String, Job>()
    private val progressState = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    private val failureState = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The downloads in progress, by id of model. */
    public val progress: StateFlow<Map<String, DownloadProgress>> = progressState.asStateFlow()

    /** Why the last download of a model failed, by id of model (cleared when it starts again). */
    public val failures: StateFlow<Map<String, String>> = failureState.asStateFlow()

    /**
     * Downloads [spec], or resumes its download.
     *
     * @return the download, `null` when the model is installed already or being downloaded.
     */
    public fun download(spec: ModelSpec): Job? {
        if (models[spec] != null) return null
        val job = syncLock(lock) {
            if (running[spec.id]?.isActive == true) return null
            scope.launch(start = CoroutineStart.LAZY) { run(spec) }.also { running[spec.id] = it }
        }
        failureState.update { it - spec.id }
        progressState.update { it + (spec.id to DownloadProgress(0, spec.sizeBytes)) }
        job.start()
        return job
    }

    /** Stops the download of [spec]: what arrived stays, and the next [download] resumes it. */
    public fun cancel(spec: ModelSpec) {
        syncLock(lock) { running[spec.id] }?.cancel()
    }

    private suspend fun run(spec: ModelSpec) {
        try {
            fetch(spec)
            models.added(spec)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failureState.update { it + (spec.id to (e.message ?: e::class.simpleName.orEmpty())) }
        } finally {
            progressState.update { it - spec.id }
            syncLock(lock) { running.remove(spec.id) }
        }
    }

    private suspend fun fetch(spec: ModelSpec) = withContext(Dispatchers.IO) {
        val target = Path(models.pathOf(spec))
        val partial = Path(models.partialPathOf(spec))
        target.parent?.let { SystemFileSystem.createDirectories(it) }

        var offset = SystemFileSystem.metadataOrNull(partial)?.size ?: 0L
        if (offset > spec.sizeBytes) offset = 0L
        var hash = if (offset > 0) hashOf(partial) else Sha256()
        // A part as long as the file (the rename did not happen): it is verified only
        if (offset < spec.sizeBytes) {
            httpClient.prepareGet(spec.url) {
                if (offset > 0) header(HttpHeaders.Range, "bytes=$offset-")
                // Gigabytes: as long as it takes, as long as bytes arrive
                timeout {
                    requestTimeoutMillis = Long.MAX_VALUE
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                }
            }.execute { response ->
                when (response.status) {
                    HttpStatusCode.PartialContent -> {}
                    // The server sends the whole file: written again from its start
                    HttpStatusCode.OK -> if (offset > 0) {
                        offset = 0L
                        hash = Sha256()
                    }
                    else -> {
                        // A range refused: the part does not belong to this file
                        if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) SystemFileSystem.delete(partial, mustExist = false)
                        throw ModelException("HTTP ${response.status.value}")
                    }
                }
                val channel = response.bodyAsChannel()
                var written = offset
                var reported = offset
                SystemFileSystem.sink(partial, append = offset > 0).buffered().use { sink ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        hash.update(buffer, 0, read)
                        written += read
                        if (written - reported >= REPORT_BYTES) {
                            reported = written
                            progressState.update { it + (spec.id to DownloadProgress(written, spec.sizeBytes)) }
                        }
                    }
                }
                if (written != spec.sizeBytes) throw ModelException("Incomplete download ($written of ${spec.sizeBytes} bytes)")
            }
        }
        if (hash.digestHex() != spec.sha256) {
            SystemFileSystem.delete(partial, mustExist = false)
            throw ModelException("The file downloaded is not the one of ${spec.name}")
        }
        SystemFileSystem.atomicMove(partial, target)
    }

    private fun hashOf(path: Path): Sha256 {
        val hash = Sha256()
        SystemFileSystem.source(path).buffered().use { source ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = source.readAtMostTo(buffer, 0, buffer.size)
                if (read == -1) break
                hash.update(buffer, 0, read)
            }
        }
        return hash
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024

        /** The progress is said every 4 MB. */
        const val REPORT_BYTES = 4L * 1024 * 1024

        const val SOCKET_TIMEOUT_MS = 60_000L

        fun defaultHttpClient(): HttpClient = HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
            }
        }
    }
}
