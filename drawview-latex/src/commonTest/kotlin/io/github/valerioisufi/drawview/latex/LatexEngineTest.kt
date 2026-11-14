package io.github.valerioisufi.drawview.latex

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.canvas.render.tile.FormulaRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LatexEngineTest {

    @Test
    fun testSanitizeLatexString() {
        assertEquals("x^2 + y^2 = r^2", sanitizeLatexString("\$\$x^2 + y^2 = r^2\$\$"))
        assertEquals("E = mc^2", sanitizeLatexString("\$E = mc^2\$"))
        assertEquals("\\frac{a}{b}", sanitizeLatexString("\\[\\frac{a}{b}\\]"))
        assertEquals("\\alpha", sanitizeLatexString("\\(\\alpha\\)"))

        // Lone exponent/subscript
        assertEquals("{}^2", sanitizeLatexString("^2"))
        assertEquals("{}_i", sanitizeLatexString("_i"))

        // Trailing backslash
        assertEquals("\\frac{a}{b}", sanitizeLatexString("\\frac{a}{b}\\"))

        // Unbalanced braces
        assertEquals("\\frac{a}{b}", sanitizeLatexString("\\frac{a}{b"))
        assertEquals("\\sqrt{x+1}", sanitizeLatexString("\\sqrt{x+1"))
    }

    @Test
    fun testRenderFormulaAndErrorBadge() = runTest {
        // The resources are unpacked from the Compose resources and the native engine is loaded
        val engine = LatexEngine()
        assertTrue(engine.initialize(), "MicroTeX must initialize")
        assertTrue(engine.isReady.value)

        val result = engine.renderFormula(
            latex = "\\int_{0}^{\\infty} e^{-x^2} dx = \\frac{\\sqrt{\\pi}}{2}",
            fontSizePx = 28f,
            colorArgb = Color.Black.toArgb()
        )
        assertNotNull(result, "Complex integral formula must render successfully")
        assertTrue(result.width > 0)
        assertTrue(result.height > 0)
        assertTrue(result.baselineRatio in 0f..1f)

        // Test error bitmap fallback
        val errorBadge = engine.generateErrorBitmap("\\broken{syntax", 24f)
        assertTrue(errorBadge.width > 0)
        assertTrue(errorBadge.height > 0)

        // The formula renderer of the editors (the port of drawview-canvas), formulas as written in a text
        val renderer: FormulaRenderer = engine
        assertTrue(renderer.isReady.value)
        val image = renderer.render(renderer.sanitize("\$\\sum_{n=1}^\\infty \\frac{1}{n^2}\$"), 24f, Color.Blue.toArgb())
        assertNotNull(image)
        assertTrue(image.bitmap.width > 0)
        assertTrue(image.bitmap.height > 0)
        assertTrue(image.baselineRatio in 0f..1f)
    }
}
