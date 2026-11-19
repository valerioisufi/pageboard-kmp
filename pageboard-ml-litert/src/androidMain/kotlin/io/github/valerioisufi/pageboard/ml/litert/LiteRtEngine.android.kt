package io.github.valerioisufi.pageboard.ml.litert

import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import io.github.valerioisufi.pageboard.ml.Accelerator
import io.github.valerioisufi.pageboard.ml.Embedder
import io.github.valerioisufi.pageboard.ml.EmbedderOptions
import io.github.valerioisufi.pageboard.ml.GenerationPart
import io.github.valerioisufi.pageboard.ml.GenerationRequest
import io.github.valerioisufi.pageboard.ml.Generator
import io.github.valerioisufi.pageboard.ml.GeneratorOptions
import io.github.valerioisufi.pageboard.ml.InstalledModel
import io.github.valerioisufi.pageboard.ml.ModelException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** LiteRT-LM of `litertlm-android`, loaded once: `null` on an ABI its AAR has no library for. */
private val androidEngine: LiteRtEngine? by lazy {
    try {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        AndroidLiteRtEngine
    } catch (_: Throwable) {
        null
    }
}

internal actual fun liteRtEngine(): LiteRtEngine? = androidEngine

private object AndroidLiteRtEngine : LiteRtEngine {
    @OptIn(ExperimentalApi::class)
    override suspend fun generator(model: InstalledModel, options: GeneratorOptions, cacheDirectory: String?): Generator =
        processFlags.withLock {
            withContext(Dispatchers.IO) {
                val imageTokens = imageTokens(model, options)
                ExperimentalFlags.visualTokenBudget = imageTokens.takeIf { it > 0 }
                val open = { backend: Backend ->
                    Engine(
                        EngineConfig(
                            modelPath = model.path,
                            backend = backend,
                            visionBackend = if (options.images) backend else null,
                            audioBackend = if (options.audio) backend else null,
                            maxNumTokens = options.maxTokens,
                            cacheDir = cache(cacheDirectory, model),
                            activationDataType = activations(backend)
                        )
                    ).also { it.initialize() }
                }
                val (engine, accelerator) = load(options.accelerator, open)
                val cpu = if (accelerator == Accelerator.GPU) ({ open(Backend.CPU()) }) else null
                AndroidLiteRtGenerator(model, engine, accelerator, imageTokens, cpu)
            }
        }

    override suspend fun embedder(model: InstalledModel, options: EmbedderOptions, cacheDirectory: String?): Embedder =
        withContext(Dispatchers.IO) {
            val (engine, accelerator) = load(options.accelerator) { backend ->
                EmbeddingEngine(EmbeddingEngineConfig(modelPath = model.path, backend = backend, cacheDir = cache(cacheDirectory, model)))
                    .also { it.initialize() }
            }
            val dimensions = options.dimensions.takeIf { it > 0 } ?: model.spec.dimensions
            AndroidLiteRtEmbedder(model, engine, accelerator, dimensions, outputSize = options.dimensions.takeIf { it > 0 })
        }

    private fun activations(backend: Backend): ActivationDataType? = if (backend is Backend.GPU) ActivationDataType.FLOAT32 else null

    private fun imageTokens(model: InstalledModel, options: GeneratorOptions): Int {
        val most = model.spec.maxImageTokens
        return if (most > 0 && options.imageTokens > most) most else options.imageTokens
    }

    /**
     * Where LiteRT-LM caches the weights of [model]: [directory], or beside the model without one;
     * [NO_CACHE] when that disk lacks the room. On the CPU the cache is about the size of the model,
     * and LiteRT-LM aborts the whole process when a write of it fails. Without a cache the model
     * loads more slowly, and nothing else changes.
     */
    private fun cache(directory: String?, model: InstalledModel): String? {
        val file = File(model.path)
        val disk = directory?.let { File(it).apply { mkdirs() } }?.takeIf { it.isDirectory } ?: file.absoluteFile.parentFile
        if (disk != null && disk.usableSpace < file.length() + CACHE_MARGIN) return NO_CACHE
        return directory?.takeIf { File(it).isDirectory }
    }

    private fun <T : AutoCloseable> load(accelerator: Accelerator, open: (Backend) -> T): Pair<T, Accelerator> {
        if (accelerator == Accelerator.GPU) {
            try {
                return open(Backend.GPU()) to Accelerator.GPU
            } catch (_: Exception) {
                // Fall back to CPU
            }
        }
        return try {
            open(Backend.CPU()) to Accelerator.CPU
        } catch (e: Exception) {
            throw ModelException("The model cannot be loaded: ${e.message}", e)
        }
    }
}

private val processFlags = Mutex()

/**
 * A model of LiteRT-LM answering on [accelerator]. On the GPU, LiteRT-LM loads a model without
 * OpenCL (the Pixels, the emulators) and fails only when the first conversation starts: the first
 * answer that fails before any piece of text then opens the model again on the CPU ([cpu]) and is
 * asked there, and so are the next ones.
 */
private class AndroidLiteRtGenerator(
    override val model: InstalledModel,
    private var engine: Engine,
    accelerator: Accelerator,
    private val imageTokens: Int,
    private var cpu: (() -> Engine)?
) : Generator {
    override var accelerator: Accelerator = accelerator
        private set

    override suspend fun generate(request: GenerationRequest, onProgress: (Int) -> Unit): String = processFlags.withLock {
        withContext(Dispatchers.IO) {
            var pieces = 0
            try {
                answer(request) { onProgress(++pieces) }
            } catch (e: ModelException) {
                val fallback = cpu?.takeIf { pieces == 0 } ?: throw e
                cpu = null
                engine.close()
                engine = try {
                    fallback()
                } catch (failure: Exception) {
                    throw ModelException("The model cannot be loaded: ${failure.message}", failure)
                }
                accelerator = Accelerator.CPU
                answer(request) { onProgress(++pieces) }
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    private suspend fun answer(request: GenerationRequest, onPiece: () -> Unit): String {
        ExperimentalFlags.enableConversationConstrainedDecoding = request.jsonSchema != null
        ExperimentalFlags.visualTokenBudget = imageTokens.takeIf { it > 0 }
        val config = ConversationConfig(
            systemInstruction = Contents.of(request.system),
            samplerConfig = SamplerConfig(topK = TOP_K, topP = TOP_P, temperature = request.temperature.toDouble(), seed = 0),
            maxOutputToken = request.maxOutputTokens,
            thinkingConfig = ThinkingConfig(enableThinking = false),
            enableResponseFormat = request.jsonSchema != null
        )
        val conversation = try {
            engine.createConversation(config)
        } catch (e: Exception) {
            throw ModelException("${model.spec.name} cannot answer: ${e.message}", e)
        }
        return conversation.use {
            val answer = StringBuilder()
            try {
                conversation.sendMessageAsync(
                    Contents.of(request.parts.map { it.toContent() }),
                    responseFormat = request.jsonSchema?.let { ResponseFormat.json(it) }
                ).collect { message ->
                    for (content in message.contents.contents) if (content is Content.Text) answer.append(content.text)
                    onPiece()
                }
            } catch (e: CancellationException) {
                conversation.cancelProcess()
                throw e
            } catch (e: Exception) {
                throw ModelException("${model.spec.name} cannot answer: ${e.message}", e)
            }
            answer.toString()
        }
    }

    override fun close() {
        engine.close()
    }

    private fun GenerationPart.toContent(): Content = when (this) {
        is GenerationPart.Text -> Content.Text(text)
        is GenerationPart.Image -> Content.ImageBytes(bytes)
        is GenerationPart.Audio -> Content.AudioBytes(wav)
    }

    private companion object {
        const val TOP_K = 40
        const val TOP_P = 0.95
    }
}

private class AndroidLiteRtEmbedder(
    override val model: InstalledModel,
    private val engine: EmbeddingEngine,
    override val accelerator: Accelerator,
    override val dimensions: Int,
    private val outputSize: Int?
) : Embedder {
    private val mutex = Mutex()

    override suspend fun embed(texts: List<String>): List<FloatArray> = mutex.withLock {
        if (texts.isEmpty()) return@withLock emptyList()
        withContext(Dispatchers.IO) {
            try {
                engine.computeEmbeddingBatch(texts.map { listOf(InputData.Text(it)) }, EmbeddingOptions(normalize = true, outputSize = outputSize))
                    .map { it.embedding }
            } catch (e: Exception) {
                throw ModelException("${model.spec.name} cannot compute the embeddings: ${e.message}", e)
            }
        }
    }

    override fun close() {
        engine.close()
    }
}
