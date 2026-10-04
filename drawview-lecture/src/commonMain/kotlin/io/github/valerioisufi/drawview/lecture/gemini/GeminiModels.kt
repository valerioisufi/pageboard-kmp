package io.github.valerioisufi.drawview.lecture.gemini

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Subset of the Gemini API (generativelanguage.googleapis.com, v1beta) used by the assistant.

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart> = emptyList()
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiBlob? = null,
    val fileData: GeminiFileData? = null,
    /** `true` for the thought summaries of the thinking models (not part of the answer). */
    val thought: Boolean? = null
) {
    companion object {
        fun text(value: String) = GeminiPart(text = value)
        fun inline(mimeType: String, base64: String) = GeminiPart(inlineData = GeminiBlob(mimeType, base64))
        fun file(mimeType: String, uri: String) = GeminiPart(fileData = GeminiFileData(mimeType, uri))
    }
}

/** Inline file content, base64-encoded. */
@Serializable
data class GeminiBlob(val mimeType: String, val data: String)

/** A file uploaded with the Files API. */
@Serializable
data class GeminiFileData(val mimeType: String, val fileUri: String)

@Serializable
data class ThinkingConfig(val thinkingLevel: String? = null)

@Serializable
data class GenerationConfig(
    val responseMimeType: String? = null,
    val responseJsonSchema: JsonElement? = null,
    val maxOutputTokens: Int? = null,
    val thinkingConfig: ThinkingConfig? = null
)

@Serializable
data class GenerateContentRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GenerationConfig? = null
)

@Serializable
data class GenerateContentResponse(
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
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null
)

@Serializable
data class GeminiPromptFeedback(val blockReason: String? = null)

@Serializable
data class GeminiUsage(
    val promptTokenCount: Long = 0,
    val candidatesTokenCount: Long = 0,
    val cachedContentTokenCount: Long = 0,
    val thoughtsTokenCount: Long = 0,
    val totalTokenCount: Long = 0
)

/** Metadata of a file of the Files API (kept for 48 hours). */
@Serializable
data class GeminiFile(
    val name: String = "",
    val uri: String = "",
    val mimeType: String = "",
    /** PROCESSING, ACTIVE or FAILED. */
    val state: String = "",
    val expirationTime: String? = null
)

@Serializable
internal data class GeminiFileEnvelope(val file: GeminiFile)

@Serializable
internal data class GeminiUploadStart(val file: GeminiUploadName)

@Serializable
internal data class GeminiUploadName(val displayName: String)

@Serializable
internal data class GeminiErrorEnvelope(val error: GeminiErrorBody? = null)

@Serializable
internal data class GeminiErrorBody(val code: Int = 0, val message: String = "", val status: String = "")
