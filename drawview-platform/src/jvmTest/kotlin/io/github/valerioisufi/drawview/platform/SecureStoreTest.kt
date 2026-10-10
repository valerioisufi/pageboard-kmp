package io.github.valerioisufi.drawview.platform

import io.github.valerioisufi.drawview.platform.SecureStore
import io.github.valerioisufi.drawview.platform.drawViewDesktopDataDirectory
import java.io.File
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
        assertNull(SecureStore.read(name))
        assertTrue(SecureStore.write(name, "s3cret"))
        assertEquals("s3cret", SecureStore.read(name))
        val file = File(drawViewDesktopDataDirectory, "secure/$name")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        assertTrue(SecureStore.write(name, null))
        assertNull(SecureStore.read(name))
    }
}
