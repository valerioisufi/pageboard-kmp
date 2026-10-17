package io.github.valerioisufi.drawview.ml.litert

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** LiteRT-LM of `litertlm-jvm`, loaded once: `null` on a platform its jar has no library for. */
@OptIn(ExperimentalApi::class)
private val jvmEngine: LiteRtEngine? by lazy {
    try {
        // The first call into the engine loads its native library
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        // The answers in the JSON of their schema (ResponseFormat of a conversation)
        ExperimentalFlags.enableConversationConstrainedDecoding = true
        JvmLiteRtEngine
    } catch (_: Throwable) {
        // UnsatisfiedLinkError, or the library missing from the jar (macOS on Intel, Windows on Arm)
        null
    }
}

internal actual fun liteRtEngine(): LiteRtEngine? = jvmEngine

private object JvmLiteRtEngine : LiteRtEngine {
    override suspend fun generator(model: InstalledModel, options: GeneratorOptions, cacheDirectory: String?): Generator =
        withContext(Dispatchers.IO) {
            val (engine, accelerator) = load(options.accelerator) { backend ->
                Engine(
                    EngineConfig(
                        modelPath = model.path,
                        backend = backend,
                        visionBackend = if (options.images) backend else null,
                        audioBackend = if (options.audio) backend else null,
                        maxNumTokens = options.maxTokens,
                        cacheDir = cacheDirectory
                    )
                ).also { it.initialize() }
            }
            LiteRtGenerator(model, engine, accelerator)
        }

    override suspend fun embedder(model: InstalledModel, options: EmbedderOptions, cacheDirectory: String?): Embedder =
        withContext(Dispatchers.IO) {
            val (engine, accelerator) = load(options.accelerator) { backend ->
                EmbeddingEngine(EmbeddingEngineConfig(modelPath = model.path, backend = backend, cacheDir = cacheDirectory))
                    .also { it.initialize() }
            }
            val dimensions = options.dimensions.takeIf { it > 0 } ?: model.spec.dimensions
            LiteRtEmbedder(model, engine, accelerator, dimensions, outputSize = options.dimensions.takeIf { it > 0 })
        }

    /** Loads on the GPU when asked and when it can be used, on the processor otherwise. */
    private fun <T : AutoCloseable> load(accelerator: Accelerator, open: (Backend) -> T): Pair<T, Accelerator> {
        if (accelerator == Accelerator.GPU) {
            try {
                return open(Backend.GPU()) to Accelerator.GPU
            } catch (_: Exception) {
                // No GPU LiteRT-LM can use: the processor
            }
        }
        return try {
            open(Backend.CPU()) to Accelerator.CPU
        } catch (e: Exception) {
            throw ModelException("The model cannot be loaded: ${e.message}", e)
        }
    }
}

/** A Gemma model of LiteRT-LM: one conversation per request, closed with it. */
private class LiteRtGenerator(
    override val model: InstalledModel,
    private val engine: Engine,
    override val accelerator: Accelerator
) : Generator {
    private val mutex = Mutex()

    override suspend fun generate(request: GenerationRequest, onProgress: (Int) -> Unit): String = mutex.withLock {
        withContext(Dispatchers.IO) {
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

/** EmbeddingGemma of LiteRT-LM. */
private class LiteRtEmbedder(
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
