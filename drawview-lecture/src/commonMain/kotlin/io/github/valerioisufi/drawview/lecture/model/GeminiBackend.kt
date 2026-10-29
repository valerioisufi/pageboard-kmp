package io.github.valerioisufi.drawview.lecture.model

import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.TokenUsage
import io.github.valerioisufi.drawview.lecture.gemini.GeminiClient
import io.github.valerioisufi.drawview.lecture.gemini.GeminiContent
import io.github.valerioisufi.drawview.lecture.gemini.GeminiException
import io.github.valerioisufi.drawview.lecture.gemini.GeminiPart
import io.github.valerioisufi.drawview.lecture.gemini.GeminiUsage
import io.github.valerioisufi.drawview.lecture.gemini.GenerateContentRequest
import io.github.valerioisufi.drawview.lecture.gemini.GenerationConfig
import io.github.valerioisufi.drawview.lecture.gemini.ThinkingConfig
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64

/**
 * The Gemini API as a [ModelBackend].
 *
 * The images are sent at [MEDIA_RESOLUTION] (half the tokens of the default for Gemini 3); when the
 * API refuses the parameter (a version without it), it is dropped for the following requests.
 *
 * @param apiKey the key of the settings (read at every request).
 */
internal class GeminiBackend(public val client: GeminiClient, private val apiKey: () -> String) : ModelBackend {
    override val provider = ModelProvider.GEMINI

    @Volatile
    private var mediaResolution: String? = MEDIA_RESOLUTION

    /**
     * @throws GeminiException also for a missing key (status 401).
     */
    override suspend fun generate(prompt: ModelPrompt, model: String, onProgress: (Int) -> Unit): ModelAnswer = generate(prompt, model, cache = null)

    /**
     * Answers [prompt] with [model], its start read from [cache] when it can be; sent whole when the
     * cache is gone meanwhile. The tokens of a cache created for it count in its usage.
     *
     * @throws GeminiException also for a missing key (status 401).
     */
    suspend fun generate(prompt: ModelPrompt, model: String, cache: GeminiPromptCache?): ModelAnswer {
        val key = apiKey()
        if (key.isEmpty()) throw GeminiException(401, "API key not set")
        val use = cache?.use(key, prompt, model)
        val response = try {
            send(key, model, prompt, use?.name)
        } catch (e: GeminiException) {
            if (use == null || e.statusCode !in CACHE_GONE_STATUSES || !e.message.orEmpty().contains("cache", ignoreCase = true)) throw e
            cache.forget(use.name)
            send(key, model, prompt, null)
        }
        val usage = response.usageMetadata.toTokenUsage()
        return ModelAnswer(response.text(), if (use != null && use.createdTokens > 0) usage + TokenUsage(promptTokens = use.createdTokens) else usage)
    }

    private suspend fun send(key: String, model: String, prompt: ModelPrompt, cachedContent: String?) = try {
        client.generateContent(key, model, toRequest(prompt, mediaResolution, cachedContent))
    } catch (e: GeminiException) {
        if (mediaResolution == null || e.statusCode != 400 || !e.message.orEmpty().contains("resolution", ignoreCase = true)) throw e
        mediaResolution = null
        client.generateContent(key, model, toRequest(prompt, null, cachedContent))
    }

    companion object {
        /**
         * Tokens per image and PDF page: medium (560 for Gemini 3) instead of high (1120 by default
         * for the images); the images show areas of the page, readable at this resolution.
         */
        const val MEDIA_RESOLUTION = "MEDIA_RESOLUTION_MEDIUM"

        /** The statuses of a request whose cache expired or was deleted. */
        private val CACHE_GONE_STATUSES = setOf(400, 403, 404)

        /**
         * The request of the Gemini API for [prompt]; with [cachedContent], the cache that holds its
         * system instruction and its [ModelPrompt.cachedParts], which the request leaves out.
         */
        fun toRequest(prompt: ModelPrompt, mediaResolution: String? = MEDIA_RESOLUTION, cachedContent: String? = null) = GenerateContentRequest(
            contents = listOf(
                GeminiContent(
                    role = "user",
                    parts = (if (cachedContent != null) prompt.parts.drop(prompt.cachedParts) else prompt.parts).map(::partOf)
                )
            ),
            systemInstruction = if (cachedContent != null) null else GeminiContent(parts = listOf(GeminiPart.text(prompt.system))),
            cachedContent = cachedContent,
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseJsonSchema = prompt.schema,
                maxOutputTokens = prompt.maxOutputTokens,
                thinkingConfig = ThinkingConfig(prompt.thinkingLevel),
                mediaResolution = mediaResolution
            )
        )

        /** A part of a prompt as the API takes it. */
        fun partOf(part: PromptPart): GeminiPart = when (part) {
            is PromptPart.Text -> GeminiPart.text(part.text)
            is PromptPart.Image -> GeminiPart.inline("image/jpeg", Base64.encode(part.jpeg))
        }

        internal fun GeminiUsage?.toTokenUsage(): TokenUsage = TokenUsage(
            requests = 1,
            promptTokens = this?.promptTokenCount ?: 0,
            cachedTokens = this?.cachedContentTokenCount ?: 0,
            outputTokens = (this?.candidatesTokenCount ?: 0) + (this?.thoughtsTokenCount ?: 0),
            promptByModality = this?.promptTokensDetails.orEmpty()
                .filter { it.modality.isNotEmpty() }
                .groupBy({ it.modality }, { it.tokenCount })
                .mapValues { (_, counts) -> counts.sum() }
        )
    }
}
