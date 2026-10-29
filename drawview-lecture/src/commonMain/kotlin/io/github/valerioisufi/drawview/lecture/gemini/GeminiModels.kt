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
internal data class GeminiBlob(val mimeType: String, val data: String)

/** A file uploaded with the Files API. */
@Serializable
internal data class GeminiFileData(val mimeType: String, val fileUri: String)

@Serializable
internal data class ThinkingConfig(val thinkingLevel: String? = null)

/**
 * The configuration of the speech models (Gemini 3.5 Transcribe): the languages spoken (BCP-47,
 * empty: recognized), the words to recognize (at most 1000, best within 100) and the mode,
 * `VERBATIM` (every word) or `SMART` (no hesitations, numbers and lists formatted).
 */
@Serializable
internal data class AudioTranscriptionConfig(
    val languageCodes: List<String> = emptyList(),
    val customVocabulary: List<String>? = null,
    val mode: String? = null
)

/** What a speech model recognized in a part of its answer: its [text], and its [words] when asked. */
@Serializable
internal data class GeminiAudioTranscription(
    val text: String? = null,
    val speakerLabel: String? = null,
    val words: List<GeminiWord> = emptyList()
)

@Serializable
internal data class GeminiWord(val word: String = "", val startOffset: String? = null, val endOffset: String? = null)

@Serializable
internal data class GenerationConfig(
    val responseMimeType: String? = null,
    val responseJsonSchema: JsonElement? = null,
    val maxOutputTokens: Int? = null,
    val thinkingConfig: ThinkingConfig? = null,
    /** Tokens per image and PDF page: MEDIA_RESOLUTION_LOW, _MEDIUM or _HIGH. */
    val mediaResolution: String? = null,
    /** The transcription of a speech model (the only content of its answer). */
    val audioTranscriptionConfig: AudioTranscriptionConfig? = null
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

    /** The text of a speech model: the text of its transcription (or of its words), or a text part. */
    fun transcriptionText(): String {
        val parts = candidates.firstOrNull()?.content?.parts.orEmpty().filter { it.thought != true }
        val transcribed = parts.mapNotNull { part ->
            part.audioTranscription?.let { it.text ?: it.words.joinToString(" ") { word -> word.word.trim() } }
        }.joinToString(" ").trim()
        if (transcribed.isNotEmpty()) return transcribed
        return parts.mapNotNull { it.text }.joinToString("").trim()
    }
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
    /** Prompt tokens by modality (TEXT, IMAGE, AUDIO, DOCUMENT, ...). */
    val promptTokensDetails: List<GeminiModalityTokens> = emptyList()
)

@Serializable
internal data class GeminiModalityTokens(val modality: String = "", val tokenCount: Long = 0)

/** Metadata of a file of the Files API (kept for 48 hours). */
@Serializable
internal data class GeminiFile(
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
