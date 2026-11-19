package io.github.valerioisufi.pageboard.ml

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstalledModelsTest {
    private val directory = newTestDirectory()
    private val bytes = ByteArray(1000) { it.toByte() }
    private val spec = testSpec(bytes)
    private val other = testSpec(ByteArray(10), id = "other-model")
    private val catalog = ModelCatalog(listOf(spec, other))

    @AfterTest
    fun deleteFiles() = deleteDirectory(directory)

    @Test
    fun aModelIsInstalledWhenItsWholeFileIsThere() {
        val models = InstalledModels(catalog, directory)
        assertNull(models[spec])

        writeFile(models.pathOf(spec), bytes.copyOf(500))
        models.refresh()
        assertNull(models[spec], "half a file")
        assertEquals(emptySet(), models.installed.value)

        writeFile(models.pathOf(spec), bytes)
        models.refresh()
        assertEquals(InstalledModel(spec, "$directory/test-model/test-model.litertlm"), models["test-model"])
        assertEquals(setOf("test-model"), models.installed.value)
    }

    @Test
    fun deletingAModelDeletesItsFilesOnly() {
        val models = InstalledModels(catalog, directory)
        writeFile(models.pathOf(spec), bytes)
        writeFile(models.partialPathOf(spec), bytes)
        writeFile(models.pathOf(other), ByteArray(10))
        val unrelated = "$directory/test-model/notes.txt"
        writeFile(unrelated, byteArrayOf(1, 2, 3))
        models.refresh()

        assertTrue(models.delete(spec))
        assertFalse(exists(models.pathOf(spec)))
        assertFalse(exists(models.partialPathOf(spec)))
        assertTrue(exists(unrelated))
        assertNotNull(models[other])
        assertEquals(setOf("other-model"), models.installed.value)
    }
}
