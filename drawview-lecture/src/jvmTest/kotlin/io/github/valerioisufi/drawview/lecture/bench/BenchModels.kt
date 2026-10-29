package io.github.valerioisufi.drawview.lecture.bench

import io.github.valerioisufi.drawview.lecture.LectureAnalysis
import io.github.valerioisufi.drawview.lecture.model.ModelPrompt
import io.github.valerioisufi.drawview.lecture.model.PromptPart
import io.github.valerioisufi.drawview.ml.Accelerator
import io.github.valerioisufi.drawview.ml.Embedder
import io.github.valerioisufi.drawview.ml.EmbedderOptions
import io.github.valerioisufi.drawview.ml.GenerationPart
import io.github.valerioisufi.drawview.ml.GenerationRequest
import io.github.valerioisufi.drawview.ml.GeneratorOptions
import io.github.valerioisufi.drawview.ml.InstalledModel
import io.github.valerioisufi.drawview.ml.InstalledModels
import io.github.valerioisufi.drawview.ml.LoadedModels
import io.github.valerioisufi.drawview.ml.ModelCatalog
import io.github.valerioisufi.drawview.ml.ModelKind
import io.github.valerioisufi.drawview.ml.ModelSpec
import io.github.valerioisufi.drawview.ml.litert.LiteRtRuntime
import java.io.File
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** How the JSON schema of an answer reaches a model of the device. */
internal enum class SchemaMode {
    /** By the constrained decoding of LiteRT-LM. */
    ENFORCED,

    /** In the instructions only: the answer is taken out of its ```` ```json ```` block. */
    ASKED
}

/**
 * [this] prompt as a request of the models of the device, as `DeviceBackend` will send it: the
 * texts between two images joined, the schema enforced or written in the instructions.
 */
internal fun ModelPrompt.toRequest(mode: SchemaMode, temperature: Float = 0.4f): GenerationRequest {
    val schemaText = schema.toString()
    val instructions = when (mode) {
        SchemaMode.ENFORCED -> system
        SchemaMode.ASKED -> "$system\n\nAnswer only with a JSON object that follows this JSON Schema (write the object, not the schema), without other text:\n$schemaText"
    }
    val content = mutableListOf<GenerationPart>()
    val text = StringBuilder()
    fun flush() {
        if (text.isNotBlank()) content.add(GenerationPart.Text(text.toString().trim()))
        text.clear()
    }
    for (part in parts) {
        when (part) {
            is PromptPart.Text -> text.append(part.text).append("\n\n")
            is PromptPart.Image -> {
                flush()
                content.add(GenerationPart.Image(part.jpeg))
            }
        }
    }
    flush()
    return GenerationRequest(
        system = instructions,
        parts = content,
        jsonSchema = schemaText.takeIf { mode == SchemaMode.ENFORCED },
        maxOutputTokens = maxOutputTokens,
        temperature = temperature
    )
}

/** An answer of a model of the device, with its time; [error] when it could not answer. */
internal class Answer(val text: String, val error: String?, val millis: Long, val pieces: Int, val accelerator: Accelerator?)

/**
 * What the parts of the bench share: the directory of the bench, the models found in
 * `<bench>/models` (only those of `drawview.bench.models`, when given), LiteRT-LM and the models
 * in memory.
 */
internal class Bench(
    val directory: File,
    modelIds: List<String>,
    modes: List<String> = emptyList(),
    /** The temperature of every request (`drawview.bench.temperature`), the one of the assistant by default. */
    private val temperature: Float? = null,
    /** Where the models run (`drawview.bench.accelerator`): the GPU when it can be used, by default. */
    val accelerator: Accelerator = Accelerator.GPU
) {
    /** The ways of giving the schema measured (`drawview.bench.modes`, both by default). */
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

/**
 * The JSON of an answer: the last object of the text (in a ```` ```json ```` block or not) that
 * holds the keys [schema] requires, so that a schema the model repeated before its answer is
 * passed over. When the answer was written inside the schema repeated (`{"type": "object", ...},
 * "blocks": [...]}`), the text from its first required key, closed as an object. Without any, the
 * first object.
 */
internal fun jsonOf(answer: String, schema: JsonObject? = null): String {
    val required = schema?.get("required")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    val objects = jsonObjects(answer)
    val appended = required.firstOrNull()?.let { key -> answer.lastIndexOf("\"$key\":").takeIf { it >= 0 } }
        ?.let { start -> jsonObjects("{" + answer.substring(start)).firstOrNull() }
    val answered = (objects + listOfNotNull(appended)).lastOrNull { holdsKeys(it, required) }
    return (answered ?: objects.firstOrNull() ?: answer).trim()
}

/** Whether [json] is an object holding [keys] (any object, when there are none, but a schema). */
internal fun holdsKeys(json: String, keys: List<String>): Boolean {
    val element = parseJsonOrNull(LectureAnalysis.repairLatexEscapes(json)) as? JsonObject ?: return false
    return element.keys.containsAll(keys) && (keys.isNotEmpty() || "properties" !in element)
}

/** The keys [schema] requires of its object. */
internal fun requiredKeys(schema: JsonObject): List<String> = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

/** The objects of [text] at the top level, by their braces (those inside the strings of JSON skipped). */
private fun jsonObjects(text: String): List<String> {
    val objects = mutableListOf<String>()
    var depth = 0
    var start = -1
    var inString = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            inString && c == '\\' -> i++
            c == '"' && depth > 0 -> inString = !inString
            inString -> {}
            c == '{' -> {
                if (depth == 0) start = i
                depth++
            }
            c == '}' && depth > 0 -> {
                depth--
                if (depth == 0) objects.add(text.substring(start, i + 1))
            }
        }
        i++
    }
    return objects
}

/** Seconds, with one decimal. */
internal fun seconds(millis: Long): String = "%.1f s".format(millis / 1000.0)

/** [part] of [whole] as a percentage. */
internal fun percent(part: Int, whole: Int): String = if (whole == 0) "-" else "${part * 100 / whole} %"

internal fun <T> List<T>.meanOf(value: (T) -> Double): Double = if (isEmpty()) 0.0 else sumOf(value) / size
