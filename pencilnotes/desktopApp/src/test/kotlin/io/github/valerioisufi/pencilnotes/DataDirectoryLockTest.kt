package io.github.valerioisufi.pencilnotes

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A process holding the data directory of its argument until it is killed. */
internal object LockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val lock = DataDirectoryLock.acquire(File(args[0]))
        println(if (lock != null) "held" else "refused")
        System.out.flush()
        Thread.sleep(TimeUnit.MINUTES.toMillis(5))
        lock?.close()
    }
}

class DataDirectoryLockTest {
    private val directory: File = Files.createTempDirectory(
        File(System.getProperty("drawview.dataDir")).apply { mkdirs() }.toPath(),
        "lock"
    ).toFile()

    @Test
    fun `the same process cannot take it twice and takes it again once closed`() {
        val first = assertNotNull(DataDirectoryLock.acquire(directory))
        assertNull(DataDirectoryLock.acquire(directory))
        first.close()
        assertNotNull(DataDirectoryLock.acquire(directory)).close()
    }

    @Test
    fun `a directory missing is created`() {
        val missing = File(directory, "a/b")
        assertNotNull(DataDirectoryLock.acquire(missing)).close()
        assertTrue(missing.isDirectory)
    }

    @Test
    fun `another process keeps it out until it dies`() {
        val holder = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-cp", System.getProperty("java.class.path"),
            LockHolder::class.java.name,
            directory.path
        ).redirectErrorStream(true).start()
        try {
            assertEquals("held", holder.inputStream.bufferedReader().readLine())
            assertNull(DataDirectoryLock.acquire(directory))
            // Killed, as a crash: nobody releases the lock but the system
            holder.destroyForcibly()
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS))
            assertNotNull(DataDirectoryLock.acquire(directory)).close()
        } finally {
            holder.destroyForcibly()
        }
    }
}
