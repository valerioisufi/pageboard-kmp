package io.nano.tex

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class LatexNativeTest {

    @Test
    fun testNativeTeXEngineInitializationAndParsing() {
        val latex = LaTeX.instance()
        assertNotNull(latex)

        // Find resources directory (source tree of the Compose resources)
        val candidates = listOf(
            File("src/commonMain/composeResources/files/tex"),
            File("pageboard-latex/src/commonMain/composeResources/files/tex"),
            File("../pageboard-latex/src/commonMain/composeResources/files/tex")
        )
        val resDir = candidates.firstOrNull { it.exists() && File(it, "fonts/latin/r10.ttf").exists() }
        println("Current dir: ${File(".").absolutePath}")
        assertNotNull(resDir, "TeX resources folder must exist. Checked: ${candidates.map { it.absolutePath }}")
        println("Initializing LaTeX with resDir: ${resDir.absolutePath}")

        latex.init(resDir.absolutePath)
        assertTrue(latex.isInitialized)

        val render = latex.parse("\\frac{a+b}{c}", 24f, 0xFF000000.toInt())
        assertNotNull(render)
        println("Render parsed: width=${render.width}, height=${render.height}, baseline=${render.baseline}")
        assertTrue(render.width > 0)
        assertTrue(render.height > 0)

        // Draw to Skia Bitmap
        val bmp = org.jetbrains.skia.Bitmap()
        bmp.allocN32Pixels(render.width.coerceAtLeast(1), render.height.coerceAtLeast(1))
        val canvas = org.jetbrains.skia.Canvas(bmp)
        canvas.clear(0)
        val g2 = Graphics2D(canvas)
        render.draw(g2, 0, 0)
        assertTrue(bmp.width > 0)
        assertTrue(bmp.height > 0)
        println("Formula successfully rendered to Skia Bitmap: ${bmp.width}x${bmp.height}")
    }
}
