package io.github.valerioisufi.pageboard.lecture.bench

import io.github.valerioisufi.pageboard.lecture.model.DeviceBackend
import io.github.valerioisufi.pageboard.lecture.model.ModelPrompt
import io.github.valerioisufi.pageboard.ml.Accelerator
import io.github.valerioisufi.pageboard.ml.Embedder
import io.github.valerioisufi.pageboard.ml.EmbedderOptions
import io.github.valerioisufi.pageboard.ml.GenerationRequest
import io.github.valerioisufi.pageboard.ml.GeneratorOptions
import io.github.valerioisufi.pageboard.ml.InstalledModel
import io.github.valerioisufi.pageboard.ml.InstalledModels
import io.github.valerioisufi.pageboard.ml.LoadedModels
import io.github.valerioisufi.pageboard.ml.ModelCatalog
import io.github.valerioisufi.pageboard.ml.ModelKind
import io.github.valerioisufi.pageboard.ml.ModelSpec
import io.github.valerioisufi.pageboard.ml.litert.LiteRtRuntime
import java.io.File
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** How the JSON schema of an answer reaches a model of the device (see [DeviceBackend]). */
internal typealias SchemaMode = io.github.valerioisufi.pageboard.lecture.model.SchemaMode

/** [this] prompt as a request of the models of the device, as [DeviceBackend] sends it. */
internal fun ModelPrompt.toRequest(mode: SchemaMode, temperature: Float = 0.4f): GenerationRequest =
    DeviceBackend.requestOf(this, mode, temperature)

/** An answer of a model of the device, with its time; [error] when it could not answer. */
internal class Answer(val text: String, val error: String?, val millis: Long, val pieces: Int, val accelerator: Accelerator?)

/**
 * What the parts of the bench share: the directory of the bench, the models found in
 * `<bench>/models` (only those of `pageboard.bench.models`, when given), LiteRT-LM and the models
 * in memory.
 */
internal class Bench(
    val directory: File,
    modelIds: List<String>,
    modes: List<String> = emptyList(),
    /** The temperature of every request (`pageboard.bench.temperature`), the one of the assistant by default. */
    private val temperature: Float? = null,
    /** Where the models run (`pageboard.bench.accelerator`): the GPU when it can be used, by default. */
    val accelerator: Accelerator = Accelerator.GPU
) {
    /** The ways of giving the schema measured (`pageboard.bench.modes`, both by default). */
    val modes: List<SchemaMode> = SchemaMode.entries.filter { modes.isEmpty() || it.name.lowercase() in modes }

    val installed = InstalledModels(ModelCatalog.Default, File(directory, "models").path)
    val runtime = LiteRtRuntime(cacheDirectory = File(directory, "cache").path)
    val loaded = LoadedModels(runtime)

    private val chosen: List<ModelSpec> = ModelCatalog.Default.models.filter { modelIds.isEmpty() || it.id in modelIds }

    /** The generation models of the bench found on this computer, the smallest first. */
    val generators: List<InstalledModel> get() = chosen.filter { it.kind == ModelKind.GENERATION }.mapNotNull { installed[it] }

    /** The embedding model of the bench, if found. */
    val embedder: InstalledModel? get() = chosen.firstOrNull { it.kind == ModelKind.EMBEDDING }?.let { installed[it] }

    val material: File get() = File(directory, "material")

    /** A file of the bench for the answers of a part, to read them again. */
    fun output(name: String): File = File(directory, "answers/$name").also { it.parentFile.mkdirs() }

    /** [prompt] answered by [model] loaded with [options], its schema reaching it by [mode]. */
    suspend fun ask(model: InstalledModel, options: GeneratorOptions, prompt: ModelPrompt, mode: SchemaMode): Answer =
        ask(model, options, if (temperature != null) prompt.toRequest(mode, temperature) else prompt.toRequest(mode))

    suspend fun ask(model: InstalledModel, options: GeneratorOptions, request: GenerationRequest): Answer {
        val options = GeneratorOptions(options.maxTokens, options.images, options.imageTokens, options.audio, accelerator)
        val start = TimeSource.Monotonic.markNow()
        var pieces = 0
        var accelerator: Accelerator? = null
        return try {
            val text = loaded.withGenerator(model, options, keepLoadedMillis = 120_000) { generator ->
                accelerator = generator.accelerator
                generator.generate(request) { pieces = it }
            }
            Answer(text, null, start.elapsedNow().inWholeMilliseconds, pieces, accelerator)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Answer("", e.message ?: e::class.simpleName.orEmpty(), start.elapsedNow().inWholeMilliseconds, pieces, accelerator)
        }
    }

    suspend fun <T> withEmbedder(model: InstalledModel, options: EmbedderOptions, block: suspend (Embedder) -> T): T =
        loaded.withEmbedder(model, options, keepLoadedMillis = 120_000, block = block)
}

/** The JSON of an answer, as [DeviceBackend] takes it. */
internal fun jsonOf(answer: String, schema: JsonObject? = null): String = DeviceBackend.jsonOf(answer, schema)

internal fun holdsKeys(json: String, keys: List<String>): Boolean = DeviceBackend.holdsKeys(json, keys)

internal fun requiredKeys(schema: JsonObject): List<String> = DeviceBackend.requiredKeys(schema)

/** Seconds, with one decimal. */
internal fun seconds(millis: Long): String = "%.1f s".format(millis / 1000.0)

/** [part] of [whole] as a percentage. */
internal fun percent(part: Int, whole: Int): String = if (whole == 0) "-" else "${part * 100 / whole} %"

internal fun <T> List<T>.meanOf(value: (T) -> Double): Double = if (isEmpty()) 0.0 else sumOf(value) / size
