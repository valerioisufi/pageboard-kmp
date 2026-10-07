package io.github.valerioisufi.drawview.lecture

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantSecretsTest {
    private val directory: File = Files.createTempDirectory("settings").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun theKeyIsKeptWithTheSecretsOnly() {
        val secrets = MemorySecrets()
        val path = File(directory, "settings.json").absolutePath
        val store = AssistantSettingsStore(path, secrets)
        store.update(store.settings.copy(apiKey = " secret-key ", chunkSeconds = 90))

        assertEquals("secret-key", secrets.read(AssistantSettingsStore.API_KEY))
        assertFalse(File(path).readText().contains("secret-key"))
        val reloaded = AssistantSettingsStore(path, secrets)
        assertEquals("secret-key", reloaded.settings.apiKey)
        assertEquals(90, reloaded.settings.chunkSeconds)

        // Removed: forgotten
        reloaded.update(reloaded.settings.copy(apiKey = ""))
        assertEquals(null, secrets.read(AssistantSettingsStore.API_KEY))
    }

    @Test
    fun withoutTheSpeechRecognitionTheSuggestionsAreOnline() {
        val local = TaskModel(provider = ModelProvider.LOCAL)
        val all = LectureTask.entries.fold(AssistantSettings()) { settings, task -> settings.withTask(task, local) }

        // A computer: everything on it
        assertFalse(all.consistent(canTranscribe = true).sendsAudio)
        // A phone: transcribed and followed online, reviewed by Ollama (of a computer of the network)
        val phone = all.consistent(canTranscribe = false)
        assertTrue(phone.sendsAudio)
        assertEquals(ModelProvider.GEMINI, phone.task(LectureTask.LIVE_NOTES).provider)
        assertEquals(ModelProvider.LOCAL, phone.task(LectureTask.FINAL_REVIEW).provider)
        val store = AssistantSettingsStore(File(directory, "phone.json").absolutePath, MemorySecrets(), canTranscribe = false)
        store.update(all)
        assertEquals(phone, store.settings.copy(chunkSeconds = phone.chunkSeconds))
    }
}
