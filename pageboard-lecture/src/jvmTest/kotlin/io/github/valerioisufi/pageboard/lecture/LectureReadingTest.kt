package io.github.valerioisufi.pageboard.lecture

import io.github.valerioisufi.pageboard.lecture.handwriting.HandwritingReader
import io.github.valerioisufi.pageboard.lecture.testing.MemorySecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the external models read of the lectures: the handwriting by page and the course, as saved (`docs/plans/agent-coverage.md`). */
class LectureReadingTest {
    private val directory: File = Files.createTempDirectory("lecture-reading").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private val assistant = LectureAssistant(
        settingsStore = AssistantSettingsStore(File(directory, "settings.json").absolutePath, MemorySecrets()),
        captureFactory = { null },
        audioOutput = null,
        library = LectureLibrary(File(directory, "lectures").absolutePath),
        stateContext = Dispatchers.Unconfined,
        canGenerate = false
    )

    @Test
    fun theHandwritingIsReadByPageWithoutAModel() = runBlocking<Unit> {
        val file = File(assistant.library.documentDirectory("notebook"), "handwriting.json")
        file.parentFile.mkdirs()
        file.writeText(
            HandwritingReader.encode(
                linkedMapOf(
                    "block-1" to HandwritingReader.Entry("page-1", "Gauss"),
                    "block-2" to HandwritingReader.Entry("page-2", "Faraday"),
                    "block-3" to HandwritingReader.Entry("page-1", " flux = q / ε0 "),
                    "block-4" to HandwritingReader.Entry("page-2", "  ")
                )
            )
        )
        assertEquals(mapOf("page-1" to "Gauss\nflux = q / ε0", "page-2" to "Faraday"), assistant.handwritingByPage("notebook"))
        assertEquals(emptyMap(), assistant.handwritingByPage("another"))
    }

    @Test
    fun theCourseIsReadAsSaved() = runBlocking<Unit> {
        assertNull(assistant.courseContext("notebook"))
        assistant.library.saveContext("notebook", CourseContext("Physics 2, second year", edited = true))
        assertEquals("Physics 2, second year", assistant.courseContext("notebook")?.text)
    }
}
