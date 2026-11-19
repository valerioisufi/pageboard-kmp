package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.platform.PlatformFiles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** On the device, a resource being received is a `.part` file, renamed when whole (in the data directory of the tests). */
class PartFileDownloadTest {
    @Test
    fun theBytesGoToAPartFileRenamedWhenWhole() {
        val download = assertNotNull(PlatformResourceFiles.receive("bin"))
        assertTrue(download.append(byteArrayOf(1, 2, 3)))
        assertTrue(download.append(byteArrayOf(4, 5)))
        val path = assertNotNull(download.finish())
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), PlatformFiles.readBytes(path))
        assertFalse(PlatformFiles.exists("$path.part"))
    }

    @Test
    fun aDownloadDiscardedLeavesNoFile() {
        val download = assertNotNull(PlatformResourceFiles.receive("bin"))
        assertTrue(download.append(byteArrayOf(1, 2, 3)))
        download.discard()
        val parts = java.io.File(PlatformFiles.resourcesDirectory()).listFiles().orEmpty().filter { it.name.endsWith(".part") }
        assertTrue(parts.isEmpty(), "left: $parts")
    }
}
