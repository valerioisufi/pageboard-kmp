package io.github.valerioisufi.pageboard.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Assume.assumeTrue

class DesktopDataLayoutTest {
    private val data = Files.createTempDirectory("data").toFile()

    @AfterTest
    fun deleteData() {
        data.deleteRecursively()
    }

    @Test
    fun whatWindowsPutInTheResourcesIsMovedBesideThem() {
        write("resources/lectures/device-id", "d1")
        write("resources/lectures/assistant-settings.json", "{}")
        write("resources/lectures/doc-5/2026-10-01_10-00-00-a1b2c3/lecture.json", "lecture")
        write("resources/lectures/doc-5/2026-10-01_10-00-00-a1b2c3/audio.opus", "audio")
        write("resources/whisper/ggml-base.bin", "model")
        write("resources/thumbnails/doc-5.png", "thumbnail")
        write("resources/device-name.txt", "PC")
        write("resources/models/embedding/model.tflite", "local model")
        write("resources/litert-cache/model.bin", "cache")
        write("resources/search/index.db", "index")
        write("resources/image_0a1b.png", "resource")
        // Every file, out of the resources unless it is one
        val expected = files().mapKeys { (path, _) ->
            val inResources = path.removePrefix("resources/")
            if (inResources.substringBefore('/') in DesktopDataLayout.MOVED) inResources else path
        }

        DesktopDataLayout.bringUpToDate(data)

        assertEquals(expected, files())
        assertEquals("d1", read("lectures/device-id"))
        assertEquals("audio", read("lectures/doc-5/2026-10-01_10-00-00-a1b2c3/audio.opus"))
        assertEquals("PC", read("device-name.txt"))
        assertEquals("local model", read("models/embedding/model.tflite"))
        assertEquals("index", read("search/index.db"))
        // The resources stay
        assertEquals("resource", read("resources/image_0a1b.png"))
        assertFalse(File(data, "resources/lectures").exists())
    }

    @Test
    fun anEntryOnBothSidesIsMergedAndNothingIsLost() {
        // Recorded by a build with the fix, then by one without it
        write("lectures/device-id", "new")
        write("lectures/doc-5/2026-10-02_10-00-00-aaaaaa/lecture.json", "fixed")
        write("resources/lectures/device-id", "old")
        write("resources/lectures/doc-5/2026-10-03_10-00-00-bbbbbb/lecture.json", "unfixed")
        write("resources/lectures/doc-5/2026-10-03_10-00-00-bbbbbb/audio.opus", "audio")
        write("resources/lectures/doc-6/2026-10-03_11-00-00-cccccc/lecture.json", "other document")
        write("device-name.txt", "new name")
        write("resources/device-name.txt", "old name")
        val contents = files().values.sorted()

        DesktopDataLayout.bringUpToDate(data)

        assertEquals(contents, files().values.sorted())
        assertEquals("new", read("lectures/device-id"))
        assertEquals("fixed", read("lectures/doc-5/2026-10-02_10-00-00-aaaaaa/lecture.json"))
        assertEquals("unfixed", read("lectures/doc-5/2026-10-03_10-00-00-bbbbbb/lecture.json"))
        assertEquals("audio", read("lectures/doc-5/2026-10-03_10-00-00-bbbbbb/audio.opus"))
        assertEquals("other document", read("lectures/doc-6/2026-10-03_11-00-00-cccccc/lecture.json"))
        assertEquals("new name", read("device-name.txt"))
        // Kept where they are: the files on both sides
        assertEquals("old", read("resources/lectures/device-id"))
        assertEquals("old name", read("resources/device-name.txt"))
        assertFalse(File(data, "resources/lectures/doc-5").exists())
    }

    @Test
    fun aDirectoryUpToDateIsLeftAsItIs() {
        write("lectures/doc-5/2026-10-02_10-00-00-aaaaaa/lecture.json", "lecture")
        write("resources/image_0a1b.png", "resource")
        val before = files()

        DesktopDataLayout.bringUpToDate(data)
        assertEquals(before, files())
        DesktopDataLayout.bringUpToDate(data)
        assertEquals(before, files())
    }

    @Test
    fun theDataDirectoryIsBroughtUpToDateWhenFirstRead() {
        write("resources/lectures/doc-5/2026-10-01_10-00-00-a1b2c3/lecture.json", "lecture")
        val previous = System.getProperty(PAGEBOARD_DATA_DIRECTORY_PROPERTY)
        System.setProperty(PAGEBOARD_DATA_DIRECTORY_PROPERTY, data.path)
        try {
            assertEquals(data.absoluteFile, pageboardDesktopDataDirectory.absoluteFile)
            assertEquals("lecture", read("lectures/doc-5/2026-10-01_10-00-00-a1b2c3/lecture.json"))
            assertEquals(data.absoluteFile, File(dataDirectory()).absoluteFile)
        } finally {
            if (previous == null) System.clearProperty(PAGEBOARD_DATA_DIRECTORY_PROPERTY) else System.setProperty(PAGEBOARD_DATA_DIRECTORY_PROPERTY, previous)
        }
    }

    @Test
    fun thePathsOfThePlatformAreSplitOnItsSeparators() {
        val resources = File(data, "resources").path
        assertEquals("resources", PlatformFiles.name(resources))
        assertEquals(data.path, PlatformFiles.parent(resources))
        assertEquals(data.path, PlatformFiles.parent(resources + File.separator))
    }

    @Test
    fun aPathOfWindowsIsSplitOnBothSeparators() {
        assumeTrue("Windows only", File.separatorChar == '\\')
        // Built with '/' beside one given by Windows
        assertEquals("2026-10-01_10-00-00-a1b2c3", PlatformFiles.name("C:\\Users\\me\\.pencilnotes/lectures/doc-5/2026-10-01_10-00-00-a1b2c3"))
        assertEquals("2026-10-01_10-00-00-a1b2c3", PlatformFiles.name("C:\\Users\\me\\.pencilnotes\\lectures\\doc-5\\2026-10-01_10-00-00-a1b2c3"))
        assertEquals("C:\\Users\\me\\.pencilnotes", PlatformFiles.parent("C:\\Users\\me\\.pencilnotes\\resources"))
        assertEquals("C:\\Users\\me\\.pencilnotes\\lectures", PlatformFiles.parent("C:\\Users\\me\\.pencilnotes/lectures/doc-5"))
    }

    private fun write(path: String, text: String) {
        File(data, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun read(path: String): String = File(data, path).readText()

    /** The files of [data] by their path in it (with `/`), and what they hold. */
    private fun files(): Map<String, String> = data.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(data).invariantSeparatorsPath to it.readText() }
}
