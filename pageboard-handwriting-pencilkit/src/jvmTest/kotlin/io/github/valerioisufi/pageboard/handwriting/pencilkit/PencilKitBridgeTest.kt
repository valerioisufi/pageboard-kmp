package io.github.valerioisufi.pageboard.handwriting.pencilkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PencilKitBridgeTest {
    private val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")

    @Test
    fun theBridgeIsInTheJarOfTheMacOnly() {
        assertEquals(mac, PencilKitBridge.isLoaded)
        if (!mac) {
            assertEquals(0, pencilKitVersion())
            assertEquals("", pencilKitLanguages())
            return
        }
        // macOS 27 and an SDK with the recognizer: Italian among the languages of the system
        val major = System.getProperty("os.version").substringBefore('.').toIntOrNull() ?: 0
        if (major >= 27 && pencilKitVersion() > 0) {
            assertTrue("it" in PencilKitTextRecognizer.parseLanguages(pencilKitLanguages()), pencilKitLanguages())
        }
    }
}
