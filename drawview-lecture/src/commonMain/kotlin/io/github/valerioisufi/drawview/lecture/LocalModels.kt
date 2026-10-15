package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.transcription.WhisperTranscriber
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** A speech recognition model of whisper.cpp offered by the settings (from huggingface.co/ggerganov/whisper.cpp). */
public enum class WhisperModel(public val fileName: String, public val sizeBytes: Long) {
    /** Accurate and light: the default. */
    LARGE_V3_TURBO_Q5("ggml-large-v3-turbo-q5_0.bin", 574_041_195),
    LARGE_V3_TURBO("ggml-large-v3-turbo.bin", 1_624_555_275),
    SMALL("ggml-small.bin", 487_601_967),
    BASE("ggml-base.bin", 147_951_465);

    public val label: String get() = fileName.removePrefix("ggml-").removeSuffix(".bin")
    public val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

/** A model installed in Ollama, and whether it reads images (the notes of the student). */
public class InstalledModel(public val name: String, public val sizeBytes: Long, public val readsImages: Boolean)

/**
 * The models on this computer, for the settings of the assistant: Ollama (whether it runs, its
 * models, their downloads) and the speech recognition models of whisper.cpp (downloaded into
 * [modelsDirectory]). The downloads go on when the settings are closed.
 *
 * Every observable property is Compose state, updated on [stateContext].
 */
public class LocalModels internal constructor(
    private val settingsStore: AssistantSettingsStore,
    private val ollama: OllamaClient,
    private val httpClient: HttpClient = GeminiClient.defaultHttpClient(),
    internal val modelsDirectory: String = WhisperTranscriber.modelsDirectory(),
    private val stateContext: CoroutineContext = Dispatchers.Main
) {
    public enum class OllamaState { CHECKING, RUNNING, NOT_FOUND }

    public var ollamaState: OllamaState by mutableStateOf(OllamaState.CHECKING)
        private set

    public var ollamaVersion: String? by mutableStateOf<String?>(null)
        private set

    /** The models of Ollama, by name. */
    public val installed: SnapshotStateList<InstalledModel> = mutableStateListOf<InstalledModel>()

    /** Downloads in progress (Ollama models and whisper models), by name: progress 0..1, or -1 when unknown. */
    public val downloads: SnapshotStateMap<String, Float> = mutableStateMapOf()

    /** The last download that failed, and why. */
    public var downloadError: Pair<String, String>? by mutableStateOf<Pair<String, String>?>(null)
        private set

    /** The whisper models downloaded. */
    public var whisperModels: Set<WhisperModel> by mutableStateOf<Set<WhisperModel>>(emptySet())
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The hosts of the other devices of the local network (given by the application, which finds
     * them): where [findOnNetwork] looks for Ollama.
     */
    public var nearbyHosts: suspend () -> List<String> = { emptyList() }

    /** The addresses of Ollama found on the local network ([findOnNetwork]). */
    public val foundServers: SnapshotStateList<String> = mutableStateListOf<String>()

    public var isSearching: Boolean by mutableStateOf(false)
        private set

    /** Looks for Ollama on the devices of the local network (e.g. the computer of the student, from a tablet). */
    public fun findOnNetwork() {
        if (isSearching) return
        isSearching = true
        scope.launch {
            val found = try {
                coroutineScope {
                    nearbyHosts().distinct().map { host ->
                        async {
                            val url = "http://" + (if (':' in host) "[$host]" else host) + ":" + OLLAMA_PORT
                            url.takeIf { ollama.version(url) != null }
                        }
                    }.awaitAll().filterNotNull()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            withContext(stateContext) {
                foundServers.clear()
                foundServers.addAll(found)
                isSearching = false
            }
        }
    }

    /** Whether the transcription can run on this device (the desktop, for now). */
    public val canTranscribe: Boolean get() = WhisperTranscriber.isAvailable

    /** Checks Ollama (at [url]) and its models, and the whisper models downloaded. */
    public fun refresh(url: String = settingsStore.settings.localUrl) {
        scope.launch {
            val whisper = WhisperModel.entries.filter { SystemFileSystem.exists(Path(whisperPath(it))) }.toSet()
            withContext(stateContext) {
                whisperModels = whisper
                if (installed.isEmpty()) ollamaState = OllamaState.CHECKING
            }
            val version = ollama.version(url)
            val models = if (version == null) emptyList() else try {
                ollama.models(url).map { model ->
                    val capabilities = try {
                        ollama.capabilities(url, model.name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    InstalledModel(model.name, model.size, "vision" in capabilities)
                }.sortedBy { it.name }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            withContext(stateContext) {
                ollamaVersion = version
                ollamaState = if (version != null) OllamaState.RUNNING else OllamaState.NOT_FOUND
                installed.clear()
                installed.addAll(models)
            }
        }
    }

    public fun isInstalled(model: String): Boolean = installed.any { it.name == model || it.name == "$model:latest" }

    /** Downloads [model] into Ollama. */
    public fun pull(model: String) {
        if (model.isBlank() || model in downloads) return
        downloads[model] = -1f
        downloadError = null
        scope.launch {
            try {
                ollama.pull(settingsStore.settings.localUrl, model) { done, total ->
                    if (total > 0) scope.launch(stateContext) { if (model in downloads) downloads[model] = done.toFloat() / total }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(stateContext) { downloadError = model to (e.message ?: e::class.simpleName.orEmpty()) }
            }
            withContext(stateContext) { downloads.remove(model) }
            refresh()
        }
    }

    /** The file of [model] in [modelsDirectory]. */
    public fun whisperPath(model: WhisperModel): String = "$modelsDirectory/${model.fileName}"

    /** Downloads [model] into [modelsDirectory] (a `.part` file, renamed when complete). */
    public fun downloadWhisper(model: WhisperModel) {
        val name = model.fileName
        if (name in downloads) return
        downloads[name] = 0f
        downloadError = null
        scope.launch {
            try {
                download(model.url, whisperPath(model), model.sizeBytes) { progress ->
                    scope.launch(stateContext) { if (name in downloads) downloads[name] = progress }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(stateContext) { downloadError = name to (e.message ?: e::class.simpleName.orEmpty()) }
            }
            withContext(stateContext) { downloads.remove(name) }
            refresh()
        }
    }

    private suspend fun download(url: String, target: String, expectedBytes: Long, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        SystemFileSystem.createDirectories(Path(target).parent ?: Path(modelsDirectory))
        val partial = Path("$target.part")
        httpClient.prepareGet(url) {
            // Hundreds of megabytes: as long as it takes, as long as bytes arrive
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                socketTimeoutMillis = 60_000L
            }
        }.execute { response ->
            if (!response.status.isSuccess()) throw IllegalStateException("HTTP ${response.status.value}")
            val total = response.contentLength()?.takeIf { it > 0 } ?: expectedBytes
            val channel = response.bodyAsChannel()
            var written = 0L
            var reported = 0f
            SystemFileSystem.sink(partial).buffered().use { sink ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    written += read
                    val progress = written.toFloat() / total
                    if (progress - reported >= 0.005f) {
                        reported = progress
                        onProgress(progress)
                    }
                }
            }
            if (written != total) {
                SystemFileSystem.delete(partial, mustExist = false)
                throw IllegalStateException("incomplete download ($written of $total bytes)")
            }
        }
        SystemFileSystem.atomicMove(partial, Path(target))
    }

    private companion object {
        /** The port Ollama listens on. */
        const val OLLAMA_PORT = 11434

        const val BUFFER_SIZE = 256 * 1024
    }
}
