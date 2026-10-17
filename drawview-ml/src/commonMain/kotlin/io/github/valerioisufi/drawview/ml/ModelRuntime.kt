package io.github.valerioisufi.drawview.ml

/** Where a model runs. */
public enum class Accelerator {
    /** The graphics processor; the runtime falls back to the processor when it cannot use it. */
    GPU,

    /** The processor: slower, but it runs everywhere. */
    CPU
}

/**
 * How a generation model is loaded.
 *
 * @param maxTokens the context: the request and the answer together.
 * @param images whether it reads images (its vision encoder is loaded).
 * @param audio whether it hears audio (its audio encoder is loaded).
 * @param accelerator where it runs.
 */
public class GeneratorOptions(
    public val maxTokens: Int = 8192,
    public val images: Boolean = false,
    public val audio: Boolean = false,
    public val accelerator: Accelerator = Accelerator.GPU
) {
    override fun equals(other: Any?): Boolean = other is GeneratorOptions && other.maxTokens == maxTokens &&
        other.images == images && other.audio == audio && other.accelerator == accelerator

    override fun hashCode(): Int = ((maxTokens * 31 + images.hashCode()) * 31 + audio.hashCode()) * 31 + accelerator.hashCode()
}

/**
 * How an embedding model is loaded.
 *
 * @param dimensions the size of the vectors, smaller than the one of the model for a model that
 *   allows it (Matryoshka); 0 for the size of the model.
 * @param accelerator where it runs.
 */
public class EmbedderOptions(
    public val dimensions: Int = 0,
    public val accelerator: Accelerator = Accelerator.GPU
) {
    override fun equals(other: Any?): Boolean = other is EmbedderOptions && other.dimensions == dimensions && other.accelerator == accelerator

    override fun hashCode(): Int = dimensions * 31 + accelerator.hashCode()
}

/** A piece of a [GenerationRequest]. */
public sealed interface GenerationPart {
    public class Text(public val text: String) : GenerationPart

    /** An image, JPEG or PNG. */
    public class Image(public val bytes: ByteArray) : GenerationPart

    /** Audio, a WAV file (16-bit mono PCM). */
    public class Audio(public val wav: ByteArray) : GenerationPart
}

/**
 * A request to a generation model.
 *
 * @param system the instructions.
 * @param parts the content, in its order.
 * @param jsonSchema when given, the answer is a JSON value of this schema (a JSON Schema, as text).
 * @param maxOutputTokens the longest answer.
 * @param temperature how freely the words are chosen (0: always the most likely).
 */
public class GenerationRequest(
    public val system: String,
    public val parts: List<GenerationPart>,
    public val jsonSchema: String? = null,
    public val maxOutputTokens: Int = 2048,
    public val temperature: Float = 0.4f
)

/** A generation model loaded in memory, given and closed by [LoadedModels]. It answers one request at a time. */
public interface Generator : AutoCloseable {
    public val model: InstalledModel

    /** Where it runs (on [Accelerator.CPU] when the GPU asked could not be used). */
    public val accelerator: Accelerator

    /**
     * The answer to [request]; [onProgress] receives the tokens written so far. Cancelling the
     * coroutine stops the generation.
     *
     * @throws ModelException when the model cannot answer.
     */
    public suspend fun generate(request: GenerationRequest, onProgress: (Int) -> Unit = {}): String
}

/** An embedding model loaded in memory, given and closed by [LoadedModels]. It answers one request at a time. */
public interface Embedder : AutoCloseable {
    public val model: InstalledModel

    /** Where it runs (on [Accelerator.CPU] when the GPU asked could not be used). */
    public val accelerator: Accelerator

    /** The size of its vectors. */
    public val dimensions: Int

    /**
     * The vectors of [texts], in their order, normalized: the dot product of two is their cosine.
     *
     * @throws ModelException when the model cannot compute them.
     */
    public suspend fun embed(texts: List<String>): List<FloatArray>
}

/**
 * Runs the models on this device (Protected Variations): an instance given by the application
 * (LiteRT-LM, `drawview-ml-litert`), used through [LoadedModels].
 */
public interface ModelRuntime {
    /** Whether [spec] can run here: the runtime works on this platform and reads its file. */
    public fun supports(spec: ModelSpec): Boolean

    /**
     * Loads [model] to generate text.
     *
     * @throws ModelException when it cannot be loaded.
     */
    public suspend fun generator(model: InstalledModel, options: GeneratorOptions): Generator

    /**
     * Loads [model] to compute embeddings.
     *
     * @throws ModelException when it cannot be loaded.
     */
    public suspend fun embedder(model: InstalledModel, options: EmbedderOptions): Embedder
}

/** A model that cannot be loaded or cannot answer: no runtime on this platform, a file it cannot read, an error of the runtime. */
public class ModelException(message: String, cause: Throwable? = null) : Exception(message, cause)
