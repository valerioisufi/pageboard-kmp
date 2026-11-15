package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.platform.Sha256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The files of the resources received are written as their parts come, and kept when whole and right. */
class ResourceDownloadsTest {
    /** Files in memory, the downloads with the default of [ResourceFiles]. */
    private class MemoryFiles : ResourceFiles {
        val written = mutableMapOf<String, ByteArray>()
        override fun read(path: String): ByteArray? = written[path]
        override fun write(bytes: ByteArray, extension: String): String = "file${written.size}.$extension".also { written[it] = bytes }
    }

    private val bytes = ByteArray(10) { it.toByte() }
    private fun part(from: Int, to: Int, total: Long = bytes.size.toLong()) = ResourceChunk("r", "png", from.toLong(), total, bytes.copyOfRange(from, to))

    @Test
    fun aFileIsKeptOnceWholeAndRight() = runTest {
        val files = MemoryFiles()
        val downloads = ResourceDownloads(files, maxSize = 100)
        assertNull(downloads.add("d", part(0, 4)) { Sha256.of(bytes) })
        assertEquals(4, downloads.receivedOf("d", "r"))
        val whole = assertNotNull(downloads.add("d", part(4, 10)) { Sha256.of(bytes) })
        assertContentEquals(bytes, files.written.getValue(assertNotNull(whole.path)))
        assertEquals(0, downloads.receivedOf("d", "r"))
    }

    @Test
    fun anotherFileThanTheOneNamedIsNotKept() = runTest {
        val files = MemoryFiles()
        val downloads = ResourceDownloads(files, maxSize = 100)
        val whole = assertNotNull(downloads.add("d", part(0, 10)) { Sha256.of(ByteArray(10)) })
        assertNull(whole.path)
        assertTrue(files.written.isEmpty())
    }

    @Test
    fun onlyTheNextPartIsTakenAndTheDownloadGoesOnFromWhereItStopped() = runTest {
        val downloads = ResourceDownloads(MemoryFiles(), maxSize = 100)
        downloads.add("d", part(0, 4)) { null }
        // A part after a gap: left out
        assertNull(downloads.add("d", part(6, 10)) { null })
        assertEquals(4, downloads.receivedOf("d", "r"))
        // Asked again from where it stopped (e.g. over another connection)
        assertNotNull(downloads.add("d", part(4, 10)) { null })
    }

    @Test
    fun anExtensionThatIsNotAFewLettersAndDigitsIsNotPartOfThePath() = runTest {
        val files = MemoryFiles()
        val downloads = ResourceDownloads(files, maxSize = 100)
        val hostile = listOf("x/../../../escaped", "x\\..\\..\\escaped", "..", "", "png\u0000", "toolongextension", "pdf.exe")
        hostile.forEachIndexed { i, extension ->
            val chunk = ResourceChunk("r$i", extension, 0, bytes.size.toLong(), bytes)
            val whole = assertNotNull(downloads.add("d", chunk) { Sha256.of(bytes) })
            assertTrue(assertNotNull(whole.path).endsWith(".bin"), "$extension: ${whole.path}")
        }
        assertEquals("png", receivedExtension("png"))
        assertEquals("JPEG", receivedExtension("JPEG"))
        assertEquals("mp4", receivedExtension("mp4"))
    }

    @Test
    fun aFileLargerThanAllowedIsNotReceived() = runTest {
        val downloads = ResourceDownloads(MemoryFiles(), maxSize = 5)
        assertNull(downloads.add("d", part(0, 4)) { null })
        assertEquals(0, downloads.receivedOf("d", "r"))
    }
}
