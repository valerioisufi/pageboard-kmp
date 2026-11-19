package io.github.valerioisufi.pageboard.pdf

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertTrue

class PdfiumTest {
    @OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
    @Test
    fun testPdfiumEngineOpenAndRender() {
        val path = NSTemporaryDirectory() + "test_sample.pdf"
        val written = NSString.create(string = SAMPLE_PDF).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        assertTrue(written, "Cannot write $path")
        try {
            runPdfiumSmokeTest(path)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun thePagesKeptLoadedAreDrawnAsTheyAre() {
        val path = NSTemporaryDirectory() + "loaded_pages.pdf"
        try {
            runLoadedPagesTest(path)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }
}
