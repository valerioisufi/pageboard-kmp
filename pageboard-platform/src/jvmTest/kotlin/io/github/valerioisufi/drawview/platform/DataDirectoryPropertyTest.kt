package io.github.valerioisufi.drawview.platform

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The data directory given under the property of before the rename stops the run. */
class DataDirectoryPropertyTest {
    @Test
    fun theOldPropertyAloneStops() = withProperties(old = "/tmp/scratch", current = null) {
        assertFailsWith<IllegalStateException> { checkDataDirectoryProperty() }
    }

    @Test
    fun theOldPropertyBesideTheNewOneIsLeftAlone() = withProperties(old = "/tmp/scratch", current = "/tmp/scratch") {
        checkDataDirectoryProperty()
    }

    @Test
    fun noPropertyIsLeftAlone() = withProperties(old = null, current = null) {
        checkDataDirectoryProperty()
    }

    /** Runs [block] with the two properties set as given, then puts back what they were. */
    private fun withProperties(old: String?, current: String?, block: () -> Unit) {
        val previous = listOf(OLD, PAGEBOARD_DATA_DIRECTORY_PROPERTY).associateWith { System.getProperty(it) }
        try {
            set(OLD, old)
            set(PAGEBOARD_DATA_DIRECTORY_PROPERTY, current)
            block()
        } finally {
            previous.forEach { (key, value) -> set(key, value) }
        }
    }

    private fun set(key: String, value: String?) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }

    private companion object {
        const val OLD = "drawview.dataDir"
    }
}
