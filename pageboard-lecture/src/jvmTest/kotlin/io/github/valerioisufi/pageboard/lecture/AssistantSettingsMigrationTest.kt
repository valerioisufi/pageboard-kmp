package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.testing.MemorySecrets
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The file of the settings written by the version before the models of the device (version 1), read by this one. */
class AssistantSettingsMigrationTest {
    private val directory: File = Files.createTempDirectory("settings").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** `assistant-settings.json` as the previous version wrote it (no `version`), copied into [directory]. */
    private fun versionOne(): File {
        val fixture = javaClass.getResource("/settings/assistant-settings-v1.json")!!.readText()
        return File(directory, "assistant-settings.json").apply { writeText(fixture) }
    }

    @Test
    fun aFileOfVersionOneKeepsItsTasksItsModelsAndOllama() {
        val file = versionOne()
        val before = file.readText()
        val settings = AssistantSettingsStore(file.absolutePath, MemorySecrets(), canTranscribe = true).settings

        assertEquals(LocalEngine.OLLAMA, settings.localEngine)
        assertEquals(90, settings.chunkSeconds)
        assertEquals("it", settings.notesLanguage)
        assertEquals("it", settings.spokenLanguage)
        assertEquals("http://192.168.1.20:11434", settings.localUrl)
        assertEquals("/models/ggml-large-v3-turbo-q5_0.bin", settings.whisperModel)
        val live = settings.task(LectureTask.LIVE_NOTES)
        assertEquals(ModelProvider.LOCAL, live.provider)
        assertEquals("gemma4:e4b", settings.modelOf(live))
        val catchUp = settings.task(LectureTask.CATCH_UP)
        assertEquals(ModelProvider.GEMINI, catchUp.provider)
        assertEquals("gemini-3.8-flash", settings.modelOf(catchUp))
        val review = settings.task(LectureTask.FINAL_REVIEW)
        assertEquals(ModelProvider.LOCAL, review.provider)
        assertEquals("gemma4:26b", settings.modelOf(review))
        assertEquals("gemini-3.8-pro", review.gemini)
        assertEquals(ModelProvider.LOCAL, settings.reviewProvider)
        // Read only: the file is as it was until the settings change
        assertEquals(before, file.readText())
    }

    @Test
    fun onceSavedItIsVersionTwoAndStillWithOllama() {
        val file = versionOne()
        val store = AssistantSettingsStore(file.absolutePath, MemorySecrets(), canTranscribe = true)
        store.update(store.settings.copy(chunkSeconds = 60))
        val text = file.readText()
        assertTrue(text.contains("\"version\": 2"), text)
        assertTrue(text.contains("\"localEngine\": \"OLLAMA\""), text)

        val again = AssistantSettingsStore(file.absolutePath, MemorySecrets(), canTranscribe = true).settings
        assertEquals(LocalEngine.OLLAMA, again.localEngine)
        assertEquals(60, again.chunkSeconds)
        assertEquals("gemma4:26b", again.modelOf(again.task(LectureTask.FINAL_REVIEW)))
    }

    @Test
    fun aNewInstallationStartsOnTheDeviceAndKeepsItsChoices() {
        val path = File(directory, "assistant-settings.json").absolutePath
        val store = AssistantSettingsStore(path, MemorySecrets(), canTranscribe = true)
        assertEquals(LocalEngine.DEVICE, store.settings.localEngine)
        val live = store.settings.task(LectureTask.LIVE_NOTES)
        // A computer runs these tests
        assertEquals("gemma-4-e4b", live.device)
        assertEquals("gemma-4-12b", store.settings.task(LectureTask.FINAL_REVIEW).device)

        store.update(store.settings.withTask(LectureTask.LIVE_NOTES, live.copy(provider = ModelProvider.LOCAL, device = "gemma-4-e2b")))
        val again = AssistantSettingsStore(path, MemorySecrets(), canTranscribe = true).settings
        assertEquals(LocalEngine.DEVICE, again.localEngine)
        assertEquals("gemma-4-e2b", again.modelOf(again.task(LectureTask.LIVE_NOTES)))
        // Switched to Ollama: its model is kept
        assertEquals("gemma4:e4b", again.copy(localEngine = LocalEngine.OLLAMA).modelOf(again.task(LectureTask.LIVE_NOTES)))
    }
}
