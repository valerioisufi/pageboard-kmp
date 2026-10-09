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
    fun withoutTheSpeechRecognitionGeminiTranscribes() {
        val local = TaskModel(provider = ModelProvider.LOCAL)
        val all = LectureTask.entries.fold(AssistantSettings()) { settings, task -> settings.withTask(task, local) }

        // A computer: everything on it
        assertFalse(all.consistent(canTranscribe = true).sendsAudio)
        // A phone: transcribed online, followed and reviewed by Ollama (of a computer of the network) on the transcript
        val phone = all.consistent(canTranscribe = false)
        assertTrue(phone.sendsAudio)
        assertEquals(ModelProvider.LOCAL, phone.task(LectureTask.LIVE_NOTES).provider)
        assertEquals(ModelProvider.LOCAL, phone.task(LectureTask.FINAL_REVIEW).provider)
        val store = AssistantSettingsStore(File(directory, "phone.json").absolutePath, MemorySecrets(), canTranscribe = false)
        store.update(all)
        // (Saved with the models of every request filled in)
        assertEquals(LectureTask.entries.map { phone.task(it) }, LectureTask.entries.map { store.settings.task(it) })
    }
}
