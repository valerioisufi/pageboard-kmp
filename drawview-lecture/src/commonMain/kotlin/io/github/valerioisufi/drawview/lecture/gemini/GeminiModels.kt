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
    val thought: Boolean? = null,
    /** The words recognized by a speech model (with their times, when asked). */
    val audioTranscription: GeminiAudioTranscription? = null
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

/**
 * The configuration of the speech models (Gemini 3.5 Transcribe): the languages spoken (BCP-47,
 * empty: recognized), the words to recognize (at most 1000, best within 100) and the mode,
 * `VERBATIM` (every word) or `SMART` (no hesitations, numbers and lists formatted).
 */
@Serializable
data class AudioTranscriptionConfig(
    val languageCodes: List<String> = emptyList(),
    val customVocabulary: List<String>? = null,
    val mode: String? = null
)

/** What a speech model recognized in a part of its answer. */
@Serializable
data class GeminiAudioTranscription(
    val speakerLabel: String? = null,
    val words: List<GeminiWord> = emptyList()
)

@Serializable
data class GeminiWord(val word: String = "", val startOffset: String? = null, val endOffset: String? = null)

@Serializable
data class GenerationConfig(
    val responseMimeType: String? = null,
    val responseJsonSchema: JsonElement? = null,
    val maxOutputTokens: Int? = null,
    val thinkingConfig: ThinkingConfig? = null,
    /** Tokens per image and PDF page: MEDIA_RESOLUTION_LOW, _MEDIUM or _HIGH. */
    val mediaResolution: String? = null,
    /** The transcription of a speech model (the only content of its answer). */
    val audioTranscriptionConfig: AudioTranscriptionConfig? = null
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

    /** The text of a speech model: its text, or its words when it answers with them only. */
    fun transcriptionText(): String {
        val parts = candidates.firstOrNull()?.content?.parts.orEmpty().filter { it.thought != true }
        val text = parts.mapNotNull { it.text }.joinToString("").trim()
        if (text.isNotEmpty()) return text
        return parts.flatMap { it.audioTranscription?.words.orEmpty() }.joinToString(" ") { it.word.trim() }.trim()
    }
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
    val totalTokenCount: Long = 0,
    /** Prompt tokens by modality (TEXT, IMAGE, AUDIO, DOCUMENT, ...). */
    val promptTokensDetails: List<GeminiModalityTokens> = emptyList()
)

@Serializable
data class GeminiModalityTokens(val modality: String = "", val tokenCount: Long = 0)

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
