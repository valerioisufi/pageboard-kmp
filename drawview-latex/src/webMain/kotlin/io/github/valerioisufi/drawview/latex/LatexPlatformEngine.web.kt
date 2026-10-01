package io.github.valerioisufi.drawview.latex

import androidx.compose.ui.graphics.ImageBitmap

/**
 * The native MicroTeX engine is not compiled for the browser: formulas are rendered as plain
 * text by the callers (see [LatexEngine.isNativeReady]).
 */
actual object LatexPlatformEngine {
    actual fun init(resourcesDirectory: String): Boolean = false

    /** Whether a native engine exists on this platform. */
    actual fun isSupported(): Boolean = false

    actual fun isNativeReady(): Boolean = false

    actual fun renderFormula(latex: String, fontSizePx: Float, colorArgb: Int, paddingPx: Float): LatexResult? = null

    actual fun generateErrorBitmap(latex: String, fontSizePx: Float): ImageBitmap = ImageBitmap(1, 1)
}

internal actual fun texResourcesDirectory(): String = "/drawview/tex"
