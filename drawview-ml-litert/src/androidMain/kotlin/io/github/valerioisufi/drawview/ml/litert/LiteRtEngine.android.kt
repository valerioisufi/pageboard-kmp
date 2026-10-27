package io.github.valerioisufi.drawview.ml.litert

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
import io.github.valerioisufi.drawview.ml.Accelerator
import io.github.valerioisufi.drawview.ml.Embedder
import io.github.valerioisufi.drawview.ml.EmbedderOptions
import io.github.valerioisufi.drawview.ml.GenerationPart
import io.github.valerioisufi.drawview.ml.GenerationRequest
import io.github.valerioisufi.drawview.ml.Generator
import io.github.valerioisufi.drawview.ml.GeneratorOptions
import io.github.valerioisufi.drawview.ml.InstalledModel
import io.github.valerioisufi.drawview.ml.ModelException
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
                val (engine, accelerator) = load(options.accelerator) { backend ->
                    Engine(
                        EngineConfig(
                            modelPath = model.path,
                            backend = backend,
                            visionBackend = if (options.images) backend else null,
                            audioBackend = if (options.audio) backend else null,
                            maxNumTokens = options.maxTokens,
                            cacheDir = cache(cacheDirectory),
                            activationDataType = activations(backend)
                        )
                    ).also { it.initialize() }
                }
                AndroidLiteRtGenerator(model, engine, accelerator, imageTokens)
            }
        }

    override suspend fun embedder(model: InstalledModel, options: EmbedderOptions, cacheDirectory: String?): Embedder =
        withContext(Dispatchers.IO) {
            val (engine, accelerator) = load(options.accelerator) { backend ->
                EmbeddingEngine(EmbeddingEngineConfig(modelPath = model.path, backend = backend, cacheDir = cache(cacheDirectory)))
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

    private fun cache(directory: String?): String? = directory?.takeIf { File(it).apply { mkdirs() }.isDirectory }

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

private class AndroidLiteRtGenerator(
    override val model: InstalledModel,
    private val engine: Engine,
    override val accelerator: Accelerator,
    private val imageTokens: Int
) : Generator {
    @OptIn(ExperimentalApi::class)
    override suspend fun generate(request: GenerationRequest, onProgress: (Int) -> Unit): String = processFlags.withLock {
        withContext(Dispatchers.IO) {
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
            conversation.use {
                val answer = StringBuilder()
                var pieces = 0
                try {
                    conversation.sendMessageAsync(
                        Contents.of(request.parts.map { it.toContent() }),
                        responseFormat = request.jsonSchema?.let { ResponseFormat.json(it) }
                    ).collect { message ->
                        for (content in message.contents.contents) if (content is Content.Text) answer.append(content.text)
                        onProgress(++pieces)
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
