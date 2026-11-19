package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.testing.MemorySecrets
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
    fun withoutTheSpeechRecognitionTheLecturesAreRecordedOnly() {
        val local = TaskModel(provider = ModelProvider.LOCAL)
        val all = LectureTask.entries.fold(AssistantSettings(generateHere = true)) { settings, task -> settings.withTask(task, local) }

        // A computer: everything on it
        assertTrue(all.consistent(canTranscribe = true).generateHere)
        // A phone: recorded only, a computer writes the notes; the models chosen are kept
        val store = AssistantSettingsStore(File(directory, "phone.json").absolutePath, MemorySecrets(), canTranscribe = false)
        store.update(all)
        assertFalse(store.settings.generateHere)
        assertEquals(LectureTask.entries.map { all.task(it) }, LectureTask.entries.map { store.settings.task(it) })
    }
}
