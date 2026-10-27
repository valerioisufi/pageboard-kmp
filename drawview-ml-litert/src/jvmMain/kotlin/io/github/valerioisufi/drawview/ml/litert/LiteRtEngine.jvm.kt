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

/** LiteRT-LM of `litertlm-jvm`, loaded once: `null` on a platform its jar has no library for. */
private val jvmEngine: LiteRtEngine? by lazy {
    try {
        // The first call into the engine loads its native library
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        JvmLiteRtEngine
    } catch (_: Throwable) {
        // UnsatisfiedLinkError, or the library missing from the jar (macOS on Intel, Windows on Arm)
        null
    }
}

internal actual fun liteRtEngine(): LiteRtEngine? = jvmEngine

private object JvmLiteRtEngine : LiteRtEngine {
    @OptIn(ExperimentalApi::class)
    override suspend fun generator(model: InstalledModel, options: GeneratorOptions, cacheDirectory: String?): Generator =
        processFlags.withLock {
            withContext(Dispatchers.IO) {
                // At most what the file allows: a larger budget fails the load
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
                LiteRtGenerator(model, engine, accelerator, imageTokens)
            }
        }

    override suspend fun embedder(model: InstalledModel, options: EmbedderOptions, cacheDirectory: String?): Embedder =
        withContext(Dispatchers.IO) {
            val (engine, accelerator) = load(options.accelerator) { backend ->
                EmbeddingEngine(EmbeddingEngineConfig(modelPath = model.path, backend = backend, cacheDir = cache(cacheDirectory)))
                    .also { it.initialize() }
            }
            val dimensions = options.dimensions.takeIf { it > 0 } ?: model.spec.dimensions
            LiteRtEmbedder(model, engine, accelerator, dimensions, outputSize = options.dimensions.takeIf { it > 0 })
        }

    /**
     * The activations of a generation model on [backend]: in 32 bits on the GPU, whose 16 bits
     * spoil the long answers of E2B and E4B (four answers in JSON in ten valid, ten in ten in 32
     * bits, phase 2 of docs/local-models.md); the default of the processor.
     */
    private fun activations(backend: Backend): ActivationDataType? = if (backend is Backend.GPU) ActivationDataType.FLOAT32 else null

    /** The budget of an image of [options], within the one of the file of [model] (when the catalog says it). */
    private fun imageTokens(model: InstalledModel, options: GeneratorOptions): Int {
        val most = model.spec.maxImageTokens
        return if (most > 0 && options.imageTokens > most) most else options.imageTokens
    }

    /** [directory], created if missing: LiteRT-LM refuses a cache directory that does not exist. */
    private fun cache(directory: String?): String? = directory?.takeIf { File(it).apply { mkdirs() }.isDirectory }

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

/**
 * Held while LiteRT-LM reads the flags of the whole process ([ExperimentalFlags]): by the load of
 * a model, and by a conversation of any model while it runs. The constrained decoding, left on,
 * cuts the answers of the conversations without a schema after a few tokens; the budget of the
 * images is the one of the model that runs.
 */
private val processFlags = Mutex()

/**
 * A Gemma model of LiteRT-LM: one conversation per request, closed with it.
 *
 * @param imageTokens the budget of each image (0: the default of the model).
 */
private class LiteRtGenerator(
    override val model: InstalledModel,
    private val engine: Engine,
    override val accelerator: Accelerator,
    private val imageTokens: Int
) : Generator {
    @OptIn(ExperimentalApi::class)
    override suspend fun generate(request: GenerationRequest, onProgress: (Int) -> Unit): String = processFlags.withLock {
        withContext(Dispatchers.IO) {
            // The answer in the JSON of its schema for this request only, the images in the budget of this model
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
