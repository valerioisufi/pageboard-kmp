package io.github.valerioisufi.drawview.lecture.gemini

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Subset of the Gemini API (generativelanguage.googleapis.com, v1beta) used by the assistant.

@Serializable
internal data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart> = emptyList()
)

@Serializable
internal data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiBlob? = null,
    /** `true` for the thought summaries of the thinking models (not part of the answer). */
    val thought: Boolean? = null
) {
    companion object {
        fun text(value: String) = GeminiPart(text = value)
        fun inline(mimeType: String, base64: String) = GeminiPart(inlineData = GeminiBlob(mimeType, base64))
    }
}

/** Inline file content, base64-encoded. */
@Serializable
internal data class GeminiBlob(val mimeType: String, val data: String)

@Serializable
internal data class ThinkingConfig(val thinkingLevel: String? = null)

@Serializable
internal data class GenerationConfig(
    val responseMimeType: String? = null,
    val responseJsonSchema: JsonElement? = null,
    val maxOutputTokens: Int? = null,
    val thinkingConfig: ThinkingConfig? = null,
    /** Tokens per image and PDF page: MEDIA_RESOLUTION_LOW, _MEDIUM or _HIGH. */
    val mediaResolution: String? = null
)

/**
 * @param cachedContent the name of a [GeminiCachedContent] that opens the request: it holds the
 *   system instruction, which the request then leaves out.
 */
@Serializable
internal data class GenerateContentRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GenerationConfig? = null,
    val cachedContent: String? = null
)

/** The content of an explicit cache: [model] as `models/<id>`, [ttl] as `"600s"`. */
@Serializable
internal data class CreateCachedContentRequest(
    val model: String,
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val ttl: String
)

/** The time to live of a cache, when it is extended. */
@Serializable
internal data class CachedContentTtl(val ttl: String)

/** An explicit cache (`cachedContents/<id>`), deleted by itself at [expireTime]. */
@Serializable
internal data class GeminiCachedContent(
    val name: String = "",
    val model: String = "",
    val expireTime: String? = null,
    val usageMetadata: GeminiCacheUsage? = null
)

@Serializable
internal data class GeminiCacheUsage(val totalTokenCount: Long = 0)

@Serializable
internal data class GenerateContentResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val promptFeedback: GeminiPromptFeedback? = null,
    val usageMetadata: GeminiUsage? = null
) {
    /** Text of the answer (thought summaries excluded). */
    fun text(): String = candidates.firstOrNull()?.content?.parts.orEmpty()
        .filter { it.thought != true }
        .mapNotNull { it.text }
        .joinToString("")
}

@Serializable
internal data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null
)

@Serializable
internal data class GeminiPromptFeedback(val blockReason: String? = null)

@Serializable
internal data class GeminiUsage(
    val promptTokenCount: Long = 0,
    val candidatesTokenCount: Long = 0,
    val cachedContentTokenCount: Long = 0,
    val thoughtsTokenCount: Long = 0,
    val totalTokenCount: Long = 0,
    /** Prompt tokens by modality (TEXT, IMAGE, ...). */
    val promptTokensDetails: List<GeminiModalityTokens> = emptyList()
)

@Serializable
internal data class GeminiModalityTokens(val modality: String = "", val tokenCount: Long = 0)

@Serializable
internal data class GeminiErrorEnvelope(val error: GeminiErrorBody? = null)

@Serializable
internal data class GeminiErrorBody(val code: Int = 0, val message: String = "", val status: String = "")
