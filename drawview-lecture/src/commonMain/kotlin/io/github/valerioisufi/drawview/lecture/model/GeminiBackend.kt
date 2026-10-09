package io.github.valerioisufi.drawview.lecture.model

import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.TokenUsage
import io.github.valerioisufi.drawview.lecture.gemini.AudioTranscriptionConfig
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
class GeminiBackend(val client: GeminiClient, private val apiKey: () -> String) : ModelBackend {
    override val provider = ModelProvider.GEMINI

    @Volatile
    private var mediaResolution: String? = MEDIA_RESOLUTION

    /**
     * @throws GeminiException also for a missing key (status 401).
     */
    override suspend fun generate(prompt: ModelPrompt, model: String, onProgress: (Int) -> Unit): ModelAnswer {
        val key = apiKey()
        if (key.isEmpty()) throw GeminiException(401, "API key not set")
        val response = try {
            client.generateContent(key, model, toRequest(prompt, mediaResolution))
        } catch (e: GeminiException) {
            if (mediaResolution == null || e.statusCode != 400 || !e.message.orEmpty().contains("resolution", ignoreCase = true)) throw e
            mediaResolution = null
            client.generateContent(key, model, toRequest(prompt, null))
        }
        return ModelAnswer(response.text(), response.usageMetadata.toTokenUsage())
    }

    /**
     * Transcribes [wav] with the speech model [model] (Gemini 3.5 Transcribe): the audio only,
     * no instructions.
     *
     * @throws GeminiException also for a missing key (status 401).
     */
    suspend fun transcribe(wav: ByteArray, model: String, config: AudioTranscriptionConfig): ModelAnswer {
        val key = apiKey()
        if (key.isEmpty()) throw GeminiException(401, "API key not set")
        val request = GenerateContentRequest(
            contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart.inline("audio/wav", Base64.encode(wav))))),
            generationConfig = GenerationConfig(audioTranscriptionConfig = config)
        )
        val response = client.generateContent(key, model, request)
        return ModelAnswer(response.transcriptionText(), response.usageMetadata.toTokenUsage())
    }

    /** The key of the settings, for the connections of the Live API. */
    internal fun currentApiKey(): String = apiKey()

    companion object {
        /**
         * Tokens per image and PDF page: medium (560 for Gemini 3) instead of high (1120 by default
         * for the images); the images show areas of the page, readable at this resolution.
         */
        const val MEDIA_RESOLUTION = "MEDIA_RESOLUTION_MEDIUM"

        /** The request of the Gemini API for [prompt]. */
        fun toRequest(prompt: ModelPrompt, mediaResolution: String? = MEDIA_RESOLUTION) = GenerateContentRequest(
            contents = listOf(
                GeminiContent(
                    role = "user",
                    parts = prompt.parts.map { part ->
                        when (part) {
                            is PromptPart.Text -> GeminiPart.text(part.text)
                            is PromptPart.Image -> GeminiPart.inline("image/jpeg", Base64.encode(part.jpeg))
                            is PromptPart.Audio -> GeminiPart.inline("audio/wav", Base64.encode(part.wav))
                            is PromptPart.File -> GeminiPart.file(part.mimeType, part.uri)
                        }
                    }
                )
            ),
            systemInstruction = GeminiContent(parts = listOf(GeminiPart.text(prompt.system))),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                responseJsonSchema = prompt.schema,
                maxOutputTokens = prompt.maxOutputTokens,
                thinkingConfig = ThinkingConfig(prompt.thinkingLevel),
                mediaResolution = mediaResolution
            )
        )

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
