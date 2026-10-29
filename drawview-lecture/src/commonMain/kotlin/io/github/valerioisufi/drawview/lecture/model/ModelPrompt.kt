package io.github.valerioisufi.drawview.lecture.model

import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.TokenUsage
import kotlinx.serialization.json.JsonObject

/** A piece of the content of a request. */
internal sealed interface PromptPart {
    class Text(val text: String) : PromptPart

    class Image(val jpeg: ByteArray) : PromptPart
}

/**
 * A request of the assistant, independent of the provider that answers it: instructions,
 * content (text and images, never audio) and the JSON schema of the answer.
 *
 * @param thinkingLevel reasoning of the Gemini models (low, medium, high).
 * @param cachedParts leading [parts] that, with [system], stay the same in every request of a
 *   lecture: a provider may cache them; the others ignore it.
 */
internal class ModelPrompt(
    val system: String,
    val parts: List<PromptPart>,
    val schema: JsonObject,
    val maxOutputTokens: Int,
    val thinkingLevel: String,
    val cachedParts: Int = 0
)

/**
 * The answer to a [ModelPrompt].
 *
 * @param usage tokens billed (Gemini); `null` for a local model, which is free.
 */
internal class ModelAnswer(public val text: String, public val usage: TokenUsage?)

/** A provider of models answering [ModelPrompt]s. */
internal interface ModelBackend {
    val provider: ModelProvider

    /**
     * Answers [prompt] with [model].
     *
     * @param onProgress tokens written so far (local models).
     */
    suspend fun generate(prompt: ModelPrompt, model: String, onProgress: (Int) -> Unit = {}): ModelAnswer
}

/** The providers of the assistant. */
internal class ModelBackends(internal val gemini: GeminiBackend, public val local: OllamaBackend) {
    fun of(provider: ModelProvider): ModelBackend = when (provider) {
        ModelProvider.GEMINI -> gemini
        ModelProvider.LOCAL -> local
    }
}
