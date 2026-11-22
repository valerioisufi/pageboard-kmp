package io.github.valerioisufi.pageboard.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The data directory of a desktop application: given once, the property winning over it, never a default. */
class DesktopDataDirectoryTest {
    @Test
    fun withoutTheApplicationNorThePropertyThereIsNone() {
        val directory = DesktopDataDirectory(property = { null })
        assertFalse(directory.isKnown)
        assertFailsWith<IllegalStateException> { directory.read() }
    }

    @Test
    fun theDirectoryOfTheApplicationIsGivenOnce() {
        val directory = DesktopDataDirectory(property = { null })
        directory.init(File("notes"))
        directory.init(File("notes"))
        assertEquals(File("notes").absoluteFile, directory.read())
        assertFailsWith<IllegalStateException> { directory.init(File("other")) }
        assertEquals(File("notes").absoluteFile, directory.read())
    }

    @Test
    fun thePropertyWinsOverTheDirectoryOfTheApplication() {
        val directory = DesktopDataDirectory(property = { "scratch" })
        directory.init(File("notes"))
        assertTrue(directory.isKnown)
        assertEquals(File("scratch"), directory.read())
    }

    @Test
    fun eachSystemHasItsPlaceForTheDataOfAnApplication() {
        val home = File("home")
        val none: (String) -> String? = { null }
        assertEquals(File(home, "Library/Application Support/My Notes"), applicationDataDirectory("My Notes", "Mac OS X", home, none))

        val local = File("local").absoluteFile
        assertEquals(File(local, "My Notes"), applicationDataDirectory("My Notes", "Windows 11", home) { if (it == "LOCALAPPDATA") local.path else null })
        assertEquals(File(home, "AppData/Local/My Notes"), applicationDataDirectory("My Notes", "Windows 11", home, none))

        val xdg = File("xdg").absoluteFile
        assertEquals(File(xdg, "My Notes"), applicationDataDirectory("My Notes", "Linux", home) { if (it == "XDG_DATA_HOME") xdg.path else null })
        assertEquals(File(home, ".local/share/My Notes"), applicationDataDirectory("My Notes", "Linux", home, none))
        // A relative XDG_DATA_HOME is left out, as its specification says
        assertEquals(File(home, ".local/share/My Notes"), applicationDataDirectory("My Notes", "Linux", home) { if (it == "XDG_DATA_HOME") "xdg" else null })
    }

    @Test
    fun theNameOfAnApplicationIsOneSegmentOfAPath() {
        for (name in listOf("", " ", ".", "..", "a/b", "a\\b")) {
            assertFailsWith<IllegalArgumentException>(name) { applicationDataDirectory(name, "Linux", File("home")) { null } }
        }
    }
}
