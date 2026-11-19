package io.github.valerioisufi.pageboard.lecture.model

import io.github.valerioisufi.pageboard.lecture.Lecture
import io.github.valerioisufi.pageboard.lecture.LectureProblem
import io.github.valerioisufi.pageboard.lecture.ModelProvider
import io.github.valerioisufi.pageboard.lecture.local.OllamaChatRequest
import io.github.valerioisufi.pageboard.lecture.local.OllamaClient
import io.github.valerioisufi.pageboard.lecture.local.OllamaException
import io.github.valerioisufi.pageboard.lecture.local.OllamaMessage
import io.github.valerioisufi.pageboard.lecture.local.OllamaOptions
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

    override val limits = LIMITS

    /** Whether the server answers is known at the request only. */
    override fun unavailable(): LectureProblem? = null

    override fun newPromptCache(): PromptCache? = null

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

    /** Answers, the model kept loaded during the lecture ([ModelUse.LIVE]) or unloaded right away. */
    override suspend fun generate(prompt: ModelPrompt, model: String, use: ModelUse, cache: PromptCache?, onProgress: (Int) -> Unit): ModelAnswer =
        generate(prompt, model, keepLoadedSeconds = if (use == ModelUse.LIVE) LIVE_KEEP_LOADED_SECONDS else 0, onProgress)

    /** The errors of the server are temporary, not a model missing or a request refused. */
    override fun isRetryable(error: Throwable): Boolean? = (error as? OllamaException)?.let { it.statusCode >= 500 }

    override suspend fun release(models: Set<String>) {
        models.forEach { unload(it) }
    }

    /** Frees the memory taken by [model] (e.g. at the end of the lecture). */
    suspend fun unload(model: String) {
        try {
            client.chat(url(), OllamaChatRequest(model = model, messages = emptyList(), keepAlive = 0))
        } catch (_: OllamaException) {
            // Not loaded, or the server is not running
        }
    }

    companion object {
        /** The local models read fewer images and blocks a request (a smaller context, a slower answer). */
        val LIMITS = ModelLimits(imagePages = 1, reviewImages = Lecture.MAX_LOCAL_REVIEW_IMAGES, handwritingBatch = 3, handwritingPerUpdate = 3)

        /** The problem shown for [error], `null` when it is not Ollama missing a model or not running. */
        fun problemOf(error: Throwable): LectureProblem? =
            if (error is OllamaException && (error.statusCode == 0 || error.statusCode == 404)) {
                LectureProblem.LocalModelUnavailable(error.message ?: error::class.simpleName.orEmpty())
            } else {
                null
            }

        /** About 3 characters per token, an image ~600 tokens, plus the answer; at least 8k. */
        fun contextSize(system: String, text: String, images: Int, answerTokens: Int): Int {
            val estimate = (system.length + text.length) / 3 + images * 600 + answerTokens
            return ((estimate + 4095) / 4096 * 4096).coerceIn(8192, 65536)
        }

        /** Seconds a model answering every minute stays loaded between two requests. */
        const val LIVE_KEEP_LOADED_SECONDS = 600
    }
}
