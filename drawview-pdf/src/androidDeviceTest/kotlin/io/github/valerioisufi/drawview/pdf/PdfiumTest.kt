package io.github.valerioisufi.drawview.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfiumTest {
    @Test
    fun testPdfiumEngineOpenAndRender() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = File(context.cacheDir, "test_sample.pdf")
        pdf.writeText(SAMPLE_PDF)
        try {
            runPdfiumSmokeTest(pdf.absolutePath)
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun thePagesKeptLoadedAreDrawnAsTheyAre() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = File(context.cacheDir, "loaded_pages.pdf")
        try {
            runLoadedPagesTest(pdf.absolutePath)
        } finally {
            pdf.delete()
        }
    }
}
