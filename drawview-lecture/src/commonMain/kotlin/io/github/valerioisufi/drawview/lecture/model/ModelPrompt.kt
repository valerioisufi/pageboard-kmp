package io.github.valerioisufi.drawview.lecture.model

import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.LectureProblem
import io.github.valerioisufi.drawview.lecture.LectureTask
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
 * @param schema the JSON schema of the answer; `null` for an answer in plain text.
 * @param thinkingLevel reasoning of the Gemini models (low, medium, high).
 * @param cachedParts leading [parts] that, with [system], stay the same in every request of a
 *   lecture: a provider may cache them; the others ignore it.
 */
internal class ModelPrompt(
    val system: String,
    val parts: List<PromptPart>,
    val schema: JsonObject?,
    val maxOutputTokens: Int,
    val thinkingLevel: String,
    val cachedParts: Int = 0
)

/**
 * The answer to a [ModelPrompt].
 *
 * @param usage tokens billed (Gemini); `null` for a local model, which is free.
 */
internal class ModelAnswer(public val text: String, public val usage: TokenUsage?) {
    /** The tokens of the answer, a request of a local model when it is free. */
    val usageOrLocal: TokenUsage get() = usage ?: TokenUsage(localRequests = 1)
}

/** How long a local model stays in memory after an answer. */
internal enum class ModelUse {
    /** A request of every few minutes during a lecture: the model stays loaded until its end. */
    LIVE,

    /** A request done once (the review, the text notes): the model is freed at once. */
    ONCE
}

/**
 * What a provider takes in a request of the lecture.
 *
 * @param imagePages the pages sent as images with a request during the lecture.
 * @param reviewImages the pages sent as images with the final review.
 * @param handwritingBatch the blocks of handwriting read by one request.
 * @param handwritingPerUpdate the blocks of handwriting read after an update of the lecture.
 */
internal class ModelLimits(
    val imagePages: Int,
    val reviewImages: Int,
    val handwritingBatch: Int,
    val handwritingPerUpdate: Int
)

/**
 * The start of the requests of a lecture kept by a provider, so that it is not sent again with
 * every request (see [ModelPrompt.cachedParts]); deleted when the lecture ends ([clear]).
 */
internal interface PromptCache {
    suspend fun clear()
}

/**
 * A provider of models answering [ModelPrompt]s, and what it needs of the requests of a lecture
 * (Polymorphism: the callers never ask which provider they hold).
 */
internal interface ModelBackend {
    val provider: ModelProvider

    val limits: ModelLimits

    /** Why it cannot answer now (the key of Gemini missing), `null` when it can. */
    fun unavailable(): LectureProblem?

    /**
     * A cache of the start of the requests of a lecture, `null` when this provider keeps none (the
     * start is then left out of the requests: see [LecturePrompts]).
     */
    fun newPromptCache(): PromptCache?

    /**
     * Answers [prompt] with [model].
     *
     * @param use how long a local model stays loaded after it.
     * @param cache the start of the requests of the lecture, from [newPromptCache].
     * @param onProgress tokens written so far (local models).
     */
    suspend fun generate(
        prompt: ModelPrompt,
        model: String,
        use: ModelUse = ModelUse.ONCE,
        cache: PromptCache? = null,
        onProgress: (Int) -> Unit = {}
    ): ModelAnswer

    /**
     * Whether [error], thrown by [generate], may pass when the request is repeated; `null` when it
     * is not an error of this provider (a network error, an answer that cannot be read).
     */
    fun isRetryable(error: Throwable): Boolean?

    /** Frees the [models] kept in memory for a lecture ([ModelUse.LIVE]). */
    suspend fun release(models: Set<String>)
}

/** The backend and the model chosen for a task by the settings. */
internal class TaskBackend(val backend: ModelBackend, val model: String) {
    val provider: ModelProvider get() = backend.provider

    val limits: ModelLimits get() = backend.limits

    fun unavailable(): LectureProblem? = backend.unavailable()

    suspend fun generate(prompt: ModelPrompt, use: ModelUse = ModelUse.ONCE, cache: PromptCache? = null, onProgress: (Int) -> Unit = {}): ModelAnswer =
        backend.generate(prompt, model, use, cache, onProgress)
}

/** The providers of the assistant: the one of a task is chosen by the settings ([forTask]). */
internal class ModelBackends(internal val gemini: GeminiBackend, internal val local: OllamaBackend) {
    private val all: List<ModelBackend> = listOf(gemini, local)

    fun of(provider: ModelProvider): ModelBackend = all.first { it.provider == provider }

    /** The backend and the model of [task] in [settings]. */
    fun forTask(settings: AssistantSettings, task: LectureTask): TaskBackend {
        val chosen = settings.task(task)
        return TaskBackend(of(chosen.provider), chosen.model)
    }

    /** The backend of the review by [provider] (the one claimed by the lecture), with its model in [settings]. */
    fun forReview(settings: AssistantSettings, provider: ModelProvider): TaskBackend {
        val chosen = settings.task(LectureTask.FINAL_REVIEW).copy(provider = provider)
        return TaskBackend(of(provider), chosen.model)
    }

    /** Frees the models kept in memory for the requests made during a lecture ([AssistantSettings.liveModels]). */
    suspend fun releaseLiveModels(settings: AssistantSettings) {
        for ((provider, models) in settings.liveModels) of(provider).release(models)
    }
}
