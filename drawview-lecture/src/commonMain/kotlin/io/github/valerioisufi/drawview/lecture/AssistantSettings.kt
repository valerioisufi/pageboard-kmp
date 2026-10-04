package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.lecture.platform.environmentApiKey
import io.github.valerioisufi.drawview.lecture.platform.lectureDataDirectory
import io.github.valerioisufi.drawview.lecture.platform.restrictToOwner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Who answers a request: the Gemini API (online) or a model on this computer. */
@Serializable
enum class ModelProvider { GEMINI, LOCAL }

/** Kept for the code written with the first name of [ModelProvider]. */
typealias ReviewProvider = ModelProvider

/** The kinds of requests of the assistant, each answered by the provider chosen for it. */
@Serializable
enum class LectureTask {
    /** Speech to text: within the live request (Gemini) or by whisper.cpp (local). */
    TRANSCRIPTION,

    /** The suggestions of every update during the lecture (and the outline). */
    LIVE_NOTES,

    /** "I missed something": a recap of the last minutes. */
    CATCH_UP,

    /** The review of the whole lecture. */
    FINAL_REVIEW
}

/**
 * The provider of a [LectureTask] and the model of each provider (kept when switching).
 */
@Serializable
data class TaskModel(
    val provider: ModelProvider = ModelProvider.GEMINI,
    val gemini: String = AssistantSettings.DEFAULT_MODEL,
    val local: String = AssistantSettings.DEFAULT_LOCAL_MODEL
) {
    /** The model of the chosen provider. */
    val model: String get() = if (provider == ModelProvider.GEMINI) gemini else local
}

/**
 * Settings of the lecture assistant.
 *
 * @param apiKey key of the Gemini API (empty: the `GEMINI_API_KEY` environment variable).
 * @param chunkSeconds new audio analyzed at every update.
 * @param neighborPages pages sent around the visible ones (before and after).
 * @param sendSlides sends the PDF documents of the pages around the visible ones (uploaded once).
 * @param notesLanguage language of the notes (empty: the language of the lecture).
 * @param courseContext course, topic and terminology, written by the student.
 * @param tasks provider and models of every kind of request (see [task]).
 * @param localUrl address of the Ollama server (the local models).
 * @param whisperServer path of the `whisper-server` program of whisper.cpp (empty: found in the
 *   usual places).
 * @param whisperModel path of the ggml model of whisper.cpp (e.g. ggml-large-v3-turbo.bin).
 * @param whisperLanguage language spoken, ISO code ("it", "en") or "auto".
 * @param priceInput Gemini prices in dollars per million tokens (input, cached input, output),
 *   used to estimate the cost of a lecture.
 */
@Serializable
data class AssistantSettings(
    val apiKey: String = "",
    val chunkSeconds: Int = 60,
    val neighborPages: Int = 1,
    val sendSlides: Boolean = true,
    val notesLanguage: String = "",
    val courseContext: String = "",
    val thinkingLevel: String = "low",
    val tasks: Map<LectureTask, TaskModel> = emptyMap(),
    val localUrl: String = DEFAULT_LOCAL_URL,
    val whisperServer: String = "",
    val whisperModel: String = "",
    val whisperLanguage: String = "auto",
    val priceInput: Double = 0.75,
    val priceCachedInput: Double = 0.075,
    val priceOutput: Double = 3.75
) {
    /** Provider and models of [task] (the defaults when not configured). */
    fun task(task: LectureTask): TaskModel = tasks[task] ?: defaultTask(task)

    fun withTask(task: LectureTask, model: TaskModel): AssistantSettings = copy(tasks = tasks + (task to model))

    /** Gemini model of the live analysis (used by the first versions of the settings). */
    val model: String get() = task(LectureTask.LIVE_NOTES).gemini

    /** Provider of the final review. */
    val reviewProvider: ModelProvider get() = task(LectureTask.FINAL_REVIEW).provider

    /** Local model of the final review. */
    val localModel: String get() = task(LectureTask.FINAL_REVIEW).local

    /** The audio is sent to Google only when Gemini transcribes it: otherwise it stays on this computer. */
    val sendsAudio: Boolean get() = task(LectureTask.TRANSCRIPTION).provider == ModelProvider.GEMINI

    /**
     * These settings with the combinations that cannot work corrected: the local models do not
     * receive audio, so the live suggestions answered by a local model need a local transcription.
     */
    fun consistent(): AssistantSettings =
        if (task(LectureTask.LIVE_NOTES).provider == ModelProvider.LOCAL && sendsAudio) {
            withTask(LectureTask.TRANSCRIPTION, task(LectureTask.TRANSCRIPTION).copy(provider = ModelProvider.LOCAL))
        } else {
            this
        }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_LOCAL_MODEL = "gemma4:12b"

        /** Smaller local model for the requests of every minute (it must keep up with the lecture). */
        const val DEFAULT_LOCAL_LIVE_MODEL = "gemma4:e4b"
        const val DEFAULT_LOCAL_URL = "http://localhost:11434"
        val CHUNK_SECONDS_RANGE = 30..120
        val THINKING_LEVELS = listOf("low", "medium", "high")

        fun defaultTask(task: LectureTask) = when (task) {
            LectureTask.LIVE_NOTES, LectureTask.CATCH_UP -> TaskModel(local = DEFAULT_LOCAL_LIVE_MODEL)
            else -> TaskModel()
        }
    }
}

/**
 * Persists the [AssistantSettings] in `assistant-settings.json` of the [lectureDataDirectory]
 * (readable by the owner only: it holds the API key).
 */
class AssistantSettingsStore(
    private val path: String = lectureDataDirectory() + "/assistant-settings.json"
) {
    var settings: AssistantSettings by mutableStateOf(load())
        private set

    /** The configured key, or the `GEMINI_API_KEY` environment variable. */
    val apiKey: String get() = settings.apiKey.trim().ifEmpty { environmentApiKey().orEmpty() }

    val hasApiKey: Boolean get() = apiKey.isNotEmpty()

    /** `true` when the key comes from the environment variable. */
    val usesEnvironmentKey: Boolean get() = settings.apiKey.isBlank() && !environmentApiKey().isNullOrEmpty()

    fun update(newSettings: AssistantSettings) {
        val sanitized = newSettings.copy(
            apiKey = newSettings.apiKey.trim(),
            chunkSeconds = newSettings.chunkSeconds.coerceIn(AssistantSettings.CHUNK_SECONDS_RANGE),
            neighborPages = newSettings.neighborPages.coerceIn(0, 3),
            thinkingLevel = newSettings.thinkingLevel.takeIf { it in AssistantSettings.THINKING_LEVELS } ?: "low",
            tasks = LectureTask.entries.associateWith { task ->
                val model = newSettings.task(task)
                val defaults = AssistantSettings.defaultTask(task)
                model.copy(gemini = model.gemini.trim().ifEmpty { defaults.gemini }, local = model.local.trim().ifEmpty { defaults.local })
            },
            localUrl = newSettings.localUrl.trim().trimEnd('/').ifEmpty { AssistantSettings.DEFAULT_LOCAL_URL },
            whisperServer = newSettings.whisperServer.trim(),
            whisperModel = newSettings.whisperModel.trim(),
            whisperLanguage = newSettings.whisperLanguage.trim().lowercase().ifEmpty { "auto" },
            priceInput = newSettings.priceInput.coerceAtLeast(0.0),
            priceCachedInput = newSettings.priceCachedInput.coerceAtLeast(0.0),
            priceOutput = newSettings.priceOutput.coerceAtLeast(0.0)
        ).consistent()
        settings = sanitized
        if (PlatformFiles.writeBytes(path, json.encodeToString(AssistantSettings.serializer(), sanitized).encodeToByteArray())) {
            restrictToOwner(path)
        }
    }

    private fun load(): AssistantSettings {
        val bytes = PlatformFiles.readBytes(path) ?: return AssistantSettings()
        return try {
            val text = bytes.decodeToString()
            migrate(json.decodeFromString(AssistantSettings.serializer(), text), json.parseToJsonElement(text).jsonObject)
        } catch (_: Exception) {
            AssistantSettings()
        }
    }

    internal companion object {
        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        /**
         * The first settings had one Gemini model ("model"), the provider and the local model of
         * the final review ("reviewProvider", "localModel"): they become the [LectureTask]s.
         */
        fun migrate(settings: AssistantSettings, saved: JsonObject): AssistantSettings {
            if ("tasks" in saved) return settings
            val model = saved["model"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return settings
            val reviewProvider = saved["reviewProvider"]?.jsonPrimitive?.content
                ?.let { name -> ModelProvider.entries.firstOrNull { it.name == name } } ?: ModelProvider.GEMINI
            val localModel = saved["localModel"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: AssistantSettings.DEFAULT_LOCAL_MODEL
            return settings.copy(
                tasks = LectureTask.entries.associateWith { task ->
                    val defaults = AssistantSettings.defaultTask(task)
                    if (task == LectureTask.FINAL_REVIEW) TaskModel(reviewProvider, model, localModel)
                    else defaults.copy(gemini = model)
                }
            )
        }
    }
}
