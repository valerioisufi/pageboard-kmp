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

/**
 * Settings of the lecture assistant.
 *
 * @param apiKey key of the Gemini API (empty: the `GEMINI_API_KEY` environment variable).
 * @param chunkSeconds new audio sent at every update.
 * @param neighborPages pages sent around the visible ones (before and after).
 * @param sendSlides sends the PDF documents of the pages around the visible ones (uploaded once).
 * @param notesLanguage language of the notes (empty: the language of the lecture).
 * @param courseContext course, topic and terminology, written by the student.
 */
@Serializable
data class AssistantSettings(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val chunkSeconds: Int = 60,
    val neighborPages: Int = 1,
    val sendSlides: Boolean = true,
    val notesLanguage: String = "",
    val courseContext: String = "",
    val thinkingLevel: String = "low"
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        val CHUNK_SECONDS_RANGE = 30..120
        val THINKING_LEVELS = listOf("low", "medium", "high")
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
            model = newSettings.model.trim().ifEmpty { AssistantSettings.DEFAULT_MODEL },
            chunkSeconds = newSettings.chunkSeconds.coerceIn(AssistantSettings.CHUNK_SECONDS_RANGE),
            neighborPages = newSettings.neighborPages.coerceIn(0, 3),
            thinkingLevel = newSettings.thinkingLevel.takeIf { it in AssistantSettings.THINKING_LEVELS } ?: "low"
        )
        settings = sanitized
        if (PlatformFiles.writeBytes(path, json.encodeToString(AssistantSettings.serializer(), sanitized).encodeToByteArray())) {
            restrictToOwner(path)
        }
    }

    private fun load(): AssistantSettings {
        val bytes = PlatformFiles.readBytes(path) ?: return AssistantSettings()
        return try {
            json.decodeFromString(AssistantSettings.serializer(), bytes.decodeToString())
        } catch (_: Exception) {
            AssistantSettings()
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }
}
