package io.github.valerioisufi.drawview.pdf

import java.io.File
import kotlin.test.Test

class PdfiumTest {
    @Test
    fun testPdfiumEngineOpenAndRender() {
        val tempPdf = File.createTempFile("test_sample", ".pdf")
        tempPdf.deleteOnExit()
        tempPdf.writeText(SAMPLE_PDF)
        runPdfiumSmokeTest(tempPdf.absolutePath)
    }
}
