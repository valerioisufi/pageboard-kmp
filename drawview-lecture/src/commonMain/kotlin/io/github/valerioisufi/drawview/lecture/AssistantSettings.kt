package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.core.platform.SecureStore
import io.github.valerioisufi.drawview.lecture.platform.isComputer
import io.github.valerioisufi.drawview.lecture.platform.environmentApiKey
import io.github.valerioisufi.drawview.lecture.platform.lectureDataDirectory
import io.github.valerioisufi.drawview.lecture.platform.restrictToOwner
import io.github.valerioisufi.drawview.lecture.transcription.WhisperTranscriber
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/**
 * Where the small secrets of the assistant are kept (the key of Gemini): the store of the system
 * ([SecureStore]: the Keychain, the Android Keystore, a file of the user on the desktop).
 */
interface SecretStorage {
    fun read(name: String): String?

    fun write(name: String, value: String?): Boolean

    /** The store of the system. */
    object System : SecretStorage {
        override fun read(name: String): String? = SecureStore.read(name)

        override fun write(name: String, value: String?): Boolean = SecureStore.write(name, value)
    }
}

/** Who answers a request: the Gemini API (online) or a model of Ollama (on this computer, or one of the local network). */
@Serializable
enum class ModelProvider { GEMINI, LOCAL }

/** What to do with a lecture received without a transcript (recorded where the models are not). */
@Serializable
enum class ReceivedLectures {
    /** Asks, saying what it would cost. */
    ASK,

    /** Transcribes and reviews it at once. */
    ALWAYS,
    NEVER
}

/** What a lecture can send to Google. */
enum class SharedData { AUDIO, TRANSCRIPT, NOTEBOOK }

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
 * @param apiKey key of the Gemini API (empty: the `GEMINI_API_KEY` environment variable), kept in
 *   the [SecretStorage], not with the other settings.
 * @param generateHere whether the models follow the lectures on this device (the computers by
 *   default); otherwise they are recorded only, and a device with the models writes their notes.
 * @param chunkSeconds new audio analyzed at every update.
 * @param notesLanguage language of the notes (empty: the language of the lecture).
 * @param tasks provider and models of every kind of request (see [task]).
 * @param localUrl address of the Ollama server (the local models).
 * @param whisperModel path of the ggml model of whisper.cpp (e.g. ggml-large-v3-turbo.bin).
 * @param whisperLanguage language spoken, ISO code ("it", "en") or "auto".
 * @param reviewAtTheEnd the final review is written when a lecture ends (otherwise on request).
 */
@Serializable
data class AssistantSettings(
    @Transient val apiKey: String = "",
    val generateHere: Boolean = isComputer,
    val chunkSeconds: Int = 60,
    val notesLanguage: String = "",
    val thinkingLevel: String = "low",
    val tasks: Map<LectureTask, TaskModel> = emptyMap(),
    val localUrl: String = DEFAULT_LOCAL_URL,
    val whisperModel: String = "",
    val whisperLanguage: String = "auto",
    /** What to do with the lectures received from devices without the models (see [ReceivedLectures]). */
    val receivedLectures: ReceivedLectures = ReceivedLectures.ASK,
    val reviewAtTheEnd: Boolean = true
) {
    /** Provider and models of [task] (the defaults when not configured). */
    fun task(task: LectureTask): TaskModel = tasks[task] ?: defaultTask(task)

    fun withTask(task: LectureTask, model: TaskModel): AssistantSettings = copy(tasks = tasks + (task to model))

    /** Provider of the final review. */
    val reviewProvider: ModelProvider get() = task(LectureTask.FINAL_REVIEW).provider

    /** Local model of the final review. */
    val localModel: String get() = task(LectureTask.FINAL_REVIEW).local

    /** The local models of the requests made during the lecture (kept loaded until its end). */
    val liveLocalModels: Set<String>
        get() = listOf(LectureTask.LIVE_NOTES, LectureTask.CATCH_UP).map { task(it) }
            .filter { it.provider == ModelProvider.LOCAL }
            .mapTo(mutableSetOf()) { it.local }

    /** The audio is sent to Google only when Gemini transcribes it: otherwise it stays on this computer. */
    val sendsAudio: Boolean get() = task(LectureTask.TRANSCRIPTION).provider == ModelProvider.GEMINI

    /** What the requests of a lecture send to Google (empty: everything stays on this computer). */
    val sentToGoogle: Set<SharedData>
        get() = buildSet {
            if (sendsAudio) add(SharedData.AUDIO)
            val online = listOf(LectureTask.LIVE_NOTES, LectureTask.CATCH_UP, LectureTask.FINAL_REVIEW)
                .any { task(it).provider == ModelProvider.GEMINI }
            if (online) {
                if (!sendsAudio) add(SharedData.TRANSCRIPT)
                add(SharedData.NOTEBOOK)
            }
        }

    /**
     * These settings with the combinations that cannot work corrected: the local models do not
     * receive audio, so the live suggestions answered by a local model need a local transcription;
     * where there is none ([canTranscribe] `false`: the phones), they are online.
     */
    fun consistent(canTranscribe: Boolean = true): AssistantSettings {
        var settings = this
        if (!canTranscribe && !settings.sendsAudio) {
            settings = settings.withTask(LectureTask.TRANSCRIPTION, settings.task(LectureTask.TRANSCRIPTION).copy(provider = ModelProvider.GEMINI))
        }
        if (settings.task(LectureTask.LIVE_NOTES).provider == ModelProvider.LOCAL && settings.sendsAudio) {
            settings = if (canTranscribe) {
                settings.withTask(LectureTask.TRANSCRIPTION, settings.task(LectureTask.TRANSCRIPTION).copy(provider = ModelProvider.LOCAL))
            } else {
                settings.withTask(LectureTask.LIVE_NOTES, settings.task(LectureTask.LIVE_NOTES).copy(provider = ModelProvider.GEMINI))
            }
        }
        return settings
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
 * Persists the [AssistantSettings] in `assistant-settings.json` of the [lectureDataDirectory], the
 * API key in [secrets]. [canTranscribe]: whether the speech is recognized on this device (see
 * [AssistantSettings.consistent]).
 */
class AssistantSettingsStore(
    private val path: String = lectureDataDirectory() + "/assistant-settings.json",
    private val secrets: SecretStorage = SecretStorage.System,
    private val canTranscribe: Boolean = WhisperTranscriber.isAvailable
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
            thinkingLevel = newSettings.thinkingLevel.takeIf { it in AssistantSettings.THINKING_LEVELS } ?: "low",
            tasks = LectureTask.entries.associateWith { task ->
                val model = newSettings.task(task)
                val defaults = AssistantSettings.defaultTask(task)
                model.copy(gemini = model.gemini.trim().ifEmpty { defaults.gemini }, local = model.local.trim().ifEmpty { defaults.local })
            },
            localUrl = newSettings.localUrl.trim().trimEnd('/').ifEmpty { AssistantSettings.DEFAULT_LOCAL_URL },
            whisperModel = newSettings.whisperModel.trim(),
            // An ISO code ("it") or "auto": a name typed by hand is not understood by whisper
            whisperLanguage = newSettings.whisperLanguage.trim().lowercase().takeIf { it == "auto" || ISO_LANGUAGE.matches(it) } ?: "auto"
        ).consistent(canTranscribe)
        if (sanitized.apiKey != settings.apiKey) secrets.write(API_KEY, sanitized.apiKey.ifEmpty { null })
        settings = sanitized
        if (PlatformFiles.writeBytes(path, json.encodeToString(AssistantSettings.serializer(), sanitized).encodeToByteArray())) {
            restrictToOwner(path)
        }
    }

    private fun load(): AssistantSettings {
        val saved = PlatformFiles.readBytes(path)?.let { bytes ->
            try {
                json.decodeFromString(AssistantSettings.serializer(), bytes.decodeToString())
            } catch (_: Exception) {
                null
            }
        } ?: AssistantSettings()
        return saved.copy(apiKey = secrets.read(API_KEY).orEmpty())
    }

    internal companion object {
        private val ISO_LANGUAGE = Regex("[a-z]{2,3}")

        /** The name of the key of Gemini in the [SecretStorage]. */
        const val API_KEY = "gemini-api-key"

        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }
}
