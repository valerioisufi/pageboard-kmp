package io.github.valerioisufi.drawview.lecture.model

import io.github.valerioisufi.drawview.lecture.ModelProvider
import io.github.valerioisufi.drawview.lecture.local.OllamaChatRequest
import io.github.valerioisufi.drawview.lecture.local.OllamaClient
import io.github.valerioisufi.drawview.lecture.local.OllamaException
import io.github.valerioisufi.drawview.lecture.local.OllamaMessage
import io.github.valerioisufi.drawview.lecture.local.OllamaOptions
import kotlin.io.encoding.Base64

/**
 * The models of Ollama on this computer as a [ModelBackend]: free, private, slower.
 *
 * Ollama takes text and images (attached to the message, in order: the text marks where each
 * one belongs), no audio nor files. A model used every minute stays loaded (`keepLoadedSeconds`);
 * [unload] frees the memory when it is no longer needed.
 *
 * @param url the address of the Ollama server of the settings (read at every request).
 */
internal class OllamaBackend(public val client: OllamaClient, private val url: () -> String) : ModelBackend {
    override val provider = ModelProvider.LOCAL

    /**
     * @param keepLoadedSeconds seconds the model stays in memory after the answer (0: unloaded).
     * @throws OllamaException when the server cannot be reached, the model is missing or the
     *   prompt holds audio or files.
     */
    suspend fun generate(prompt: ModelPrompt, model: String, keepLoadedSeconds: Int, onProgress: (Int) -> Unit): ModelAnswer {
        val images = mutableListOf<String>()
        val text = buildString {
            for (part in prompt.parts) {
                when (part) {
                    is PromptPart.Text -> appendLine(part.text).appendLine()
                    is PromptPart.Image -> {
                        images.add(Base64.encode(part.jpeg))
                        appendLine("[attached image ${images.size}]").appendLine()
                    }
                    is PromptPart.Audio, is PromptPart.File ->
                        throw OllamaException(400, "A local model receives neither audio nor files")
                }
            }
        }.trim()
        val request = OllamaChatRequest(
            model = model,
            messages = listOf(
                OllamaMessage("system", prompt.system),
                OllamaMessage("user", text, images.ifEmpty { null })
            ),
            format = prompt.schema,
            think = false,
            keepAlive = keepLoadedSeconds,
            options = OllamaOptions(numCtx = contextSize(prompt.system, text, images.size, prompt.maxOutputTokens), temperature = 0.4f)
        )
        val result = client.chat(url(), request, onProgress)
        return ModelAnswer(result.content, usage = null)
    }

    /** Answers and unloads the model right away (a request done once, e.g. the final review). */
    override suspend fun generate(prompt: ModelPrompt, model: String, onProgress: (Int) -> Unit): ModelAnswer =
        generate(prompt, model, keepLoadedSeconds = 0, onProgress)

    /** Frees the memory taken by [model] (e.g. at the end of the lecture). */
    suspend fun unload(model: String) {
        try {
            client.chat(url(), OllamaChatRequest(model = model, messages = emptyList(), keepAlive = 0))
        } catch (_: OllamaException) {
            // Not loaded, or the server is not running
        }
    }

    companion object {
        /** About 3 characters per token, an image ~600 tokens, plus the answer; at least 8k. */
        fun contextSize(system: String, text: String, images: Int, answerTokens: Int): Int {
            val estimate = (system.length + text.length) / 3 + images * 600 + answerTokens
            return ((estimate + 4095) / 4096 * 4096).coerceIn(8192, 65536)
        }

        /** Seconds a model answering every minute stays loaded between two requests. */
        const val LIVE_KEEP_LOADED_SECONDS = 600
    }
}
