package io.github.valerioisufi.drawview.lecture.model

import io.github.valerioisufi.drawview.lecture.LectureAnalysis
import io.github.valerioisufi.drawview.lecture.LectureProblem
import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.ml.GenerationPart
import io.github.valerioisufi.drawview.ml.GenerationRequest
import io.github.valerioisufi.drawview.ml.GeneratorOptions
import io.github.valerioisufi.drawview.ml.InstalledModels
import io.github.valerioisufi.drawview.ml.LoadedModels
import io.github.valerioisufi.drawview.ml.ModelException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** How the JSON schema of an answer reaches a model of the device. */
internal enum class SchemaMode {
    /** By the constrained decoding of LiteRT-LM. */
    ENFORCED,

    /** In the instructions only: the answer is taken out of the text ([DeviceBackend.jsonOf]). */
    ASKED
}

/**
 * The models of this device (`drawview-ml`) as a [ModelBackend]: free, private, and they never leave
 * the device. A model is named by its id in the catalog of [installed] (`gemma-4-e4b`), loaded by
 * [loaded] at its first request and kept while the lecture goes on ([ModelUse.LIVE]).
 *
 * The schema of the answer is asked in the instructions by default ([SchemaMode.ASKED]): measured
 * on the Mac, the constrained decoding of LiteRT-LM spoils the answers of Gemma 4 E2B and E4B on the
 * handwriting (`docs/plans/local-models.md`, phase 2).
 *
 * The models of the device are an engine of [ModelProvider.LOCAL], as Ollama is: the lecture
 * records name the provider only.
 */
internal class DeviceBackend(
    private val installed: InstalledModels,
    private val loaded: LoadedModels,
    private val schemaMode: SchemaMode = SchemaMode.ASKED
) : ModelBackend {
    override val provider = ModelProvider.LOCAL

    /** As the models of Ollama: few images and blocks a request. */
    override val limits = OllamaBackend.LIMITS

    /** Whether the model is here is known at the request only. */
    override fun unavailable(): LectureProblem? = null

    override fun newPromptCache(): PromptCache? = null

    /**
     * @throws ModelException when the model [model] is not on this device, cannot be loaded or
     *   cannot answer.
     */
    override suspend fun generate(prompt: ModelPrompt, model: String, use: ModelUse, cache: PromptCache?, onProgress: (Int) -> Unit): ModelAnswer {
        val installedModel = installed[model] ?: throw ModelException("The model $model is not on this device")
        val images = prompt.parts.count { it is PromptPart.Image }
        val imageTokens = if (images > 0) installedModel.spec.maxImageTokens.coerceAtMost(MAX_IMAGE_TOKENS) else 0
        val options = GeneratorOptions(
            maxTokens = contextSize(prompt, images, imageTokens),
            images = images > 0,
            imageTokens = imageTokens
        )
        val keepLoaded = if (use == ModelUse.LIVE) LIVE_KEEP_LOADED_MILLIS else 0L
        val text = loaded.withGenerator(installedModel, options, keepLoaded) { generator -> generator.generate(requestOf(prompt, schemaMode), onProgress) }
        return ModelAnswer(prompt.schema?.let { jsonOf(text, it) } ?: text, usage = null)
    }

    /** A model missing or failing fails again: not repeated. */
    override fun isRetryable(error: Throwable): Boolean? = if (error is ModelException) false else null

    override suspend fun release(models: Set<String>) {
        models.forEach { loaded.unload(it) }
    }

    companion object {
        /** A model in memory between two requests of a lecture (as Ollama's `keep_alive`). */
        const val LIVE_KEEP_LOADED_MILLIS = OllamaBackend.LIVE_KEEP_LOADED_SECONDS * 1000L

        /** The most tokens of an image: more than 560 read worse (`docs/plans/local-models.md`, phase 2). */
        const val MAX_IMAGE_TOKENS = 560

        /** The problem shown for [error], `null` when it is not an error of a model of the device. */
        fun problemOf(error: Throwable): LectureProblem? =
            (error as? ModelException)?.let { LectureProblem.LocalModelUnavailable(it.message ?: it::class.simpleName.orEmpty()) }

        /** About 3 characters per token, the images at their budget, plus the answer; 8k to 32k. */
        fun contextSize(prompt: ModelPrompt, images: Int, imageTokens: Int): Int {
            val chars = prompt.system.length + prompt.parts.sumOf { (it as? PromptPart.Text)?.text?.length ?: 0 } +
                (if (prompt.schema != null) prompt.schema.toString().length else 0)
            val estimate = chars / 3 + images * imageTokens + prompt.maxOutputTokens
            return ((estimate + 4095) / 4096 * 4096).coerceIn(8192, 32768)
        }

        /**
         * [prompt] as a request of the models of the device: the texts between two images joined,
         * the schema enforced or written in the instructions ([mode]).
         */
        fun requestOf(prompt: ModelPrompt, mode: SchemaMode, temperature: Float = 0.4f): GenerationRequest {
            val schemaText = prompt.schema?.toString()
            val instructions = if (mode == SchemaMode.ASKED && schemaText != null) {
                "${prompt.system}\n\nAnswer only with a JSON object that follows this JSON Schema (write the object, not the schema), without other text:\n$schemaText"
            } else {
                prompt.system
            }
            val content = mutableListOf<GenerationPart>()
            val text = StringBuilder()
            fun flush() {
                if (text.isNotBlank()) content.add(GenerationPart.Text(text.toString().trim()))
                text.clear()
            }
            for (part in prompt.parts) {
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
                maxOutputTokens = prompt.maxOutputTokens,
                temperature = temperature
            )
        }

        /**
         * The JSON of an answer: the last object of the text (in a ```` ```json ```` block or not)
         * that holds the keys [schema] requires, so that a schema the model repeated before its
         * answer is passed over. When the answer was written inside the schema repeated
         * (`{"type": "object", ...}, "blocks": [...]}`), the text from its first required key,
         * closed as an object. Without any, the first object.
         */
        fun jsonOf(answer: String, schema: JsonObject? = null): String {
            val required = schema?.let(::requiredKeys).orEmpty()
            val objects = jsonObjects(answer)
            val appended = required.firstOrNull()?.let { key -> answer.lastIndexOf("\"$key\":").takeIf { it >= 0 } }
                ?.let { start -> jsonObjects("{" + answer.substring(start)).firstOrNull() }
            val answered = (objects + listOfNotNull(appended)).lastOrNull { holdsKeys(it, required) }
            return (answered ?: objects.firstOrNull() ?: answer).trim()
        }

        /** Whether [json] is an object holding [keys] (any object, when there are none, but a schema). */
        fun holdsKeys(json: String, keys: List<String>): Boolean {
            val element = try {
                Json.parseToJsonElement(LectureAnalysis.repairLatexEscapes(json))
            } catch (_: Exception) {
                null
            } as? JsonObject ?: return false
            return element.keys.containsAll(keys) && (keys.isNotEmpty() || "properties" !in element)
        }

        /** The keys [schema] requires of its object. */
        fun requiredKeys(schema: JsonObject): List<String> = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

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
    }
}
