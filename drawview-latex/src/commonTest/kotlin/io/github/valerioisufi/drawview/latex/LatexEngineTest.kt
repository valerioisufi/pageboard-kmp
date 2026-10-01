package io.github.valerioisufi.drawview.latex

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LatexEngineTest {

    @Test
    fun testSanitizeLatexString() {
        assertEquals("x^2 + y^2 = r^2", LatexEngine.sanitizeLatexString("\$\$x^2 + y^2 = r^2\$\$"))
        assertEquals("E = mc^2", LatexEngine.sanitizeLatexString("\$E = mc^2\$"))
        assertEquals("\\frac{a}{b}", LatexEngine.sanitizeLatexString("\\[\\frac{a}{b}\\]"))
        assertEquals("\\alpha", LatexEngine.sanitizeLatexString("\\(\\alpha\\)"))

        // Lone exponent/subscript
        assertEquals("{}^2", LatexEngine.sanitizeLatexString("^2"))
        assertEquals("{}_i", LatexEngine.sanitizeLatexString("_i"))

        // Trailing backslash
        assertEquals("\\frac{a}{b}", LatexEngine.sanitizeLatexString("\\frac{a}{b}\\"))

        // Unbalanced braces
        assertEquals("\\frac{a}{b}", LatexEngine.sanitizeLatexString("\\frac{a}{b"))
        assertEquals("\\sqrt{x+1}", LatexEngine.sanitizeLatexString("\\sqrt{x+1"))
    }

    @Test
    fun testRenderFormulaAndErrorBadge() = runTest {
        // The resources are unpacked from the Compose resources and the native engine is loaded
        assertTrue(LatexEngine.initialize(), "MicroTeX must initialize")
        assertTrue(LatexEngine.isReady.value)

        val result = LatexEngine.renderFormula(
            latex = "\\int_{0}^{\\infty} e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}",
            fontSizePx = 28f,
            color = Color.Black
        )
        assertNotNull(result, "Complex integral formula must render successfully")
        assertTrue(result.width > 0)
        assertTrue(result.height > 0)
        assertTrue(result.baselineRatio in 0f..1f)

        // Test error bitmap fallback
        val errorBadge = LatexEngine.generateErrorBitmap("\\broken{syntax", 24f)
        assertTrue(errorBadge.width > 0)
        assertTrue(errorBadge.height > 0)

        // Test adapter
        val adapter = LatexEngine.asTileAdapter()
        val adapterBitmap = adapter.renderLatex("\\sum_{n=1}^\\infty \\frac{1}{n^2}", 24f, Color.Blue)
        assertNotNull(adapterBitmap)
        assertTrue(adapterBitmap.width > 0)
        assertTrue(adapterBitmap.height > 0)
    }
}
