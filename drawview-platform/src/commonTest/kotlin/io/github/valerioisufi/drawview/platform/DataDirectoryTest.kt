package io.github.valerioisufi.drawview.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/** The directory of the data, found from the one of the resources on every system. */
class DataDirectoryTest {

    @Test
    fun theParentIsFoundWithTheSeparatorsOfEverySystem() {
        assertEquals("/Users/me/.pencilnotes", parentDirectory("/Users/me/.pencilnotes/resources"))
        assertEquals("/Users/me/.pencilnotes", parentDirectory("/Users/me/.pencilnotes/resources/"))
        assertEquals("C:\\Users\\me\\.pencilnotes", parentDirectory("C:\\Users\\me\\.pencilnotes\\resources"))
        assertEquals("/data/user/0/app/files", parentDirectory("/data/user/0/app/files/drawview_resources"))
    }

    @Test
    fun aPathWithoutSeparatorsIsItsOwnParent() {
        assertEquals("resources", parentDirectory("resources"))
    }
}
