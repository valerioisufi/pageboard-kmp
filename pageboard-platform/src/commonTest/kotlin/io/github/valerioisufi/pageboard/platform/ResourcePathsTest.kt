package io.github.valerioisufi.pageboard.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class ResourcePathsTest {
    @Test
    fun toStoredRelativizesUnixAndIosPaths() {
        val root = "/var/mobile/Containers/Data/Application/OLD-UUID/Documents/pageboard_resources"
        assertEquals("pdf_123.pdf", ResourcePaths.toStored("$root/pdf_123.pdf", root))
        assertEquals("nested/img_1.png", ResourcePaths.toStored("$root/nested/img_1.png", "$root/"))
        assertEquals("pdf_123.pdf", ResourcePaths.toStored("$root//pdf_123.pdf", root))
    }

    @Test
    fun toStoredRelativizesWindowsPathsWithMixedSeparatorsAndDriveCase() {
        val root = """c:\Users\Ada\.pencilnotes\resources"""
        assertEquals(
            "pdf_123.pdf",
            ResourcePaths.toStored("""C:\Users\Ada\.pencilnotes\resources\pdf_123.pdf""", root)
        )
        assertEquals(
            "sub/img_1.png",
            ResourcePaths.toStored("""C:/Users/Ada/.pencilnotes/resources/sub\img_1.png""", """C:\Users\Ada\.pencilnotes\resources\""")
        )
    }

    @Test
    fun toStoredLeavesExternalRelativeEmptyAndTraversalPathsUnchanged() {
        val root = "/data/resources"
        assertEquals("", ResourcePaths.toStored("", root))
        assertEquals("pdf_123.pdf", ResourcePaths.toStored("pdf_123.pdf", root))
        assertEquals("/tmp/slides.pdf", ResourcePaths.toStored("/tmp/slides.pdf", root))
        assertEquals("""D:\other\slides.pdf""", ResourcePaths.toStored("""D:\other\slides.pdf""", """C:\data\resources"""))
        assertEquals("/data/resources_extra/file.pdf", ResourcePaths.toStored("/data/resources_extra/file.pdf", root))
        assertEquals("$root/../secret.pdf", ResourcePaths.toStored("$root/../secret.pdf", root))
        assertEquals(root, ResourcePaths.toStored(root, root))
        assertEquals("$root/", ResourcePaths.toStored("$root/", root))
    }

    @Test
    fun resolveJoinsRelativePathsAndPreservesAbsoluteOrEmpty() {
        assertEquals("/data/resources/pdf_123.pdf", ResourcePaths.resolve("pdf_123.pdf", "/data/resources"))
        assertEquals("/data/resources/sub/pdf_123.pdf", ResourcePaths.resolve("sub/pdf_123.pdf", "/data/resources/"))
        assertEquals("""C:\data\resources/pdf_123.pdf""", ResourcePaths.resolve("pdf_123.pdf", """C:\data\resources\"""))
        assertEquals("", ResourcePaths.resolve("", "/data/resources"))
        assertEquals("/tmp/slides.pdf", ResourcePaths.resolve("/tmp/slides.pdf", "/data/resources"))
        assertEquals("""\\server\share\slides.pdf""", ResourcePaths.resolve("""\\server\share\slides.pdf""", "/data/resources"))
        assertEquals("""C:\tmp\slides.pdf""", ResourcePaths.resolve("""C:\tmp\slides.pdf""", "/data/resources"))
        assertEquals("C:/tmp/slides.pdf", ResourcePaths.resolve("C:/tmp/slides.pdf", "/data/resources"))
    }

    @Test
    fun roundTripRelocatesAcrossIosContainerUuids() {
        val oldContainer = "/var/mobile/Containers/Data/Application/1111-OLD/Documents/pageboard_resources"
        val newContainer = "/var/mobile/Containers/Data/Application/2222-NEW/Documents/pageboard_resources"
        val stored = ResourcePaths.toStored("$oldContainer/pdf_lecture.pdf", oldContainer)
        assertEquals("pdf_lecture.pdf", stored)
        assertEquals("$newContainer/pdf_lecture.pdf", ResourcePaths.resolve(stored, newContainer))
    }
}
