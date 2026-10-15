package io.github.valerioisufi.drawview.lecture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.lecture.platform.environmentApiKey
import io.github.valerioisufi.drawview.lecture.platform.isComputer
import io.github.valerioisufi.drawview.lecture.platform.lectureDataDirectory
import io.github.valerioisufi.drawview.lecture.platform.restrictToOwner
import io.github.valerioisufi.drawview.lecture.transcription.WhisperTranscriber
import io.github.valerioisufi.drawview.platform.PlatformFiles
import io.github.valerioisufi.drawview.platform.SecureStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/**
 * Where the small secrets of the assistant are kept (the key of Gemini): the store of the system
 * ([SecureStore]: the Keychain, the Android Keystore, a file of the user on the desktop).
 */
public interface SecretStorage {
    public fun read(name: String): String?

    public fun write(name: String, value: String?): Boolean

    /** The store of the system. */
    public object System : SecretStorage {
        override fun read(name: String): String? = SecureStore.read(name)

        override fun write(name: String, value: String?): Boolean = SecureStore.write(name, value)
    }
}

/** Who answers a request: the Gemini API (online) or a model of Ollama (on this computer, or one of the local network). */
@Serializable
public enum class ModelProvider { GEMINI, LOCAL }

/** What to do with a lecture received without a transcript (recorded where the models are not). */
@Serializable
public enum class ReceivedLectures {
    /** Asks, saying what it would cost. */
    ASK,

    /** Transcribes and reviews it at once. */
    ALWAYS,
    NEVER
}

/** What a lecture can send to Google. */
public enum class SharedData { AUDIO, TRANSCRIPT, NOTEBOOK }

/** The kinds of requests of the assistant, each answered by the provider chosen for it. */
@Serializable
public enum class LectureTask {
    /** Speech to text: by a model of Gemini (see [TranscriptionMode]) or by whisper.cpp (local). */
    TRANSCRIPTION,

    /** The suggestions of every update during the lecture (and the outline). */
    LIVE_NOTES,

    /** "I missed something": a recap of the last minutes. */
    CATCH_UP,

    /** The review of the whole lecture. */
    FINAL_REVIEW
}

/**
 * How the lecture is transcribed, following from the models chosen ([AssistantSettings.transcription]).
 */
public enum class TranscriptionMode {
    /** whisper.cpp on this computer, a chunk at a time. */
    LOCAL,

    /** A speech model of Gemini, live: the words arrive while they are said (Gemini 3.5 Transcribe Live). */
    STREAMING,

    /** A speech model of Gemini, a request at every update (Gemini 3.5 Transcribe). */
    SPEECH_CHUNKS,

    /** The model of the live suggestions hears the audio in its own request: one request. */
    WITH_NOTES,

    /** Another general model of Gemini transcribes every chunk in a request of its own. */
    PROMPT_CHUNKS
}

/**
 * The provider of a [LectureTask] and the model of each provider (kept when switching); an empty
 * model is the default one of the task ([AssistantSettings.task]).
 */
@Serializable
public data class TaskModel(
    val provider: ModelProvider = ModelProvider.GEMINI,
    val gemini: String = "",
    val local: String = ""
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
 * @param spokenLanguage language spoken, ISO code ("it", "en") or "auto" (recognized).
 * @param reviewAtTheEnd the final review is written when a lecture ends (otherwise on request).
 */
@Serializable
public data class AssistantSettings(
    @Transient val apiKey: String = "",
    val generateHere: Boolean = isComputer,
    val chunkSeconds: Int = 60,
    val notesLanguage: String = "",
    val thinkingLevel: String = "low",
    val tasks: Map<LectureTask, TaskModel> = emptyMap(),
    val localUrl: String = DEFAULT_LOCAL_URL,
    val whisperModel: String = "",
    val spokenLanguage: String = "auto",
    /** What to do with the lectures received from devices without the models (see [ReceivedLectures]). */
    val receivedLectures: ReceivedLectures = ReceivedLectures.ASK,
    val reviewAtTheEnd: Boolean = true
) {
    /** Provider and models of [task] (the defaults where not configured). */
    public fun task(task: LectureTask): TaskModel {
        val defaults = defaultTask(task)
        val saved = tasks[task] ?: return defaults
        return saved.copy(gemini = saved.gemini.trim().ifEmpty { defaults.gemini }, local = saved.local.trim().ifEmpty { defaults.local })
    }

    internal fun withTask(task: LectureTask, model: TaskModel): AssistantSettings = copy(tasks = tasks + (task to model))

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

    /** How the lecture is transcribed. */
    val transcription: TranscriptionMode
        get() = if (task(LectureTask.TRANSCRIPTION).provider == ModelProvider.LOCAL) TranscriptionMode.LOCAL else onlineTranscription

    /** How Gemini transcribes the lecture, with the model of the transcription (also when it is local now). */
    val onlineTranscription: TranscriptionMode
        get() {
            val model = task(LectureTask.TRANSCRIPTION).gemini
            val notes = task(LectureTask.LIVE_NOTES)
            return when {
                isSpeechModel(model) && isLiveModel(model) -> TranscriptionMode.STREAMING
                isSpeechModel(model) -> TranscriptionMode.SPEECH_CHUNKS
                notes.provider == ModelProvider.GEMINI && notes.gemini == model -> TranscriptionMode.WITH_NOTES
                else -> TranscriptionMode.PROMPT_CHUNKS
            }
        }

    /** The BCP-47 codes of the language spoken for the speech models of Gemini (empty: recognized). */
    val speechLocales: List<String> get() = SPEECH_LOCALES[spokenLanguage].orEmpty()

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
     * These settings with the combination that cannot work corrected: where the speech is not
     * recognized on the device ([canTranscribe] : the phones), Gemini transcribes it.
     */
    internal fun consistent(canTranscribe: Boolean = true): AssistantSettings {
        val transcription = task(LectureTask.TRANSCRIPTION)
        return if (!canTranscribe && transcription.provider == ModelProvider.LOCAL) {
            withTask(LectureTask.TRANSCRIPTION, transcription.copy(provider = ModelProvider.GEMINI))
        } else {
            this
        }
    }

    public companion object {
        internal const val DEFAULT_MODEL = "gemini-3.8-flash"

        /** The speech model of Gemini transcribing live. */
        internal const val DEFAULT_SPEECH_MODEL = "gemini-3.5-transcribe-live"
        internal const val DEFAULT_LOCAL_MODEL = "gemma4:12b"

        /** Smaller local model for the requests of every minute (it must keep up with the lecture). */
        internal const val DEFAULT_LOCAL_LIVE_MODEL = "gemma4:e4b"
        internal const val DEFAULT_LOCAL_URL = "http://localhost:11434"
        internal val CHUNK_SECONDS_RANGE = 30..120
        internal val THINKING_LEVELS = listOf("low", "medium", "high")

        internal fun defaultTask(task: LectureTask) = when (task) {
            LectureTask.TRANSCRIPTION -> TaskModel(gemini = DEFAULT_SPEECH_MODEL)
            LectureTask.LIVE_NOTES, LectureTask.CATCH_UP -> TaskModel(gemini = DEFAULT_MODEL, local = DEFAULT_LOCAL_LIVE_MODEL)
            LectureTask.FINAL_REVIEW -> TaskModel(gemini = DEFAULT_MODEL, local = DEFAULT_LOCAL_MODEL)
        }

        /** A speech model of Gemini (it transcribes only: no prompt). */
        internal fun isSpeechModel(model: String): Boolean = "transcribe" in model.lowercase()

        /** A model of the Live API (a WebSocket). */
        internal fun isLiveModel(model: String): Boolean = "-live" in model.lowercase()

        /** The model for recorded audio of a live speech model (gemini-3.5-transcribe-live: gemini-3.5-transcribe). */
        internal fun recordedSpeechModel(model: String): String = model.replace("-live", "")

        /** The locales of the speech models (their table), by ISO code: the variants of a language together. */
        private val SPEECH_LOCALES = mapOf(
            "it" to listOf("it-IT"), "en" to listOf("en-US", "en-GB"), "es" to listOf("es-ES", "es-419"), "fr" to listOf("fr-FR"),
            "de" to listOf("de-DE"), "pt" to listOf("pt-PT", "pt-BR"), "nl" to listOf("nl-NL"), "pl" to listOf("pl-PL"),
            "ro" to listOf("ro-RO"), "el" to listOf("el-GR"), "ru" to listOf("ru-RU"), "uk" to listOf("uk-UA"),
            "zh" to listOf("cmn-Hans-CN"), "ja" to listOf("ja-JP"), "ar" to listOf("ar-EG")
        )
    }
}

/**
 * Persists the [AssistantSettings] in `assistant-settings.json` of the [lectureDataDirectory], the
 * API key in [secrets]. [canTranscribe]: whether the speech is recognized on this device (see
 * [AssistantSettings.consistent]).
 */
public class AssistantSettingsStore(
    private val path: String = lectureDataDirectory() + "/assistant-settings.json",
    private val secrets: SecretStorage = SecretStorage.System,
    private val canTranscribe: Boolean = WhisperTranscriber.isAvailable
) {
    public var settings: AssistantSettings by mutableStateOf(load())
        private set

    /** The configured key, or the `GEMINI_API_KEY` environment variable. */
    internal val apiKey: String get() = settings.apiKey.trim().ifEmpty { environmentApiKey().orEmpty() }

    internal val hasApiKey: Boolean get() = apiKey.isNotEmpty()

    /** `true` when the key comes from the environment variable. */
    internal val usesEnvironmentKey: Boolean get() = settings.apiKey.isBlank() && !environmentApiKey().isNullOrEmpty()

    public fun update(newSettings: AssistantSettings) {
        val sanitized = newSettings.copy(
            apiKey = newSettings.apiKey.trim(),
            chunkSeconds = newSettings.chunkSeconds.coerceIn(AssistantSettings.CHUNK_SECONDS_RANGE),
            thinkingLevel = newSettings.thinkingLevel.takeIf { it in AssistantSettings.THINKING_LEVELS } ?: "low",
            tasks = LectureTask.entries.associateWith { newSettings.task(it) },
            localUrl = newSettings.localUrl.trim().trimEnd('/').ifEmpty { AssistantSettings.DEFAULT_LOCAL_URL },
            whisperModel = newSettings.whisperModel.trim(),
            // An ISO code ("it") or "auto": a name typed by hand is not understood by the speech models
            spokenLanguage = newSettings.spokenLanguage.trim().lowercase().takeIf { it == "auto" || ISO_LANGUAGE.matches(it) } ?: "auto"
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
