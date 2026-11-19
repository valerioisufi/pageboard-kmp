package io.github.valerioisufi.pageboard.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformFilesPathTest {
    @Test
    fun theNameIsTheLastSegment() {
        assertEquals("lecture", PlatformFiles.name("data/doc-5/lecture"))
        assertEquals("lecture", PlatformFiles.name("data/doc-5/lecture/"))
        assertEquals("audio.opus", PlatformFiles.name("audio.opus"))
    }

    @Test
    fun theParentHoldsThePath() {
        // Its separators are the ones of the platform: compared by their segments
        val parent = PlatformFiles.parent("data/doc-5/lecture")!!
        assertEquals("doc-5", PlatformFiles.name(parent))
        assertEquals("data", PlatformFiles.name(PlatformFiles.parent(parent)!!))
        assertEquals("doc-5", PlatformFiles.name(PlatformFiles.parent("data/doc-5/lecture/")!!))
        assertNull(PlatformFiles.parent("lecture"))
    }
}
