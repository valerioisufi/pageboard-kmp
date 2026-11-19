package io.github.valerioisufi.pageboard.platform

import io.github.valerioisufi.pageboard.platform.SecureStore
import io.github.valerioisufi.pageboard.platform.pageboardDesktopDataDirectory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureStoreTest {
    @Test
    fun aSecretIsKeptReadableByItsUserOnly() {
        val name = "test-secret-${System.nanoTime()}"
        try {
            assertNull(SecureStore.read(name))
            assertTrue(SecureStore.write(name, "s3cret"))
            assertEquals("s3cret", SecureStore.read(name))
            // The permissions where the file system has them (not on Windows)
            if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                val file = File(pageboardDesktopDataDirectory, "secure/$name")
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
            }
            assertTrue(SecureStore.write(name, null))
            assertNull(SecureStore.read(name))
        } finally {
            SecureStore.write(name, null)
        }
    }
}
