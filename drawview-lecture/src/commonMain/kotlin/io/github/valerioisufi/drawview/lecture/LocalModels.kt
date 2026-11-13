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
import io.github.valerioisufi.drawview.lecture.transcription.EncoderArchive
import io.github.valerioisufi.drawview.lecture.transcription.WhisperEngine
import io.github.valerioisufi.drawview.lecture.transcription.WhisperTranscriber
import io.github.valerioisufi.drawview.lecture.transcription.encoderArchive
import io.github.valerioisufi.drawview.lecture.transcription.whisperEngine
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * A speech recognition model of whisper.cpp offered by the settings (from huggingface.co/ggerganov/whisper.cpp),
 * and its encoder compiled for Core ML, which whisper.cpp runs on the Neural Engine of a Mac when it lies next to
 * the model (docs/transcription-queue.md, "The encoder on the Neural Engine").
 *
 * @property encoderSizeBytes the size of the zip of the encoder.
 */
public enum class WhisperModel(
    public val fileName: String,
    public val sizeBytes: Long,
    public val encoderSizeBytes: Long,
    internal val encoderSha256: String
) {
    /** Accurate and light: the default. */
    LARGE_V3_TURBO_Q5("ggml-large-v3-turbo-q5_0.bin", 574_041_195, 1_173_393_014, LARGE_V3_TURBO_ENCODER_SHA256),
    LARGE_V3_TURBO("ggml-large-v3-turbo.bin", 1_624_555_275, 1_173_393_014, LARGE_V3_TURBO_ENCODER_SHA256),
    SMALL("ggml-small.bin", 487_601_967, 163_083_239, "de43fb9fed471e95c19e60ae67575c2bf09e8fb607016da171b06ddad313988b"),
    BASE("ggml-base.bin", 147_951_465, 37_922_638, "7e6ab77041942572f239b5b602f8aaa1c3ed29d73e3d8f20abea03a773541089");

    public val label: String get() = fileName.removePrefix("ggml-").removeSuffix(".bin")
    public val url: String get() = "$MODELS_URL/$fileName"

    /**
     * The directory of the encoder next to the model, named as whisper.cpp looks for it: the name of the
     * model without its quantization (`-q5_0`), so the two turbo models share one.
     */
    public val encoderFileName: String get() = fileName.removeSuffix(".bin").replace(QUANTIZATION, "") + "-encoder.mlmodelc"
    internal val encoderUrl: String get() = "$MODELS_URL/$encoderFileName.zip"
}

private const val MODELS_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
private const val LARGE_V3_TURBO_ENCODER_SHA256 = "84bedfe895bd7b5de6e8e89a0803dfc5addf8c0c5bc4c937451716bf7cf7988a"
private val QUANTIZATION = Regex("-q\\d_\\d$")

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
    private val stateContext: CoroutineContext = Dispatchers.Main,
    private val encoderArchive: EncoderArchive? = encoderArchive(),
    private val whisperEngine: WhisperEngine? = whisperEngine(),
    private val encoderSha256: (WhisperModel) -> String = { it.encoderSha256 }
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

    /**
     * Whether whisper.cpp runs the encoder of its models on the Neural Engine here, when the encoder is
     * downloaded ([downloadEncoder]): on a Mac with Apple Silicon. There the window stays smooth while a
     * lecture is transcribed, and the transcription is a little faster.
     */
    public val usesNeuralEngine: Boolean get() = encoderArchive != null

    /** The models whose encoder for the Neural Engine is downloaded. */
    public var encoders: Set<WhisperModel> by mutableStateOf<Set<WhisperModel>>(emptySet())
        private set

    /**
     * The encoders being prepared, by [WhisperModel.encoderFileName]: once downloaded, Core ML compiles one
     * for the Neural Engine (half a minute) at the first load of its model, done right after the download.
     */
    public val preparing: SnapshotStateList<String> = mutableStateListOf<String>()

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
            val encoded = WhisperModel.entries.filter { SystemFileSystem.metadataOrNull(Path(encoderPath(it)))?.isDirectory == true }.toSet()
            withContext(stateContext) {
                whisperModels = whisper
                encoders = encoded
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

    /**
     * Downloads [model] into [modelsDirectory] (a `.part` file, renamed when complete), and then its encoder
     * where it runs on the Neural Engine ([usesNeuralEngine]) and is not downloaded yet.
     */
    public fun downloadWhisper(model: WhisperModel) {
        val name = model.fileName
        if (name in downloads) return
        downloads[name] = 0f
        downloadError = null
        scope.launch {
            val downloaded = try {
                download(model.url, whisperPath(model), model.sizeBytes) { progress ->
                    scope.launch(stateContext) { if (name in downloads) downloads[name] = progress }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(stateContext) { downloadError = name to (e.message ?: e::class.simpleName.orEmpty()) }
                false
            }
            withContext(stateContext) {
                downloads.remove(name)
                if (downloaded && usesNeuralEngine && !isEncoderDownloaded(model)) downloadEncoder(model)
            }
            refresh()
        }
    }

    /** The directory of the encoder of [model] in [modelsDirectory], where whisper.cpp looks for it. */
    public fun encoderPath(model: WhisperModel): String = "$modelsDirectory/${model.encoderFileName}"

    private fun isEncoderDownloaded(model: WhisperModel) = SystemFileSystem.metadataOrNull(Path(encoderPath(model)))?.isDirectory == true

    /**
     * Downloads the encoder of [model] for the Neural Engine, where there is one ([usesNeuralEngine]): its zip,
     * checked against the SHA-256 known for it, extracted next to the model and deleted; then, if the model is
     * downloaded, the model loaded once, so that Core ML compiles the encoder now and not at the first lecture
     * ([preparing]). In [downloads] and [downloadError] by [WhisperModel.encoderFileName].
     */
    public fun downloadEncoder(model: WhisperModel) {
        val archive = encoderArchive ?: return
        val name = model.encoderFileName
        if (name in downloads || name in preparing) return
        downloads[name] = 0f
        downloadError = null
        scope.launch {
            val zip = "$modelsDirectory/$name.zip"
            val downloaded = try {
                download(model.encoderUrl, zip, model.encoderSizeBytes) { progress ->
                    scope.launch(stateContext) { if (name in downloads) downloads[name] = progress }
                }
                withContext(Dispatchers.IO) {
                    try {
                        // A native library loads what is extracted: only the zip published for this model is opened
                        if (archive.sha256(zip) != encoderSha256(model)) throw IllegalStateException("the encoder downloaded is not the one expected")
                        archive.extract(zip, modelsDirectory, name, maxBytes = model.encoderSizeBytes * MAX_ENCODER_EXPANSION)
                    } finally {
                        SystemFileSystem.delete(Path(zip), mustExist = false)
                    }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(stateContext) { downloadError = name to (e.message ?: e::class.simpleName.orEmpty()) }
                false
            }
            withContext(stateContext) {
                downloads.remove(name)
                if (downloaded) preparing += name
            }
            refresh()
            if (downloaded) {
                try {
                    prepare(model)
                } finally {
                    withContext(NonCancellable + stateContext) { preparing -= name }
                }
            }
        }
    }

    /** Loads [model] once and frees it: Core ML compiles its encoder for the Neural Engine, and keeps it. */
    private suspend fun prepare(model: WhisperModel) = withContext(Dispatchers.IO) {
        val engine = whisperEngine ?: return@withContext
        val path = whisperPath(model)
        if (!SystemFileSystem.exists(Path(path))) return@withContext
        val handle = engine.load(path, useGpu = true)
        if (handle != 0L) engine.free(handle)
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

        /** How many times its zip an encoder may take once extracted (1.09 for large-v3-turbo). */
        const val MAX_ENCODER_EXPANSION = 2
    }
}
